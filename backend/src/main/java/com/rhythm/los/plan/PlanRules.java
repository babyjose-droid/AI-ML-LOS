package com.rhythm.los.plan;

import java.time.LocalDate;
import java.util.*;

/**
 * Loan-plan rules, pure. Housing HS-01..07, education ED-01..06, farm AG-01..06 decide whether the plan is ready for sanction;
 * TR-01..04 decide whether a tranche can be released.
 */
public final class PlanRules {
    private PlanRules() {}

    public record Check(String id, String label, String result, String reason, String ruling) {
        public boolean blocks() { return "FAIL".equals(result) || "PENDING".equals(result) || "REFER".equals(result) && ruling == null; }
    }
    public record Tranche(int seq, String label, double amount, Double stagePct, String payeeKind, Long payeeId) {}
    public record Party(Long id, String name, String kind, String status, boolean empanelled, boolean hasAccount) {}
    public record Result(String status, List<Check> checks, List<String> conditions) {}

    /** Not overridable: a suspended institution, tranches that do not add up to the loan, a land record in someone else's name. */
    public static final Set<String> HARD = Set.of("ED-01", "HS-05", "ED-04");
    /** RBI affordable-housing carpet area (90 sq m outside metros). */
    public static final double AFFORDABLE_SQFT = 968;

    static Check c(String id, String label, String result, String reason) { return new Check(id, label, result, reason, null); }

    public static Result evaluate(String kind, Map<String, Object> d, Map<String, Map<String, Object>> docs, List<Tranche> tranches, Party party,
                                  String applicant, double loan, int tenureMonths, LocalDate today, Map<String, Map<String, Object>> rulings) {
        List<Check> cs = new ArrayList<>();
        List<String> cond = new ArrayList<>();
        switch (kind) {
            case "HOUSING" -> housing(d, docs, tranches, loan, cs, cond);
            case "EDUCATION" -> education(d, docs, tranches, party, applicant, loan, tenureMonths, today, cs, cond);
            case "AGRI" -> agri(d, docs, applicant, loan, cs, cond);
            case "RENTAL" -> rental(d, docs, applicant, loan, tenureMonths, today, cs, cond);
            default -> cs.add(c("PL-00", "Plan kind", "FAIL", "Unknown plan kind " + kind));
        }
        Map<String, Map<String, Object>> rule = rulings == null ? Map.of() : rulings;
        List<Check> out = new ArrayList<>();
        for (Check ch : cs) {
            Map<String, Object> r = rule.get(ch.id());
            out.add(r != null && "REFER".equals(ch.result()) && !HARD.contains(ch.id())
                    ? new Check(ch.id(), ch.label(), ch.result(), ch.reason(), "Exception approved by " + r.get("by") + ": " + r.get("note")) : ch);
        }
        String status = out.stream().anyMatch(x -> "FAIL".equals(x.result())) ? "FAIL"
                : out.stream().anyMatch(x -> "PENDING".equals(x.result())) ? "PENDING"
                : out.stream().anyMatch(x -> "REFER".equals(x.result()) && x.ruling() == null) ? "REFER" : "PASS";
        return new Result(status, out, cond);
    }

    // ---------------------------------------------------------------- housing

