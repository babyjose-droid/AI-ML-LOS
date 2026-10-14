package com.rhythm.los.risk;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Household cash flow (FSD 13.4): members, who earns what, household expenses and existing repayments. For microfinance
 * the household is the unit the RBI directions (2022) use: annual household income up to ₹3 lakh, and all repayments
 * within 50% of monthly household income. Captured by sales, operations or the field officer before the decision.
 */
@Service
public class HouseholdService {
    static final Set<String> RELATIONS = Set.of("SELF", "SPOUSE", "SON", "DAUGHTER", "FATHER", "MOTHER", "BROTHER", "SISTER", "OTHER");

    public record MemberIn(String name, String relation, String occupation, BigDecimal monthlyIncome, Boolean included) {}
    public record HouseholdIn(Integer members, BigDecimal monthlyExpenses, BigDecimal existingEmi, List<MemberIn> earners) {}

    private final HouseholdRepository households;
    private final HouseholdMemberRepository members;
    private final ApplicationService apps;
    private final AuditService audit;

    public HouseholdService(HouseholdRepository households, HouseholdMemberRepository members, ApplicationService apps, AuditService audit) {
        this.households = households;
        this.members = members;
        this.apps = apps;
        this.audit = audit;
    }

    public Map<String, Object> view(Long appId) {
        Household h = households.findById(appId).orElse(null);
        List<HouseholdMember> ms = members.findByApplicationIdOrderByIdAsc(appId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("household", h);
        m.put("earners", ms);
        EngineInput.Household e = engineInput(appId).orElse(null);
        if (e != null) {
            double surplus = e.monthlyIncome() - e.monthlyExpenses() - e.existingEmi();
            m.put("monthlyIncome", e.monthlyIncome());
            m.put("annualIncome", e.monthlyIncome() * 12);
            m.put("surplus", surplus);
            m.put("mfiQualifying", e.monthlyIncome() * 12 <= 300_000);
            m.put("mfiRepaymentRoom", Math.max(0, 0.5 * e.monthlyIncome() - e.existingEmi()));
        }
        return m;
    }

    /** The household as the decision engine sees it; empty when not captured. */
    public Optional<EngineInput.Household> engineInput(Long appId) {
        Household h = households.findById(appId).orElse(null);
        if (h == null) return Optional.empty();
        List<HouseholdMember> ms = members.findByApplicationIdOrderByIdAsc(appId);
        double income = ms.stream().filter(HouseholdMember::isIncluded).mapToDouble(x -> x.getMonthlyIncome().doubleValue()).sum();
        int earners = (int) ms.stream().filter(x -> x.isIncluded() && x.getMonthlyIncome().signum() > 0).count();
        return Optional.of(new EngineInput.Household(h.getMembers(), earners, income, h.getMonthlyExpenses().doubleValue(), h.getExistingEmi().doubleValue()));
    }

    @Transactional
    public Map<String, Object> save(Long appId, HouseholdIn in, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!ApplicationService.isOpen(a) || List.of("SANCTIONED", "DISBURSED").contains(a.getAppState()))
            throw ApiException.conflict("CLOSED", "The household can only change before sanction");
        if (in.members() == null || in.members() < 1 || in.members() > 30) throw ApiException.unprocessable("BAD_MEMBERS", "Household members must be 1 to 30");
        if (in.earners() == null || in.earners().isEmpty()) throw ApiException.unprocessable("NO_EARNERS", "List at least the applicant as an earning member");
        if (in.earners().size() > in.members()) throw ApiException.unprocessable("BAD_MEMBERS", "More earners than household members");
        BigDecimal exp = in.monthlyExpenses() == null ? BigDecimal.ZERO : in.monthlyExpenses();
        BigDecimal emi = in.existingEmi() == null ? BigDecimal.ZERO : in.existingEmi();
        if (exp.signum() < 0 || emi.signum() < 0) throw ApiException.unprocessable("NEGATIVE", "Amounts cannot be negative");
        for (MemberIn m : in.earners()) {
            if (m.name() == null || m.name().isBlank()) throw ApiException.unprocessable("NAME_REQUIRED", "Each member needs a name");
            if (m.relation() == null || !RELATIONS.contains(m.relation())) throw ApiException.unprocessable("BAD_RELATION", "Relation must be one of " + RELATIONS);
            if (m.monthlyIncome() == null || m.monthlyIncome().signum() < 0) throw ApiException.unprocessable("BAD_INCOME", "Monthly income cannot be negative");
        }
        Household h = households.findById(appId).orElseGet(() -> { Household x = new Household(); x.setApplicationId(appId); return x; });
        h.setMembers(in.members());
        h.setMonthlyExpenses(exp);
        h.setExistingEmi(emi);
        h.setUpdatedBy(by.username());
        h.setUpdatedAt(Instant.now());
        households.save(h);
        members.deleteByApplicationId(appId);
        members.flush();
        for (MemberIn m : in.earners()) {
            HouseholdMember x = new HouseholdMember();
            x.setApplicationId(appId);
            x.setName(m.name().trim());
            x.setRelation(m.relation());
            x.setOccupation(m.occupation());
            x.setMonthlyIncome(m.monthlyIncome());
            x.setIncluded(m.included() == null || m.included());
            members.save(x);
        }
        audit.record(by, "household.saved", "APPLICATION", a.getAppNo(), appId, in.members() + " members, " + in.earners().size() + " listed earners");
        return view(appId);
    }
}
