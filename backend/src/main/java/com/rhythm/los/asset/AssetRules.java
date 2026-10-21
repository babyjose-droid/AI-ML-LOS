package com.rhythm.los.asset;

import java.time.LocalDate;
import java.util.*;

/**
 * Asset-finance rules AF-01 to AF-13: dealer, quotation and invoice, duplicates, serial or chassis number, down payment,
 * loan-to-cost (LTV), insurance, fleet and dealer exposure, asset age and the AI reading of the invoice. Pure: the service
 * gathers the facts. Each check is PASS, REFER (credit can approve an exception with a reason), FAIL (cannot be overridden)
 * or PENDING (needed before disbursement, not before sanction).
 */
public final class AssetRules {
    private AssetRules() {}

    public record Policy(String assetClass, String label, double maxLtv, double minDownPct, double depRate, int maxAgeYears,
                         boolean insuranceRequired, boolean registrationRequired, String serialLabel, double quoteVariance,
                         Double fleetLimit, List<String> postDocs, int postDays) {}

    public record DealerFacts(Long id, String name, String status, boolean empanelled, String categories, String gstin,
                              Double maxExposure, double currentExposure) {}

    public record Facts(String assetClass, Integer year, String serialNo, String registrationNo, int units,
                        Double quoteAmount, boolean hasQuote, String invoiceNo, Double basePrice, Double gst, Double other, Double invoiceTotal,
                        String invoiceGstin, boolean hasInvoice, Double downPayment, boolean downPaymentVerified,
                        Double insuredValue, LocalDate insuranceExpiry, boolean insuranceHypothecated, Double subsidy,
                        double loanAmount, int tenureMonths, double ratePa, double otherAssetExposure,
                        String duplicateInvoiceOn, String duplicateSerialOn, Map<String, Object> invoiceRead) {}

    public record Check(String id, String label, String result, String reason, String ruling) {
        public boolean blocksSanction() { return ("REFER".equals(result) && ruling == null) || "FAIL".equals(result); }
    }

    public record Result(String status, double assetCost, double netCost, double maxLoan, double ltv, double downPct,
                         List<Check> checks, List<String> beforeDisbursement, List<Map<String, Object>> cover, List<String> conditions) {}

    static final Map<String, String> LABEL = new LinkedHashMap<>();
    static {
        LABEL.put("AF-01", "Dealer or supplier empanelled, active and allowed for this asset");
        LABEL.put("AF-02", "Quotation or proforma invoice on file");
        LABEL.put("AF-03", "Invoice adds up (price + GST + other charges = total)");
        LABEL.put("AF-04", "GSTIN on the invoice is the dealer's");
        LABEL.put("AF-05", "Invoice and serial number not financed before");
        LABEL.put("AF-06", "Serial or chassis number recorded and valid");
        LABEL.put("AF-07", "Down payment at or above the minimum, paid and verified");
        LABEL.put("AF-08", "Loan within the maximum loan-to-cost (LTV)");
        LABEL.put("AF-09", "Final invoice within the variance allowed from the quotation");
        LABEL.put("AF-10", "Insurance for at least the loan, valid a year, lender noted as hypothecatee");
        LABEL.put("AF-11", "Borrower (fleet) and dealer exposure within limits");
        LABEL.put("AF-12", "Asset is new (manufactured within the allowed age)");
        LABEL.put("AF-13", "Invoice read by AI matches the details entered");
    }

    /** Never overridable: a suspended or blacklisted dealer, a GSTIN mismatch, an asset already financed. */
    public static final Set<String> HARD = Set.of("AF-01", "AF-04", "AF-05");