    static void housing(Map<String, Object> d, Map<String, Map<String, Object>> docs, List<Tranche> tr, double loan, List<Check> cs, List<String> cond) {
        String purpose = str(d.get("purpose"));
        double estimate = num(d.get("estimate")), own = num(d.get("ownContribution")), carpet = num(d.get("carpetAreaSqft"));
        cs.add(purpose == null || estimate <= 0 ? c("HS-01", "Purpose and cost recorded", "PENDING", "Enter the purpose and the cost (estimate or agreement value)")
                : c("HS-01", "Purpose and cost recorded", "PASS", label(purpose) + " · cost " + rupees(estimate)));
        if (carpet <= 0) cs.add(c("HS-02", "Affordable carpet area", "PENDING", "Enter the carpet area"));
        else cs.add(carpet <= AFFORDABLE_SQFT ? c("HS-02", "Affordable carpet area", "PASS", fmt(carpet) + " sq ft (up to " + fmt(AFFORDABLE_SQFT) + " for affordable housing)")
                : c("HS-02", "Affordable carpet area", "REFER", fmt(carpet) + " sq ft is above " + fmt(AFFORDABLE_SQFT) + " sq ft"));
        if (estimate > 0) {
            double cap = loan <= 3_000_000 ? 0.90 : loan <= 7_500_000 ? 0.80 : 0.75;
            double ltv = loan / estimate;
            cs.add(ltv <= cap + 1e-9 ? c("HS-03", "Loan to cost (RBI housing limits)", "PASS", pct(ltv) + " of the cost (limit " + pct(cap) + ")")
                    : c("HS-03", "Loan to cost (RBI housing limits)", "REFER", pct(ltv) + " of the cost is above " + pct(cap) + "; the maximum loan is " + rupees(Math.floor(estimate * cap))));
            cs.add(own + loan + 1 >= estimate ? c("HS-04", "Own contribution covers the rest", "PASS", "Own " + rupees(own) + " + loan " + rupees(loan) + " cover " + rupees(estimate))
                    : c("HS-04", "Own contribution covers the rest", "REFER", "Own " + rupees(own) + " + loan " + rupees(loan) + " is short of " + rupees(estimate)));
        } else cs.add(c("HS-03", "Loan to cost (RBI housing limits)", "PENDING", "Cost needed"));
        // HS-05 tranche plan
        if (tr.isEmpty()) cs.add(c("HS-05", "Stage-wise release plan", "PENDING", "Add the construction stages"));
        else {
            double sum = tr.stream().mapToDouble(Tranche::amount).sum();
            List<String> why = new ArrayList<>();
            if (Math.abs(sum - loan) > 1) why.add("stages add up to " + rupees(sum) + ", the loan is " + rupees(loan));
            double prev = -1;
            for (Tranche t : tr) {
                double p = t.stagePct() == null ? 0 : t.stagePct();
                if (p < prev) why.add("stage " + t.seq() + " needs less progress than the one before");
                prev = p;
                double cum = tr.stream().filter(x -> x.seq() <= t.seq()).mapToDouble(Tranche::amount).sum();
                if (t.seq() > 1 && loan > 0 && cum / loan > (p / 100) + 0.20 + 1e-9) why.add("stage " + t.seq() + " releases " + pct(cum / loan) + " of the loan at " + fmt(p) + "% progress");
            }
            if (tr.get(tr.size() - 1).stagePct() == null || tr.get(tr.size() - 1).stagePct() < 100) why.add("the last stage must be at 100% (completion)");
            cs.add(why.isEmpty() ? c("HS-05", "Stage-wise release plan", "PASS", tr.size() + " stages adding up to " + rupees(sum) + ", released with construction progress")
                    : c("HS-05", "Stage-wise release plan", "FAIL", String.join("; ", why)));
        }
        String planNo = str(d.get("planNo"));
        cs.add(planNo == null ? c("HS-06", "Approved building plan", "REFER", "No building permission / approved plan number recorded")
                : c("HS-06", "Approved building plan", "PASS", "Plan " + planNo));
        Map<String, Object> est = docs.get("CONSTRUCTION_ESTIMATE");
        if ("CONSTRUCTION".equals(purpose) || "EXTENSION".equals(purpose)) {
            if (est == null) cs.add(c("HS-07", "Engineer's estimate matches", "PENDING", "Upload the engineer's construction estimate"));
            else {
                List<String> diff = new ArrayList<>();
                double t = num(est.get("total"));
                if (t > 0 && Math.abs(t - estimate) > 1) diff.add("estimate reads " + rupees(t));
                double ca = num(est.get("carpetAreaSqft"));
                if (ca > 0 && carpet > 0 && Math.abs(ca - carpet) > 1) diff.add("carpet area reads " + fmt(ca) + " sq ft");
                String pn = str(est.get("planNo"));
                if (pn != null && planNo != null && !norm(pn).equals(norm(planNo))) diff.add("plan " + pn);
                cs.add(diff.isEmpty() ? c("HS-07", "Engineer's estimate matches", "PASS", "Estimate by " + Objects.toString(est.get("engineer"), "the engineer") + " matches the plan")
                        : c("HS-07", "Engineer's estimate matches", "REFER", "The estimate reads differently: " + String.join(", ", diff)));
            }
            cond.add("Each stage released after a technical inspection certifies the construction progress");
            cond.add("Pre-EMI interest on the amount released until the last stage; full EMI from then");
        }
        cond.add("Mortgage of the property (equitable mortgage) and property insurance with the lender noted");
    }

