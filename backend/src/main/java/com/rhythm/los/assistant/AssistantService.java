package com.rhythm.los.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.decision.Delegation;
import com.rhythm.los.decision.engine.ReasonCodes;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.policy.PolicyFields;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.product.Product;
import com.rhythm.los.product.ProductRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Policy assistant. Answers questions about credit policy, products, delegation, reason codes and rules from the
 * live configuration, and explains a decision when an application number is given. Retrieval is keyword-based over
 * passages built from the live policy; the answer is extractive unless the AI service can write one from those
 * passages. Nothing personal leaves Rhythm: names, PAN, Aadhaar and mobile numbers are masked first.
 */
@Service
public class AssistantService {
    public record Passage(String id, String title, String text, String source) {}

    static final Pattern APP_NO = Pattern.compile("APP-\\d{6}", Pattern.CASE_INSENSITIVE);
    static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "what", "how", "why", "when", "who", "which", "can", "do", "does", "of", "for", "to", "in",
            "on", "and", "or", "it", "this", "that", "be", "we", "i", "my", "our", "with", "by", "at", "as", "was", "were", "has", "have", "give", "me", "tell", "about", "there");

    static final List<String[]> RULES = List.of(
            new String[]{"R01", "Age within the product's limits at application and at maturity; otherwise declined (RC-013)."},
            new String[]{"R02", "KYC identity match below the decline threshold is declined (RC-011)."},
            new String[]{"R03", "KYC identity match below the review threshold goes to KYC review or video KYC (RC-011)."},
            new String[]{"R04", "Fraud score at or above the decline threshold is declined (RC-012)."},
            new String[]{"R05", "Fraud score at or above the review threshold is referred to the fraud analyst (RC-012)."},
            new String[]{"R06", "Too few months of bank data makes the case incomplete (RC-013)."},
            new String[]{"R07", "Serious delinquency (days past due at or above the policy limit) in the last 12 months is declined (RC-005)."},
            new String[]{"R08", "Too many bureau enquiries in 6 months is referred (RC-006)."},
            new String[]{"R09", "Probability of default above the decline band is declined (RC-001)."},
            new String[]{"R10", "Probability of default above the auto-approve band is referred to a credit officer (RC-001)."},
            new String[]{"R11", "No EMI capacity after obligations is declined (RC-003)."},
            new String[]{"R12", "Requested EMI above capacity gets a counter-offer for a smaller amount."},
            new String[]{"R13", "Amount above the auto-approval limit is referred for human sanction (RC-014)."},
            new String[]{"R14", "Betting or gambling transactions in the bank statement are referred (RC-021)."},
            new String[]{"R15", "Salary slip employer not matching salary credits is referred (RC-010)."},
            new String[]{"R16", "Business or employment vintage below the product minimum is declined (RC-022)."},
            new String[]{"R17", "Monthly income below the product minimum is declined (RC-022)."},
            new String[]{"R18", "Applicant's PIN code not served by a branch offering the product is declined (RC-022)."},
            new String[]{"R19", "Money rotation above 30% of statement credits is referred; turnover is taken net of rotation (RC-023)."},
            new String[]{"R20", "Microfinance: household income above ₹3,00,000 a year is not eligible (RC-022)."},
            new String[]{"R21", "Microfinance: household repayments above 50% of household income get a counter-offer (RC-025)."},
            new String[]{"R22", "Network risk score of 60 or more (links to risky applications) is referred (RC-024)."});

    static final List<String[]> BOOK = List.of(
            new String[]{"Asset classification (SMA and NPA)", "SMA-0 is 1–30 days past due, SMA-1 31–60, SMA-2 61–90; above 90 days the loan is an NPA. An NPA is upgraded to standard only when all arrears are paid, not when days past due fall (RBI IRACP, 12 Nov 2021)."},
            new String[]{"Provisioning (RBI minimum)", "Standard assets 0.4%. Sub-standard (NPA up to 12 months) 10%. Doubtful: secured 20%, 30% and 50% by age (up to 1, 1–3, above 3 years doubtful); unsecured portion 100%. Provision held is the higher of ECL and this minimum; a shortfall of ECL is held as an impairment reserve."},
            new String[]{"Expected credit loss (Ind AS 109)", "Stage 1 (up to 30 days past due): 12-month PD × LGD × exposure. Stage 2 (31–90 days, or restructured): lifetime PD. Stage 3 (NPA): PD 1. LGD is the policy LGD for secured or unsecured loans."},
            new String[]{"Restructuring", "Proposed by a maker (operations, credit officer or manager) and approved by a different person (credit manager or CRO). Tenure extension up to 36 more months (120 at most), moratorium up to 6 months with interest capitalised, rate cut. A standard account is downgraded to NPA on restructuring and upgraded only after a year of on-time payment from the first instalment under the new terms (RBI prudential framework, 7 Jun 2019)."},
            new String[]{"Collections contact rules", "Calls and visits only between 8 am and 7 pm. Reminders by SMS or WhatsApp in the first week, calls up to 30 days, field visits from 31–60 days if a promise is broken or payment is unlikely, field visit from 61 days, recovery notice for NPAs. A promise to pay must be within 30 days and is marked kept or broken from the LMS feed."},
            new String[]{"Top-up eligibility", "Active and standard, at least 6 months on book and 6 instalments paid, never more than 30 days late, no bounce in 3 months, not restructured, behaviour PD under 10%, not on the early-warning list. Total exposure up to 1.5 times the original loan and within the exposure cap; minimum top-up ₹25,000; 0.5% lower rate for the best payers. Fresh consent and a fresh decision are needed."},
            new String[]{"Early warning", "Signals: days past due, first instalment missed, days past due rising, bounces in 3 months, behaviour PD of 15% or more, no payment for 45 days, restructured, fraud review. A score of 40 or more puts the loan on the watchlist with a suggested action."},
            new String[]{"Key Facts Statement", "Every borrower gets a KFS with the annual percentage rate, all fees, the repayment schedule and the cooling-off period, in their language, and accepts it by OTP before the loan is disbursed. The loan is paid only to the borrower's own bank account."});

    private final PolicyService policy;
    private final ProductRepository products;
    private final LoanApplicationRepository apps;
    private final DecisionRecordRepository decisions;
    private final DataScope scope;
    private final AssistantLlm llm;
    private final AuditService audit;

    public AssistantService(PolicyService policy, ProductRepository products, LoanApplicationRepository apps, DecisionRecordRepository decisions,
                            DataScope scope, AssistantLlm llm, AuditService audit) {
        this.policy = policy;
        this.products = products;
        this.apps = apps;
        this.decisions = decisions;
        this.scope = scope;
        this.llm = llm;
        this.audit = audit;
    }

    static String fmt(double v, String unit) {
        if ("share".equals(unit)) return Math.round(v * 1000) / 10.0 + "%";
        String n = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        return n + " " + unit;
    }

    public List<Passage> knowledge() {
        List<Passage> out = new ArrayList<>();
        PolicyParams p = policy.live();
        Map<String, Object> vals = Json.read(Json.write(p), new TypeReference<Map<String, Object>>() {});
        for (PolicyFields.Field f : PolicyFields.ALL) {
            Object v = vals.get(f.key());
            if (!(v instanceof Number n)) continue;
            out.add(new Passage("POL-" + f.key(), f.label(), f.label() + " is " + fmt(n.doubleValue(), f.unit()) + ". " + f.help() + ".", "Credit policy " + p.version() + " · " + f.group()));
        }
        for (Product pr : products.findAll()) {
            if (!"LIVE".equals(pr.getStatus())) continue;
            out.add(new Passage("PRD-" + pr.getCode(), pr.getName() + " (" + pr.getCode() + ")",
                    pr.getName() + " (" + pr.getCode() + ", " + pr.getSegment().toLowerCase() + ", " + (pr.isSecured() ? "secured" : "unsecured") + "): ₹" + pr.getMinAmount().toPlainString() + " to ₹"
                            + pr.getMaxAmount().toPlainString() + ", " + pr.getMinTenure() + "–" + pr.getMaxTenure() + " months, rate " + pr.getRateMin() + "–" + pr.getRateMax()
                            + "% a year, processing fee " + pr.getProcessingFeePct() + "%, age " + pr.getMinAge() + "–" + pr.getMaxAge() + ". Documents: " + pr.getRequiredDocs()
                            + ". Field visit: " + Objects.toString(pr.getFieldVisitRule(), "none").toLowerCase().replace('_', ' ') + (pr.getFieldVisitThreshold() == null ? "" : " above ₹" + pr.getFieldVisitThreshold().toPlainString())
                            + ". Video KYC: " + Objects.toString(pr.getVideoKycRule(), "none").toLowerCase().replace('_', ' ') + "."
                            + (pr.getAutoSanctionLimit() == null ? "" : " Auto-sanction up to ₹" + pr.getAutoSanctionLimit().toPlainString() + ".")
                            + (pr.getMinMonthlyIncome() == null ? "" : " Minimum monthly income ₹" + pr.getMinMonthlyIncome().toPlainString() + ".")
                            + (pr.getMinVintageYears() == null ? "" : " Minimum vintage " + pr.getMinVintageYears().toPlainString() + " years."),
                    "Product master v" + pr.getVersion()));
        }
        Delegation.Matrix m = Delegation.matrix();
        out.add(new Passage("DEL-matrix", "Delegation of sanction authority (who approves)",
                "Credit officer (L1) sanctions up to ₹" + Math.round(m.l1Max()) + " and risk band " + m.l1Band() + " or better with " + m.l1Deviations()
                        + " deviations. Credit manager (L2) up to ₹" + Math.round(m.l2Max()) + ", band " + m.l2Band() + ", " + m.l2Deviations()
                        + " deviation(s). Above that, or with more deviations, the CRO (L3) sanctions. Clean cases within the auto-approval limit are sanctioned automatically.",
                "Delegation matrix"));
        ReasonCodes.TEXT.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                out.add(new Passage("RC-" + e.getKey().substring(3), e.getKey() + ": " + e.getValue(), "Reason code " + e.getKey() + " means: " + e.getValue() + ".", "Reason codes")));
        for (String[] r : RULES) out.add(new Passage("RULE-" + r[0], "Rule " + r[0], "Rule " + r[0] + ": " + r[1], "Underwriting rules"));
        int i = 1;
        for (String[] b : BOOK) out.add(new Passage("BOOK-" + i++, b[0], b[1], "Portfolio and RBI norms"));
        return out;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9\\-]+")) {
            if (t.isBlank() || STOP.contains(t)) continue;
            out.add(t.length() > 4 && t.endsWith("s") ? t.substring(0, t.length() - 1) : t);
        }
        return out;
    }

    /** BM25-style ranking with a boost for words in the title. */
    static List<Passage> rank(String q, List<Passage> ps, int k) {
        List<String> qt = tokens(q);
        if (qt.isEmpty()) return List.of();
        Map<String, Integer> df = new HashMap<>();
        List<List<String>> docs = new ArrayList<>();
        for (Passage p : ps) {
            List<String> d = tokens(p.title() + " " + p.title() + " " + p.text() + " " + p.id());
            docs.add(d);
            new HashSet<>(d).forEach(t -> df.merge(t, 1, Integer::sum));
        }
        double avg = docs.stream().mapToInt(List::size).average().orElse(1);
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            List<String> d = docs.get(i);
            double s = 0;
            for (String t : new HashSet<>(qt)) {
                long tf = d.stream().filter(t::equals).count();
                if (tf == 0) continue;
                double idf = Math.log(1 + (ps.size() - df.get(t) + 0.5) / (df.get(t) + 0.5));
                s += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * d.size() / avg));
            }
            if (s > 0) scored.add(new double[]{s, i});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        return scored.stream().limit(k).map(x -> ps.get((int) x[1])).toList();
    }

    /** Removes names, PAN, Aadhaar and mobile numbers before anything is sent out. */
    String mask(String q, List<LoanApplication> known) {
        String s = q.replaceAll("(?i)\\b[A-Z]{5}[0-9]{4}[A-Z]\\b", "[PAN]")
                .replaceAll("(?<!\\d)\\d{4}\\s?\\d{4}\\s?\\d{4}(?!\\d)", "[AADHAAR]")
                .replaceAll("(?<!\\d)[6-9]\\d{9}(?!\\d)", "[MOBILE]");
        for (LoanApplication a : known) {
            if (a.getApplicantName() == null || a.getApplicantName().length() < 3) continue;
            s = s.replaceAll("(?i)" + Pattern.quote(a.getApplicantName()), "[name]");
            for (String part : a.getApplicantName().split("\\s+")) if (part.length() >= 4) s = s.replaceAll("(?i)\\b" + Pattern.quote(part) + "\\b", "[name]");
        }
        return s;
    }

    Passage explain(LoanApplication a, CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        if (vis != null && (a.getBranchId() == null || !vis.contains(a.getBranchId()))) throw ApiException.notFound("Application");
        DecisionRecord d = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).orElse(null);
        if (d == null) return new Passage(a.getAppNo(), "Decision for " + a.getAppNo(), a.getAppNo() + " has no credit decision yet (status " + a.getAppState().toLowerCase() + ").", "Decision record");
        StringBuilder b = new StringBuilder(a.getAppNo() + " (" + a.getProductCode() + "): decision " + d.getDecision().replace('_', ' ').toLowerCase()
                + ", PD " + (d.getPd() == null ? "n/a" : Math.round(d.getPd().doubleValue() * 1000) / 10.0 + "%") + ", risk band " + d.getRiskBand() + ".");
        if (d.getRecommendedAmount() != null) b.append(" Recommended ₹").append(d.getRecommendedAmount().toPlainString()).append(" at ").append(d.getRate()).append("%.");
        if (d.getDelegationLevel() != null) b.append(" Sanction authority ").append(d.getDelegationLevel()).append(".");
        try {
            List<Map<String, Object>> rules = Json.read(d.getRulesJson(), new TypeReference<List<Map<String, Object>>>() {});
            List<String> failed = rules.stream().filter(r -> Boolean.FALSE.equals(r.get("pass"))).map(r -> r.get("id") + " " + r.get("description") + " (→ " + r.get("action") + ")").toList();
            if (!failed.isEmpty()) b.append(" Rules not met: ").append(String.join("; ", failed)).append(".");
        } catch (RuntimeException ignored) { }
        if (d.getReasonCodes() != null && !d.getReasonCodes().isBlank()) {
            List<String> rc = new ArrayList<>();
            for (String c : d.getReasonCodes().split("[,;\\s\"\\[\\]]+")) if (ReasonCodes.TEXT.containsKey(c)) rc.add(c + " " + ReasonCodes.TEXT.get(c));
            if (!rc.isEmpty()) b.append(" Reasons: ").append(String.join("; ", rc)).append(".");
        }
        b.append(" Policy ").append(d.getPolicyVersion()).append(", model ").append(d.getModelVersion()).append(".");
        return new Passage(a.getAppNo(), "Decision for " + a.getAppNo(), b.toString(), "Decision record " + d.getId());
    }

    public Map<String, Object> ask(String question, CurrentUser u) {
        if (question == null || question.isBlank()) throw ApiException.unprocessable("EMPTY_QUESTION", "Ask a question");
        if (question.length() > 1000) throw ApiException.unprocessable("TOO_LONG", "Keep the question under 1,000 characters");
        List<LoanApplication> all = apps.findAll();
        String masked = mask(question, all);
        List<Passage> ps = new ArrayList<>();
        String appNo = null;
        Matcher m = APP_NO.matcher(question);
        if (m.find()) {
            String no = m.group().toUpperCase(Locale.ROOT);
            LoanApplication a = all.stream().filter(x -> no.equals(x.getAppNo())).findFirst().orElseThrow(() -> ApiException.notFound("Application " + no));
            ps.add(explain(a, u));
            appNo = no;
        }
        List<Passage> kb = knowledge();
        String query = masked + (ps.isEmpty() ? "" : " " + ps.get(0).text());
        for (Passage p : rank(query, kb, ps.isEmpty() ? 4 : 5)) if (!ps.contains(p)) ps.add(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", masked);
        if (appNo != null) out.put("application", appNo);
        if (ps.isEmpty()) {
            out.put("answer", "I could not find this in the credit policy, products, delegation, rules or reason codes. Try other words, or ask the credit team.");
            out.put("mode", "none");
            out.put("sources", List.of());
            return out;
        }
        List<Map<String, String>> send = ps.stream().map(p -> Map.of("id", p.id(), "title", p.title(), "text", p.text())).toList();
        Optional<String> gen = llm.answer(masked, send);
        out.put("answer", gen.orElseGet(() -> extractive(ps)));
        out.put("mode", gen.isPresent() ? "generative" : "extractive");
        out.put("sources", ps);
        audit.record(u, "assistant.asked", "ASSISTANT", appNo == null ? "-" : appNo, null, masked.length() > 300 ? masked.substring(0, 300) : masked);
        return out;
    }

    static String extractive(List<Passage> ps) {
        StringBuilder b = new StringBuilder();
        for (Passage p : ps.subList(0, Math.min(3, ps.size()))) b.append(p.text()).append(" [").append(p.id()).append("]\n");
        return b.toString().trim();
    }
}
