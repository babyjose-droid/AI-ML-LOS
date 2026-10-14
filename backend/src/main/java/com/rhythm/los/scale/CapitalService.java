package com.rhythm.los.scale;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LoanBookService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.disbursement.Disbursement;
import com.rhythm.los.disbursement.DisbursementRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Capital and liquidity planning: starting figures from the loan book, the projection, and saved scenarios. */
@Service
public class CapitalService {
    private final LoanBookService book;
    private final DisbursementRepository disbursements;
    private final CapitalScenarioRepository scenarios;
    private final AuditService audit;

    public CapitalService(LoanBookService book, DisbursementRepository disbursements, CapitalScenarioRepository scenarios, AuditService audit) {
        this.book = book;
        this.disbursements = disbursements;
        this.scenarios = scenarios;
        this.audit = audit;
    }

    /** Starting point: book, yield, tenure and loss from the live book; disbursals from the last 90 days. Balance-sheet items are placeholders to replace. */
    public Map<String, Object> defaults(CurrentUser u) {
        List<LiveLoan> act = book.visible(u).stream().filter(l -> "ACTIVE".equals(l.getStatus())).toList();
        double os = act.stream().mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
        double yield = os == 0 ? 18 : act.stream().mapToDouble(l -> l.getOutstanding().doubleValue() * l.getRate().doubleValue()).sum() / os;
        double tenure = act.isEmpty() ? 24 : act.stream().mapToInt(LiveLoan::getTenureMonths).average().orElse(24);
        double ecl = act.stream().mapToDouble(l -> l.getEcl().doubleValue()).sum();
        Instant since = Instant.now().minus(Duration.ofDays(90));
        double disb90 = disbursements.findAll().stream().filter(d -> "SUCCESS".equals(d.getStatus()) && d.getCreatedAt().isAfter(since))
                .map(Disbursement::getGrossAmount).filter(Objects::nonNull).mapToDouble(java.math.BigDecimal::doubleValue).sum();
        double monthly = Math.max(disb90 / 3, os * 0.04);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ownedFunds", Math.round(os * 0.25));
        m.put("borrowings", Math.round(os * 0.80));
        m.put("cash", Math.round(os * 0.05));
        m.put("book", Math.round(os));
        m.put("monthlyDisbursement", Math.round(monthly));
        m.put("disbursementGrowthPct", 2.0);
        m.put("collectionEfficiency", 0.97);
        m.put("yieldPct", Math.round(yield * 100) / 100.0);
        m.put("costOfFundsPct", 11.0);
        m.put("creditLossPct", os == 0 ? 2.0 : Math.round(ecl / os * 10000) / 100.0);
        m.put("opexPct", 4.0);
        m.put("newBorrowingMonthly", Math.round(monthly * 0.7));
        m.put("borrowingRepaymentMonthly", Math.round(os * 0.80 / 36));
        m.put("avgTenureMonths", Math.round(tenure));
        m.put("minCrar", 0.15);
        m.put("riskWeight", 1.0);
        m.put("months", 12);
        m.put("notes", List.of("Book, yield, tenure and expected loss come from the live loan book; disbursals from the last 90 days.",
                "Owned funds, borrowings, cash and borrowing flows are placeholders: enter the figures from the balance sheet and ALM."));
        return m;
    }

    public Map<String, Object> project(CapitalPlanner.Inputs in) {
        List<String> errors = CapitalPlanner.validate(in);
        if (!errors.isEmpty()) throw ApiException.unprocessable("BAD_INPUTS", String.join("; ", errors));
        return CapitalPlanner.project(in);
    }

    public record Save(String name, CapitalPlanner.Inputs inputs) {}

    public CapitalScenario save(Save r, CurrentUser by) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 80) throw ApiException.unprocessable("NAME_REQUIRED", "Name the scenario");
        Map<String, Object> result = project(r.inputs());
        CapitalScenario s = new CapitalScenario();
        s.setName(r.name().trim());
        s.setInputsJson(Json.write(r.inputs()));
        s.setResultJson(Json.write(result));
        s.setCreatedBy(by.username());
        scenarios.save(s);
        audit.record(by, "capital.scenario_saved", "CAPITAL", s.getName(), null, "min CRAR " + result.get("minCrar") + ", min cash " + result.get("minCash"));
        return s;
    }

    public List<CapitalScenario> list() { return scenarios.findTop20ByOrderByIdDesc(); }
}