    // ---------------------------------------------------------------- education

    static void education(Map<String, Object> d, Map<String, Map<String, Object>> docs, List<Tranche> tr, Party inst, String applicant, double loan,
                          int tenure, LocalDate today, List<Check> cs, List<String> cond) {
        String student = str(d.get("studentName"));
        if (inst == null) cs.add(c("ED-01", "Institution approved", "PENDING", "Choose the institution"));
        else if (!"ACTIVE".equals(inst.status())) cs.add(c("ED-01", "Institution approved", "FAIL", inst.name() + " is " + inst.status().toLowerCase()));
        else if (!inst.empanelled()) cs.add(c("ED-01", "Institution approved", "REFER", inst.name() + " is not on the approved list"));
        else if (!inst.hasAccount()) cs.add(c("ED-01", "Institution approved", "REFER", inst.name() + "'s fee account is not on record"));
        else cs.add(c("ED-01", "Institution approved", "PASS", inst.name() + " (approved, fees paid to its account)"));

        Map<String, Object> adm = docs.get("ADMISSION_LETTER");
        if (student == null) cs.add(c("ED-02", "Admission letter for the student", "PENDING", "Enter the student's name"));
        else if (adm == null) cs.add(c("ED-02", "Admission letter for the student", "PENDING", "Upload the admission letter"));
        else {
            List<String> diff = new ArrayList<>();
            if (adm.get("student") != null && !sameName(adm.get("student").toString(), student)) diff.add("student " + adm.get("student"));
            if (adm.get("institution") != null && inst != null && !sameName(adm.get("institution").toString(), inst.name())) diff.add("institution " + adm.get("institution"));
            cs.add(diff.isEmpty() ? c("ED-02", "Admission letter for the student", "PASS", student + " admitted to " + Objects.toString(adm.get("course"), "the course"))
                    : c("ED-02", "Admission letter for the student", "REFER", "The admission letter reads " + String.join(", ", diff)));
        }

        Map<String, Object> fee = docs.get("FEE_LETTER");
        List<Map<String, Object>> terms = terms(fee);
        double fees = fee == null ? 0 : num(fee.get("total")) > 0 ? num(fee.get("total")) : terms.stream().mapToDouble(t -> num(t.get("amount"))).sum();
        if (fee == null) cs.add(c("ED-03", "Loan within the fees", "PENDING", "Upload the institution's fee letter"));
        else cs.add(loan <= fees + 1 ? c("ED-03", "Loan within the fees", "PASS", "Loan " + rupees(loan) + " within fees of " + rupees(fees))
                : c("ED-03", "Loan within the fees", "REFER", "Loan " + rupees(loan) + " is above the fees of " + rupees(fees)));

        if (tr.isEmpty()) cs.add(c("ED-04", "Term-wise fee releases", "PENDING", "Add the term-wise releases"));
        else {
            List<String> why = new ArrayList<>();
            double sum = tr.stream().mapToDouble(Tranche::amount).sum();
            if (Math.abs(sum - loan) > 1) why.add("releases add up to " + rupees(sum) + ", the loan is " + rupees(loan));
            for (Tranche t : tr) {
                if (!"INSTITUTION".equals(t.payeeKind())) why.add("release " + t.seq() + " is not paid to the institution");
                Optional<Map<String, Object>> term = terms.stream().filter(x -> num(x.get("term")) == t.seq()).findFirst();
                if (term.isPresent() && t.amount() > num(term.get().get("amount")) + 1) why.add("release " + t.seq() + " " + rupees(t.amount()) + " is above term " + t.seq() + "'s fee " + rupees(num(term.get().get("amount"))));
            }
            cs.add(why.isEmpty() ? c("ED-04", "Term-wise fee releases", "PASS", tr.size() + " releases to the institution, each within that term's fee")
                    : c("ED-04", "Term-wise fee releases", "FAIL", String.join("; ", why)));
        }

        LocalDate start = date(d.get("courseStart"));
        int dur = (int) num(d.get("durationMonths"));
        if (start == null || dur <= 0) cs.add(c("ED-05", "Course within the loan tenure", "PENDING", "Enter the course start and duration"));
        else {
            LocalDate end = start.plusMonths(dur), loanEnd = today.plusMonths(tenure);
            cs.add(!end.isAfter(loanEnd.plusMonths(36)) ? c("ED-05", "Course within the loan tenure", "PASS", "Course " + start + " to " + end + "; loan to " + loanEnd)
                    : c("ED-05", "Course within the loan tenure", "REFER", "Course ends " + end + ", long after the loan ends " + loanEnd));
        }
        String rel = str(d.get("relationship"));
        cs.add(rel == null ? c("ED-06", "Borrower's relationship to the student", "PENDING", "Record how the borrower is related to the student")
                : c("ED-06", "Borrower's relationship to the student", "PASS", applicant + " is the student's " + label(rel).toLowerCase()));
        cond.add("Fees paid directly to the institution, term by term, against its fee demand");
        cond.add("Borrower informs the lender if the student leaves the course; unused fees refunded to the loan");
    }