    public static Result evaluate(Facts f, Policy p, DealerFacts d, Map<String, Map<String, Object>> rulings, LocalDate today) {
        List<Check> cs = new ArrayList<>();
        Map<String, Map<String, Object>> rule = rulings == null ? Map.of() : rulings;
        double cost = pos(f.invoiceTotal()) ? f.invoiceTotal() : pos(f.quoteAmount()) ? f.quoteAmount() : 0;
        double subsidy = pos(f.subsidy()) ? f.subsidy() : 0;
        double net = Math.max(0, cost - subsidy);
        double maxLoan = Math.floor(net * p.maxLtv());
        double ltv = net > 0 ? f.loanAmount() / net : 0;
        double down = pos(f.downPayment()) ? f.downPayment() : 0;
        double downPct = cost > 0 ? down / cost : 0;

        // AF-01 dealer
        if (d == null) cs.add(c("AF-01", "PENDING", "No dealer or supplier chosen yet"));
        else if (!"ACTIVE".equals(d.status())) cs.add(c("AF-01", "FAIL", d.name() + " is " + d.status().toLowerCase()));
        else if (!List.of(d.categories().split(",")).contains(f.assetClass())) cs.add(c("AF-01", "FAIL", d.name() + " is not allowed to sell " + p.label().toLowerCase()));
        else if (!d.empanelled()) cs.add(c("AF-01", "REFER", d.name() + " is not empanelled"));
        else cs.add(c("AF-01", "PASS", d.name() + " is empanelled and active"));

        // AF-02 quotation
        cs.add(f.hasQuote() || pos(f.quoteAmount()) ? c("AF-02", "PASS", "Quotation of " + rupees(f.quoteAmount()) + " on file")
                : f.hasInvoice() ? c("AF-02", "PASS", "Final invoice on file") : c("AF-02", "REFER", "No quotation or invoice yet"));

        // AF-03 invoice arithmetic
        if (!f.hasInvoice() && f.invoiceTotal() == null) cs.add(c("AF-03", "PENDING", "Final tax invoice needed before disbursement"));
        else if (f.basePrice() == null || f.invoiceTotal() == null) cs.add(c("AF-03", "REFER", "Enter the price, GST and total from the invoice"));
        else {
            double sum = f.basePrice() + n(f.gst()) + n(f.other());
            cs.add(Math.abs(sum - f.invoiceTotal()) <= 1 ? c("AF-03", "PASS", rupees(f.basePrice()) + " + GST " + rupees(n(f.gst())) + (n(f.other()) > 0 ? " + " + rupees(f.other()) : "") + " = " + rupees(f.invoiceTotal()))
                    : c("AF-03", "REFER", "Price + GST + charges = " + rupees(sum) + ", but the invoice total is " + rupees(f.invoiceTotal())));
        }

        // AF-04 GSTIN
        if (f.invoiceGstin() == null || f.invoiceGstin().isBlank()) cs.add(c("AF-04", f.hasInvoice() ? "REFER" : "PENDING", f.hasInvoice() ? "GSTIN not found on the invoice" : "Checked on the final invoice"));
        else if (d == null || d.gstin() == null) cs.add(c("AF-04", "REFER", "Dealer GSTIN not on record"));
        else cs.add(norm(f.invoiceGstin()).equals(norm(d.gstin())) ? c("AF-04", "PASS", "GSTIN " + f.invoiceGstin() + " is " + d.name() + "'s")
                    : c("AF-04", "FAIL", "Invoice GSTIN " + f.invoiceGstin() + " is not " + d.name() + "'s (" + d.gstin() + ")"));

        // AF-05 duplicates
        if (f.duplicateInvoiceOn() != null) cs.add(c("AF-05", "FAIL", "Invoice " + f.invoiceNo() + " from this dealer is already on " + f.duplicateInvoiceOn()));
        else if (f.duplicateSerialOn() != null) cs.add(c("AF-05", "FAIL", p.serialLabel() + " " + f.serialNo() + " is already financed on " + f.duplicateSerialOn()));
        else cs.add(c("AF-05", "PASS", "Not seen on any other loan"));

        // AF-06 serial / chassis
        String serial = f.serialNo() == null ? "" : f.serialNo().trim().toUpperCase(Locale.ROOT);
        if (serial.isEmpty()) cs.add(c("AF-06", "PENDING", p.serialLabel() + " needed before disbursement"));
        else if (p.registrationRequired() && !serial.matches("[A-HJ-NPR-Z0-9]{17}")) cs.add(c("AF-06", "REFER", p.serialLabel() + " " + serial + " is not a 17-character VIN"));
        else if (serial.length() < 5) cs.add(c("AF-06", "REFER", p.serialLabel() + " " + serial + " looks too short"));
        else cs.add(c("AF-06", "PASS", p.serialLabel() + " " + serial));

        // AF-07 down payment
        double minDown = Math.ceil(cost * p.minDownPct());
        if (cost <= 0) cs.add(c("AF-07", "PENDING", "Asset cost not known yet"));
        else if (down + 0.5 < minDown) cs.add(c("AF-07", "REFER", "Down payment " + rupees(down) + " is below the minimum " + rupees(minDown) + " (" + pct(p.minDownPct()) + " of " + rupees(cost) + ")"));
        else if (down + f.loanAmount() + subsidy + 1 < cost) cs.add(c("AF-07", "REFER", "Down payment + loan" + (subsidy > 0 ? " + subsidy" : "") + " = " + rupees(down + f.loanAmount() + subsidy) + ", short of the cost " + rupees(cost)));
        else if (!f.downPaymentVerified()) cs.add(c("AF-07", "PENDING", "Down payment " + rupees(down) + " (" + pct(downPct) + ") to be verified with the dealer's receipt before disbursement"));
        else cs.add(c("AF-07", "PASS", "Down payment " + rupees(down) + " (" + pct(downPct) + ") received by the dealer and verified"));

        // AF-08 LTV
        if (net <= 0) cs.add(c("AF-08", "PENDING", "Asset cost not known yet"));
        else if (f.loanAmount() > maxLoan + 0.5) cs.add(c("AF-08", "REFER", "Loan " + rupees(f.loanAmount()) + " is " + pct(ltv) + " of " + rupees(net) + "; the maximum is " + pct(p.maxLtv()) + " (" + rupees(maxLoan) + ")"));
        else cs.add(c("AF-08", "PASS", "Loan " + rupees(f.loanAmount()) + " is " + pct(ltv) + " of " + rupees(net) + (subsidy > 0 ? " (cost net of subsidy)" : "") + "; maximum " + pct(p.maxLtv())));

        // AF-09 quote vs invoice
        if (pos(f.quoteAmount()) && pos(f.invoiceTotal())) {
            double v = (f.invoiceTotal() - f.quoteAmount()) / f.quoteAmount();
            cs.add(Math.abs(v) <= p.quoteVariance() + 1e-9 ? c("AF-09", "PASS", "Invoice is " + signedPct(v) + " from the quotation")
                    : c("AF-09", "REFER", "Invoice " + rupees(f.invoiceTotal()) + " is " + signedPct(v) + " from the quotation " + rupees(f.quoteAmount()) + " (limit " + pct(p.quoteVariance()) + ")"));
        } else cs.add(c("AF-09", pos(f.invoiceTotal()) ? "PASS" : "PENDING", pos(f.invoiceTotal()) ? "No separate quotation to compare" : "Compared when the final invoice comes"));

        // AF-10 insurance
        if (!p.insuranceRequired()) cs.add(c("AF-10", "PASS", "Not required for " + p.label().toLowerCase()));
        else if (!pos(f.insuredValue())) cs.add(c("AF-10", "PENDING", "Insurance needed before disbursement"));
        else {
            List<String> why = new ArrayList<>();
            if (f.insuredValue() + 0.5 < f.loanAmount()) why.add("insured value " + rupees(f.insuredValue()) + " is below the loan");
            if (f.insuranceExpiry() == null || f.insuranceExpiry().isBefore(today.plusDays(330))) why.add("valid only to " + (f.insuranceExpiry() == null ? "—" : f.insuranceExpiry()));
            if (!f.insuranceHypothecated()) why.add("lender not noted as hypothecatee");
            cs.add(why.isEmpty() ? c("AF-10", "PASS", "Insured for " + rupees(f.insuredValue()) + " to " + f.insuranceExpiry() + ", lender noted")
                    : c("AF-10", "REFER", "Insurance: " + String.join("; ", why)));
        }

        // AF-11 exposure
        List<String> ex = new ArrayList<>();
        if (p.fleetLimit() != null && f.otherAssetExposure() + f.loanAmount() > p.fleetLimit())
            ex.add("borrower's asset loans would be " + rupees(f.otherAssetExposure() + f.loanAmount()) + " (fleet limit " + rupees(p.fleetLimit()) + ")");
        if (d != null && d.maxExposure() != null && d.currentExposure() + f.loanAmount() > d.maxExposure())
            ex.add(d.name() + "'s loans would be " + rupees(d.currentExposure() + f.loanAmount()) + " (limit " + rupees(d.maxExposure()) + ")");
        cs.add(ex.isEmpty() ? c("AF-11", "PASS", "Borrower's other asset loans " + rupees(f.otherAssetExposure()) + (d != null ? "; dealer exposure " + rupees(d.currentExposure()) : ""))
                : c("AF-11", "REFER", String.join("; ", ex)));

        // AF-12 asset age
        if (f.year() == null) cs.add(c("AF-12", "PENDING", "Year of manufacture needed"));
        else cs.add(today.getYear() - f.year() <= p.maxAgeYears() ? c("AF-12", "PASS", "Manufactured " + f.year())
                    : c("AF-12", "REFER", "Manufactured " + f.year() + ": older than " + p.maxAgeYears() + " year(s) for a new-asset loan"));

        // AF-13 AI reading of the invoice
        Map<String, Object> read = f.invoiceRead() == null ? Map.of() : f.invoiceRead();
        if (read.isEmpty()) cs.add(c("AF-13", f.hasInvoice() ? "PASS" : "PENDING", f.hasInvoice() ? "Invoice details entered by staff (no AI reading)" : "Read when the invoice is uploaded"));
        else {
            List<String> diff = new ArrayList<>();
            if (read.get("invoiceNo") != null && f.invoiceNo() != null && !norm(read.get("invoiceNo").toString()).equals(norm(f.invoiceNo()))) diff.add("invoice no. " + read.get("invoiceNo"));
            if (read.get("total") instanceof Number t && f.invoiceTotal() != null && Math.abs(t.doubleValue() - f.invoiceTotal()) > 1) diff.add("total " + rupees(t.doubleValue()));
            if (read.get("gstin") != null && f.invoiceGstin() != null && !norm(read.get("gstin").toString()).equals(norm(f.invoiceGstin()))) diff.add("GSTIN " + read.get("gstin"));
            if (read.get("serialNo") != null && !serial.isEmpty() && !norm(read.get("serialNo").toString()).equals(norm(serial))) diff.add(p.serialLabel().toLowerCase() + " " + read.get("serialNo"));
            cs.add(diff.isEmpty() ? c("AF-13", "PASS", "Invoice number, total, GSTIN and serial read from the invoice match")
                    : c("AF-13", "REFER", "The invoice reads differently: " + String.join(", ", diff)));
        }

        // exceptions approved by credit (never for the hard rules)
        List<Check> ruled = new ArrayList<>();
        for (Check ch : cs) {
            Map<String, Object> r = rule.get(ch.id());
            if (r != null && "REFER".equals(ch.result()) && !HARD.contains(ch.id()))
                ruled.add(new Check(ch.id(), ch.label(), ch.result(), ch.reason(), "Exception approved by " + r.get("by") + ": " + r.get("note")));
            else ruled.add(ch);
        }
        String status = d == null || cost <= 0 ? "PENDING"
                : ruled.stream().anyMatch(x -> "FAIL".equals(x.result())) ? "FAIL"
                : ruled.stream().anyMatch(Check::blocksSanction) ? "REFER" : "PASS";
        List<String> before = ruled.stream().filter(x -> "PENDING".equals(x.result())).map(x -> x.id() + ": " + x.reason()).toList();

        List<String> conditions = new ArrayList<>();
        if (p.registrationRequired()) conditions.add("Hypothecation in the lender's favour endorsed on the RC within " + p.postDays() + " days, and on the insurance");
        else if (p.insuranceRequired()) conditions.add("Asset insured with the lender noted as hypothecatee, renewed every year");
        for (String doc : p.postDocs()) if (!doc.endsWith("_HP") && !doc.equals("RC_HP")) conditions.add(postDocLabel(doc) + " within " + p.postDays() + " days of disbursement");
        if (subsidy > 0) conditions.add("Subsidy of " + rupees(subsidy) + ", when credited, is applied as a part-prepayment");
        conditions.add("Disbursement is paid to the dealer or supplier, not to the borrower");

        return new Result(status, round(cost), round(net), maxLoan, round4(ltv), round4(downPct), ruled, before,
                cover(cost, p.depRate(), f.loanAmount(), f.ratePa(), f.tenureMonths()), conditions);
    }

