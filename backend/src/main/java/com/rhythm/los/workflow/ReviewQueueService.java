package com.rhythm.los.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.decision.Delegation;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Review queue (SCR-051, FR-UW-06): every case waiting for a sanction decision, as a case card with the facts an
 * approver needs to decide without opening the file: band, PD, recommended amount, reason codes, deviations,
 * fraud and field status, the level it needs and how long it has waited against the SLA.
 */
@Service
public class ReviewQueueService {
    static final int SLA_HOURS = 24;

    public record Card(Long applicationId, String appNo, String applicant, String product, String segment, double amount,
                       String decision, String riskBand, Double pd, Integer score, Double recommendedAmount, Double rate, Double foir,
                       List<String> reasonCodes, List<String> deviations, String fraudState, String fieldState, String level,
                       boolean canAct, long ageHours, int slaHours, boolean breached, boolean sentBackBefore) {}

    private final LoanApplicationRepository apps;
    private final DecisionRecordRepository decisions;
    private final DataScope scope;
    private final com.rhythm.los.sanction.SanctionRecordRepository sanctions;

    public ReviewQueueService(LoanApplicationRepository apps, DecisionRecordRepository decisions, DataScope scope,
                              com.rhythm.los.sanction.SanctionRecordRepository sanctions) {
        this.apps = apps;
        this.decisions = decisions;
        this.scope = scope;
        this.sanctions = sanctions;
    }

    public List<Card> queue(CurrentUser u) {
        Set<Long> visible = scope.visibleBranches(u);
        List<Card> out = new ArrayList<>();
        for (LoanApplication a : apps.findAllByOrderByIdDesc()) {
            String s = a.getSanctionState();
            if (!s.startsWith("PENDING_") || !DataScope.canSee(visible, a)) continue;
            String level = s.substring(8);
            DecisionRecord d = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).orElse(null);
            List<String> devs = new ArrayList<>();
            if (d != null && d.getRulesJson() != null)
                for (EngineResult.RuleResult r : Json.read(d.getRulesJson(), new TypeReference<List<EngineResult.RuleResult>>() {}))
                    if (!r.pass() && "REFER".equals(r.action())) devs.add(r.id() + " · " + r.description());
            Instant since = d == null ? a.getUpdatedAt() : d.getCreatedAt();
            long age = Duration.between(since, Instant.now()).toHours();
            out.add(new Card(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(), a.getSegment(), a.getLoanAmount().doubleValue(),
                    d == null ? null : d.getDecision(), d == null ? null : d.getRiskBand(), d == null ? null : d.getPd().doubleValue(),
                    d == null ? null : d.getScore(), d == null || d.getRecommendedAmount() == null ? null : d.getRecommendedAmount().doubleValue(),
                    d == null || d.getRate() == null ? null : d.getRate().doubleValue(), d == null || d.getFoirPost() == null ? null : d.getFoirPost().doubleValue(),
                    d == null || d.getReasonCodes() == null || d.getReasonCodes().isBlank() ? List.of() : List.of(d.getReasonCodes().split(",")),
                    devs, a.getFraudState(), a.getFieldState(), level, u.role().sanctionLevel() >= Delegation.rank(level),
                    age, SLA_HOURS, age > SLA_HOURS, sanctions.findFirstByApplicationIdAndActionOrderByIdDesc(a.getId(), "SENT_BACK").isPresent()));
        }
        out.sort(Comparator.comparing(Card::breached).reversed().thenComparing(Comparator.comparingLong(Card::ageHours).reversed()));
        return out;
    }
}