    // ---------------------------------------------------------------- farm & allied

    static void agri(Map<String, Object> d, Map<String, Map<String, Object>> docs, String applicant, double loan, List<Check> cs, List<String> cond) {
        String activity = str(d.get("activity"));
        double acres = num(d.get("landAreaAcres")), scale = num(d.get("scalePerUnit")), units = num(d.get("units")), own = num(d.get("ownContribution"));
        Map<String, Object> land = docs.get("LAND_RECORD_712");
        if (land == null) cs.add(c("AG-01", "Land record (7/12) in the borrower's name", "PENDING", "Upload the 7/12 extract"));
        else if (land.get("owner") != null && !sameName(land.get("owner").toString(), applicant))
            cs.add(c("AG-01", "Land record (7/12) in the borrower's name", "REFER", "The 7/12 shows " + land.get("owner") + " as occupant (lease or family land needs a consent)"));
        else cs.add(c("AG-01", "Land record (7/12) in the borrower's name", "PASS", Objects.toString(land.get("owner"), applicant) + " · Gat/Survey " + Objects.toString(land.get("surveyNo"), "—")
                + " · " + Objects.toString(land.get("village"), "")));

        boolean crop = activity == null || Set.of("CROP", "HORTICULTURE").contains(activity);
        double basis = crop ? acres : units;
        if (activity == null || scale <= 0 || basis <= 0) cs.add(c("AG-02", "Within the scale of finance", "PENDING", "Enter the activity, the area or units and the scale of finance"));
        else {
            double max = Math.floor(scale * basis);
            cs.add(loan <= max + 1 ? c("AG-02", "Within the scale of finance", "PASS", rupees(scale) + " per " + (crop ? "acre" : "unit") + " × " + fmt(basis) + " = " + rupees(max) + " (loan " + rupees(loan) + ")")
                    : c("AG-02", "Within the scale of finance", "REFER", "Loan " + rupees(loan) + " is above " + rupees(max) + " (" + rupees(scale) + " × " + fmt(basis) + ")"));
        }
        double project = loan + own;
        if (loan > 200_000) cs.add(own >= 0.10 * project - 1 ? c("AG-03", "Margin above ₹2 lakh", "PASS", "Own contribution " + rupees(own) + " (" + pct(project > 0 ? own / project : 0) + " of " + rupees(project) + ")")
                : c("AG-03", "Margin above ₹2 lakh", "REFER", "Own contribution " + rupees(own) + " is below 10% of " + rupees(project)));
        else cs.add(c("AG-03", "Margin above ₹2 lakh", "PASS", "No margin needed up to ₹2 lakh"));

        String months = str(d.get("harvestMonths"));
        if (crop) cs.add(months == null ? c("AG-04", "Repayment after harvest", "PENDING", "Enter the harvest months (e.g. NOV, MAY)")
                : c("AG-04", "Repayment after harvest", "PASS", "Half-yearly instalments in " + months + ", after the harvests"));
        else cs.add(c("AG-04", "Repayment after harvest", "PASS", label(activity) + ": income through the year"));

        String ins = str(d.get("insuranceRef"));
        Map<String, Object> insDoc = docs.get("INSURANCE");
        cs.add(ins == null && insDoc == null ? c("AG-05", crop ? "Crop insurance" : "Livestock / asset insurance", "REFER", "No insurance recorded")
                : c("AG-05", crop ? "Crop insurance" : "Livestock / asset insurance", "PASS", insDoc != null ? Objects.toString(insDoc.get("insurer"), "Insurer") + " " + Objects.toString(insDoc.get("policyNo"), "") : ins));

        if (land != null && land.get("areaAcres") != null && acres > 0) {
            double a = num(land.get("areaAcres"));
            cs.add(Math.abs(a - acres) <= 0.25 || acres <= a ? c("AG-06", "Area matches the 7/12", "PASS", fmt(acres) + " acres declared, " + fmt(a) + " on the 7/12")
                    : c("AG-06", "Area matches the 7/12", "REFER", fmt(acres) + " acres declared but the 7/12 shows " + fmt(a)));
        } else cs.add(c("AG-06", "Area matches the 7/12", crop ? "PENDING" : "PASS", crop ? "Area from the 7/12 needed" : "Not area-based"));
        cond.add(crop ? "Instalments after each harvest; the field officer re-visits before each season" : "Monthly repayment from the activity's income");
        cond.add("Insurance kept in force with the lender noted");
    }

