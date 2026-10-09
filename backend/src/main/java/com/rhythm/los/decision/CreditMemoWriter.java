package com.rhythm.los.decision;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.ReasonCodes;
import com.rhythm.los.product.Product;

import static com.rhythm.los.common.Fmt.*;

/**
 * Template credit memo built only from structured engine outputs, so every statement traces to data.
 * Phase 3 adds a Claude-written narrative on top of the same facts (with personal data masked).
 */
public final class CreditMemoWriter {
    private CreditMemoWriter() {}

    public static String write(LoanApplication a, Product p, EngineInput in, EngineResult r, Delegation.Level lvl) {
        StringBuilder m = new StringBuilder();
        m.append("CREDIT MEMO · ").append(a.getAppNo()).append('\n');
        m.append("Product: ").append(p.getName()).append(" · Segment: ").append(a.getSegment()).append("\n\n");

        m.append("1. Applicant\n");
        m.append(a.getApplicantName()).append(", age ").append(in.age()).append(", ").append(a.getCity() == null ? "" : a.getCity())
                .append(". ").append(a.getBusinessName() == null ? "" : a.getBusinessName() + ", ")
                .append("vintage ").append(in.vintage()).append(" years. Declared income ").append(rupees(in.declaredIncome())).append("/month.\n\n");

        m.append("2. Request\n");
        m.append(rupees(in.loan().amount())).append(" for ").append(in.loan().tenure()).append(" months")
                .append(a.getPurpose() == null ? "" : " · purpose: " + a.getPurpose()).append(".\n\n");

        m.append("3. Data used\n");
        m.append("KYC identity match ").append(Math.round(r.idScore() * 100)).append("% (").append(r.kycStatus()).append("). ");
        m.append("Bank data: ").append(in.bank().inflows().size()).append(" months via Account Aggregator. ");
        m.append(in.bureau() == null ? "Bureau: no hit (thin file) - assessed on cash flow. " :
                "Bureau score " + in.bureau().score() + ", " + in.bureau().active() + " active lines, max DPD (12m) " + in.bureau().maxDpd12() + ". ");
        if (in.gst() != null) m.append("GST filing regularity ").append(Math.round(in.gst().filing() * 100)).append("%. ");
        m.append("\n\n");

        m.append("4. Cash flow and affordability\n");
        m.append("Sustainable income ").append(rupees(r.income())).append("/month (stability ").append(Math.round(r.stability() * 100)).append("%). ");
        m.append("Existing EMIs ").append(rupees(r.existing())).append(". Surplus after essentials ").append(rupees(r.surplus())).append(". ");
        m.append("Maximum affordable EMI ").append(rupees(r.maxEmi())).append("; requested EMI ").append(rupees(r.reqEmi()))
                .append("; FOIR after loan ").append(pct(r.foirPost(), 0)).append(".\n\n");

        m.append("5. Risk\n");
        m.append("PD ").append(pct(r.pd(), 1)).append(", score ").append(r.score()).append(", band ").append(r.band())
                .append(". Fraud score ").append(r.fraud()).append("/100 (").append(r.fraudStatus()).append("). ")
                .append("Expected loss ").append(rupees(r.expectedLoss())).append(".\n");
        m.append(r.narrative()).append("\n\n");

        m.append("6. Policy rules\n");
        long failed = r.rules().stream().filter(x -> !x.pass()).count();
        if (failed == 0) m.append("All ").append(r.rules().size()).append(" rules passed.\n");
        r.rules().stream().filter(x -> !x.pass())
                .forEach(x -> m.append("- ").append(x.id()).append(" ").append(x.description()).append(": ").append(x.action()).append('\n'));
        m.append('\n');

        m.append("7. Recommendation\n");
        m.append(r.decision().replace('_', ' ')).append(": ").append(rupees(r.recAmt())).append(" at ").append(r.rate())
                .append("% for ").append(in.loan().tenure()).append(" months, EMI ").append(rupees(r.recEmi())).append(".\n");
        if (!r.conditions().isEmpty()) {
            m.append("Conditions:\n");
            r.conditions().forEach(c -> m.append("- ").append(c).append('\n'));
        }
        if (!r.reasonCodes().isEmpty()) {
            m.append("Reason codes: ");
            m.append(String.join("; ", r.reasonCodes().stream().map(c -> c + " " + ReasonCodes.TEXT.getOrDefault(c, "")).toList())).append('\n');
        }
        m.append('\n');
        m.append("8. Sanction authority\n");
        m.append(lvl.level()).append(" (").append(lvl.role().replace('_', ' ').toLowerCase()).append("), ")
                .append(lvl.deviations()).append(" policy deviation(s).\n\n");
        m.append("Model ").append(r.modelVersion()).append(" · Policy ").append(r.policyVersion()).append('\n');
        return m.toString();
    }
}