    /** Security cover by year: depreciated asset value against the loan outstanding (written-down value). */
    static List<Map<String, Object>> cover(double cost, double dep, double loan, double ratePa, int months) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (cost <= 0 || loan <= 0 || months <= 0) return out;
        double r = ratePa / 1200.0;
        double emi = r == 0 ? loan / months : loan * r * Math.pow(1 + r, months) / (Math.pow(1 + r, months) - 1);
        for (int y = 0; y * 12 <= months; y++) {
            int k = y * 12;
            double outstanding = r == 0 ? loan - emi * k : loan * Math.pow(1 + r, k) - emi * (Math.pow(1 + r, k) - 1) / r;
            outstanding = Math.max(0, outstanding);
            double value = cost * Math.pow(1 - dep, y);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year", y);
            m.put("assetValue", round(value));
            m.put("outstanding", round(outstanding));
            m.put("cover", outstanding <= 0 ? null : round4(value / outstanding));
            out.add(m);
        }
        return out;
    }

    public static String postDocLabel(String code) {
        return switch (code) {
            case "RC_HP" -> "RC with the lender's hypothecation";
            case "INSURANCE_HP" -> "Insurance with the lender noted";
            case "INSTALLATION_CERT" -> "Installation and commissioning certificate";
            case "NET_METER" -> "Net-meter connection from the DISCOM";
            case "DELIVERY_NOTE" -> "Delivery note signed by the borrower";
            default -> code;
        };
    }

    static Check c(String id, String result, String reason) { return new Check(id, LABEL.get(id), result, reason, null); }
    static boolean pos(Double x) { return x != null && x > 0; }
    static double n(Double x) { return x == null ? 0 : x; }
    static String norm(String s) { return s == null ? "" : s.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT); }
    static double round(double x) { return Math.round(x * 100) / 100.0; }
    static double round4(double x) { return Math.round(x * 10000) / 10000.0; }
    static String pct(double x) { return Math.round(x * 1000) / 10.0 + "%"; }
    static String signedPct(double x) { return (x >= 0 ? "+" : "") + Math.round(x * 1000) / 10.0 + "%"; }

    static String rupees(Double x) {
        if (x == null) return "—";
        long v = Math.round(x);
        String s = Long.toString(Math.abs(v));
        if (s.length() > 3) {
            String last = s.substring(s.length() - 3), rest = s.substring(0, s.length() - 3);
            StringBuilder b = new StringBuilder();
            for (int i = rest.length(); i > 0; i -= 2) b.insert(0, (i - 2 > 0 ? "," : "") + rest.substring(Math.max(0, i - 2), i));
            s = b + "," + last;
        }
        return (v < 0 ? "-₹" : "₹") + s;
    }
}