    // ---------------------------------------------------------------- rental income (lease rental discounting)

    /** Share of the net rent (after 10% TDS) that the EMI may take. */
    public static final double RENT_COVER = 0.80;

    static void rental(Map<String, Object> d, Map<String, Map<String, Object>> docs, String applicant, double loan, int tenure, LocalDate today,
                       List<Check> cs, List<String> cond) {
        Map<String, Object> lease = docs.get("LEASE_DEED");
        String tenant = str(d.get("tenantName"));
        double rent = num(d.get("monthlyRent")), rate = num(d.get("_ratePa"));
        if (lease == null) cs.add(c("LR-01", "Lease deed: borrower is the landlord", "PENDING", "Upload the registered lease deed"));
        else {
            List<String> diff = new ArrayList<>();
            if (lease.get("lessor") != null && !sameName(lease.get("lessor").toString(), applicant)) diff.add("landlord " + lease.get("lessor"));
            if (lease.get("lessee") != null && tenant != null && !sameName(lease.get("lessee").toString(), tenant)) diff.add("tenant " + lease.get("lessee"));
            if (num(lease.get("monthlyRent")) > 0 && rent > 0 && Math.abs(num(lease.get("monthlyRent")) - rent) > 1) diff.add("rent " + rupees(num(lease.get("monthlyRent"))));
            cs.add(diff.isEmpty() ? c("LR-01", "Lease deed: borrower is the landlord", "PASS", applicant + " leases to " + (tenant != null ? tenant : Objects.toString(lease.get("lessee"), "the tenant")) + " at " + rupees(rent) + " a month")
                    : c("LR-01", "Lease deed: borrower is the landlord", "REFER", "The lease reads " + String.join(", ", diff)));
        }
        if (rent <= 0 || rate <= 0) cs.add(c("LR-02", "EMI within 80% of the net rent", "PENDING", "Enter the monthly rent"));
        else {
            double r = rate / 1200, emi = loan * r * Math.pow(1 + r, tenure) / (Math.pow(1 + r, tenure) - 1), net = rent * 0.90;
            cs.add(emi <= RENT_COVER * net + 1 ? c("LR-02", "EMI within 80% of the net rent", "PASS", "EMI " + rupees(emi) + " is " + pct(emi / net) + " of the rent after TDS " + rupees(net) + " (at " + fmt(rate) + "%)")
                    : c("LR-02", "EMI within 80% of the net rent", "REFER", "EMI " + rupees(emi) + " is " + pct(emi / net) + " of the rent after TDS " + rupees(net) + "; the maximum loan is lower"));
        }
        LocalDate end = date(d.get("leaseEnd"));
        if (end == null) cs.add(c("LR-03", "Loan ends before the lease", "PENDING", "Enter the lease end date"));
        else cs.add(!today.plusMonths(tenure).isAfter(end) ? c("LR-03", "Loan ends before the lease", "PASS", "Loan ends " + today.plusMonths(tenure) + ", lease runs to " + end)
                : c("LR-03", "Loan ends before the lease", "REFER", "Loan ends " + today.plusMonths(tenure) + ", after the lease ends " + end));
        LocalDate start = date(d.get("leaseStart"));
        int lockIn = (int) num(d.get("lockInMonths"));
        if (start == null || lockIn <= 0) cs.add(c("LR-04", "Lock-in protects the loan", "REFER", "No lock-in recorded: the tenant may leave at short notice"));
        else {
            long left = Math.max(0, java.time.temporal.ChronoUnit.MONTHS.between(today, start.plusMonths(lockIn)));
            long need = Math.min(36, tenure / 2);
            cs.add(left >= need ? c("LR-04", "Lock-in protects the loan", "PASS", left + " months of lock-in left (needs " + need + ")")
                    : c("LR-04", "Lock-in protects the loan", "REFER", "Only " + left + " months of lock-in left (needs " + need + ")"));
        }
        String escrow = str(d.get("escrowAccount"));
        cs.add(escrow == null ? c("LR-05", "Rent paid into an escrow account", "PENDING", "Record the escrow account the tenant will pay into")
                : c("LR-05", "Rent paid into an escrow account", "PASS", "Escrow " + escrow + "; the tenant pays rent there and the EMI is swept first"));
        String type = str(d.get("tenantType"));
        cs.add(type == null ? c("LR-06", "Tenant quality", "PENDING", "Record the kind of tenant")
                : Set.of("BANK", "CORPORATE", "GOVERNMENT").contains(type) ? c("LR-06", "Tenant quality", "PASS", label(type) + " tenant")
                : c("LR-06", "Tenant quality", "REFER", label(type) + " tenant: higher risk of vacancy"));
        cond.add("Tripartite agreement: the tenant pays rent into the escrow account until the loan is repaid");
        cond.add("Assignment of the lease rentals and mortgage of the property in the lender's favour");
        cond.add("Any change in the lease or tenant to be informed within 15 days");
    }

    // ---------------------------------------------------------------- tranche release

    public record Release(String status, List<Check> checks) {}

    /**
     * certifiedPct: construction progress certified for housing; demandAmount: the term's fee demand for education (null = none uploaded).
     */
    public static Release release(String kind, Tranche t, boolean earlierPaid, Double certifiedPct, Double demandAmount, double releasedBefore, double sanctioned, Party payee) {
        List<Check> cs = new ArrayList<>();
        cs.add(earlierPaid ? c("TR-01", "Earlier releases made", "PASS", "All earlier releases paid") : c("TR-01", "Earlier releases made", "PENDING", "Release the earlier stages first"));
        if ("HOUSING".equals(kind)) {
            double need = t.stagePct() == null ? 0 : t.stagePct();
            if (need <= 0) cs.add(c("TR-02", "Condition for release", "PASS", "Released at disbursement"));
            else if (certifiedPct == null) cs.add(c("TR-02", "Condition for release", "PENDING", "Technical inspection to certify at least " + fmt(need) + "% progress"));
            else cs.add(certifiedPct + 1e-9 >= need ? c("TR-02", "Condition for release", "PASS", fmt(certifiedPct) + "% progress certified (needs " + fmt(need) + "%)")
                    : c("TR-02", "Condition for release", "FAIL", "Only " + fmt(certifiedPct) + "% certified; " + fmt(need) + "% needed"));
        } else if ("EDUCATION".equals(kind)) {
            if (t.seq() == 1) cs.add(c("TR-02", "Condition for release", "PASS", "First term fee at disbursement"));
            else if (demandAmount == null) cs.add(c("TR-02", "Condition for release", "PENDING", "Upload the institution's fee demand for term " + t.seq()));
            else cs.add(demandAmount + 1 >= t.amount() ? c("TR-02", "Condition for release", "PASS", "Fee demand of " + rupees(demandAmount) + " for term " + t.seq())
                    : c("TR-02", "Condition for release", "FAIL", "Fee demand " + rupees(demandAmount) + " is less than the release " + rupees(t.amount())));
        }
        cs.add(releasedBefore + t.amount() <= sanctioned + 1 ? c("TR-03", "Within the sanctioned amount", "PASS", rupees(releasedBefore + t.amount()) + " of " + rupees(sanctioned) + " released after this")
                : c("TR-03", "Within the sanctioned amount", "FAIL", "Would release " + rupees(releasedBefore + t.amount()) + " against " + rupees(sanctioned)));
        if (!"BORROWER".equals(t.payeeKind())) {
            if (payee == null) cs.add(c("TR-04", "Payee approved", "FAIL", "Payee not set"));
            else if (!"ACTIVE".equals(payee.status())) cs.add(c("TR-04", "Payee approved", "FAIL", payee.name() + " is " + payee.status().toLowerCase()));
            else if (!payee.hasAccount()) cs.add(c("TR-04", "Payee approved", "FAIL", payee.name() + "'s account is not on record"));
            else cs.add(c("TR-04", "Payee approved", "PASS", "Paid to " + payee.name()));
        } else cs.add(c("TR-04", "Payee approved", "PASS", "Paid to the borrower's verified account"));
        String status = cs.stream().anyMatch(x -> "FAIL".equals(x.result())) ? "FAIL" : cs.stream().anyMatch(x -> "PENDING".equals(x.result())) ? "PENDING" : "PASS";
        return new Release(status, cs);
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> terms(Map<String, Object> fee) {
        if (fee == null || !(fee.get("terms") instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /** Same person or institution: every word of the shorter name appears in the longer (case, dots and titles ignored). */
    public static boolean sameName(String a, String b) {
        List<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        List<String> s = x.size() <= y.size() ? x : y, l = x.size() <= y.size() ? y : x;
        return l.containsAll(s);
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").split("\\s+"))
            if (!w.isBlank() && !Set.of("mr", "mrs", "ms", "dr", "shri", "smt", "kum", "er").contains(w)) out.add(w);
        return out;
    }

    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try { return Double.parseDouble(o.toString().replace(",", "").trim()); } catch (NumberFormatException e) { return 0; }
    }
    static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString().trim(); }
    static LocalDate date(Object o) {
        String s = str(o);
        if (s == null) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); } catch (Exception e) { return null; }
    }
    static String norm(String s) { return s == null ? "" : s.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT); }
    static String label(String s) { return s == null ? "" : s.substring(0, 1) + s.substring(1).toLowerCase(Locale.ROOT).replace('_', ' '); }
    static String pct(double v) { return Math.round(v * 1000) / 10.0 + "%"; }
    static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0); }

    public static String rupees(double v) {
        long r = Math.round(v);
        String s = Long.toString(Math.abs(r));
        if (s.length() > 3) {
            String head = s.substring(0, s.length() - 3), tail = s.substring(s.length() - 3);
            StringBuilder b = new StringBuilder();
            while (head.length() > 2) { b.insert(0, "," + head.substring(head.length() - 2)); head = head.substring(0, head.length() - 2); }
            s = head + b + "," + tail;
        }
        return (r < 0 ? "-₹" : "₹") + s;
    }
}
