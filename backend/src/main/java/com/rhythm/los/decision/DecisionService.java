package com.rhythm.los.decision;

import com.rhythm.los.application.*;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.document.DocumentService;
import com.rhythm.los.integration.DataFetchService;
import com.rhythm.los.integration.DataSnapshot;
import com.rhythm.los.integration.DataSnapshotRepository;
import com.rhythm.los.integration.VendorModels.*;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.product.Product;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class DecisionService {
    private final ApplicationService apps;
    private final LoanApplicationRepository appRepo;
    private final DataSnapshotRepository snapshots;
    private final DocumentService documents;
    private final PolicyService policy;
    private final DecisionRecordRepository decisions;

    public DecisionService(ApplicationService apps, LoanApplicationRepository appRepo, DataSnapshotRepository snapshots,
                           DocumentService documents, PolicyService policy, DecisionRecordRepository decisions) {
        this.apps = apps;
        this.appRepo = appRepo;
        this.snapshots = snapshots;
        this.documents = documents;
        this.policy = policy;
        this.decisions = decisions;
    }

    public Optional<DecisionRecord> latest(Long appId) { return decisions.findFirstByApplicationIdOrderByIdDesc(appId); }

    /** What still blocks a decision, in plain words. Empty means the engine can run. */
    public List<String> blockers(LoanApplication a) {
        List<String> b = new ArrayList<>();
        if (!Set.of("SUBMITTED", "UNDERWRITING").contains(a.getAppState())) b.add("Application is " + a.getAppState());
        if ("NOT_STARTED".equals(a.getKycState())) b.add("KYC not run");
        if ("REVIEW".equals(a.getKycState())) b.add("KYC review pending (video KYC)");
        if (!"FETCHED".equals(a.getDataState())) b.add("External data not fetched (" + a.getDataState() + ")");
        if ("IN_REVIEW".equals(a.getDocsState())) b.add("Documents waiting for review");
        else if (!"COMPLETE".equals(a.getDocsState())) b.add("Documents not complete (" + a.getDocsState() + ")");
        if ("REQUIRED".equals(a.getFieldState())) b.add("Field visit pending");
        if (Set.of("SANCTIONED", "KFS_ACCEPTED", "DECLINED").contains(a.getSanctionState())) b.add("Already " + a.getSanctionState());
        return b;
    }

    @Transactional
    public DecisionRecord run(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        List<String> blockers = blockers(a);
        if (!blockers.isEmpty()) throw ApiException.conflict("PRECONDITION_FAILED", "Cannot decide yet: " + String.join("; ", blockers));

        Product p = apps.product(a);
        PolicyParams pol = policy.live();
        EngineInput in = buildInput(a, p, pol);
        EngineResult r = UnderwritingEngine.underwrite(in, pol);
        Delegation.Level lvl = Delegation.of(r);

        DecisionRecord d = new DecisionRecord();
        d.setApplicationId(a.getId());
        d.setDecision(r.decision());
        d.setPd(bd(r.pd(), 6));
        d.setScore(r.score());
        d.setRiskBand(r.band());
        d.setFraudScore(r.fraud());
        d.setFraudStatus(r.fraudStatus());
        d.setKycStatus(r.kycStatus());
        d.setSustainableIncome(bd(r.income(), 2));
        d.setMaxEmi(bd(r.maxEmi(), 2));
        d.setRequestedEmi(bd(r.reqEmi(), 2));
        d.setRecommendedAmount(bd(r.recAmt(), 2));
        d.setRecommendedEmi(bd(r.recEmi(), 2));
        d.setRate(bd(r.rate(), 2));
        d.setFoirPost(bd(Math.min(r.foirPost(), 99), 4));
        d.setExpectedLoss(bd(r.expectedLoss(), 2));
        d.setDelegationLevel("REJECT".equals(r.decision()) ? null : lvl.level());
        d.setRulesJson(Json.write(r.rules()));
        d.setContributionsJson(Json.write(r.contributions()));
        d.setStepsJson(Json.write(r.steps()));
        d.setReasonCodes(String.join(",", r.reasonCodes()));
        d.setConditionsJson(Json.write(r.conditions()));
        d.setNarrative(r.narrative().length() > 2000 ? r.narrative().substring(0, 2000) : r.narrative());
        d.setCreditMemo(CreditMemoWriter.write(a, p, in, r, lvl));
        d.setModelVersion(r.modelVersion());
        d.setPolicyVersion(r.policyVersion());
        String inputJson = Json.write(in);
        d.setInputJson(inputJson);
        d.setInputHash(sha256(inputJson + "|" + Json.write(pol)));
        d.setCreatedBy(by.username());
        d = decisions.save(d);

        // states
        if (!"PENDING".equals(a.getDecisionState())) apps.transition(a, Domain.DECISION, "PENDING", "decision.rerun", by, null);
        if (a.getSanctionState().startsWith("PENDING_")) apps.transition(a, Domain.SANCTION, "NOT_STARTED", "sanction.reset", by, "Decision re-run");
        if ("SUBMITTED".equals(a.getAppState())) apps.transition(a, Domain.APP, "UNDERWRITING", "application.underwriting", by, null);
        apps.transition(a, Domain.DECISION, r.decision(), "decision.made", by,
                "PD " + String.format("%.1f%%", r.pd() * 100) + ", band " + r.band() + ", " + r.decision());
        if (!a.getFraudState().equals(r.fraudStatus()) || "NOT_CHECKED".equals(a.getFraudState())) {
            if (StateMachineGuard.fraudAllowed(a.getFraudState(), r.fraudStatus()))
                apps.transition(a, Domain.FRAUD, r.fraudStatus(), "fraud.scored", by, "Fraud score " + r.fraud());
        }
        if ("REJECT".equals(r.decision())) {
            String rule = r.rules().stream().filter(x -> !x.pass() && "REJECT".equals(x.action())).findFirst()
                    .map(x -> x.id() + " " + x.description()).orElse("policy");
            apps.reject(a, "Declined by decision engine: " + rule + " not met", by);
        } else {
            apps.transition(a, Domain.SANCTION, "PENDING_" + lvl.level(), "sanction.routed", by,
                    "Routed to " + lvl.role() + " (" + lvl.deviations() + " deviation(s))");
        }
        return d;
    }

    EngineInput buildInput(LoanApplication a, Product p, PolicyParams pol) {
        KycBundle k = snap(a, "KYC", KycBundle.class).orElseThrow(() -> ApiException.conflict("NO_KYC", "KYC data missing"));
        AaStatement s = snap(a, "AA", AaStatement.class).orElseThrow(() -> ApiException.conflict("NO_AA", "Bank data missing"));
        BureauReport b = snap(a, "BUREAU", BureauReport.class).orElse(null);
        GstProfile g = snap(a, "GST", GstProfile.class).orElse(null);

        EngineInput.Kyc kyc = new EngineInput.Kyc(k.nameMatch(), k.dobMatch(), k.idMatch(), k.addressMatch(), k.contactMatch());
        if ("VERIFIED".equals(a.getKycState()) && DataFetchService.idScore(k) < pol.kycReview()) {
            // KYC review was cleared by operations (for example after video KYC): treat address and contact as confirmed
            kyc = new EngineInput.Kyc(k.nameMatch(), k.dobMatch(), k.idMatch(), 1.0, 1.0);
        } else if ("FAILED".equals(a.getKycState())) {
            // KYC failed by a reviewer: identity is not established, so the engine's hard KYC rule must fail
            kyc = new EngineInput.Kyc(0, k.dobMatch(), k.idMatch(), 0, 0);
        }
        long dup = appRepo.countOtherPansWithMobile(a.getMobile(), a.getPan());
        long velocity = appRepo.countByPanSince(a.getPan(), Instant.now().minus(30, ChronoUnit.DAYS));
        EngineInput.Fraud fraud = new EngineInput.Fraud((int) dup, documents.anyTampered(a.getId()), (int) Math.max(1, velocity));

        return new EngineInput(
                a.getAppNo(), a.getApplicantName(), a.getSegment(), a.ageYears(),
                a.getBusinessVintageYears().doubleValue(), kyc,
                a.getDeclaredMonthlyIncome().doubleValue(), a.getEssentialExpenses().doubleValue(),
                new EngineInput.Bank(s.inflows(), s.outflows(), s.avgBalance(), s.bounces(), s.detectedEmi()),
                b != null && b.hit() ? new EngineInput.Bureau(b.score(), b.activeLines(), b.maxDpd12(), b.ever90(), b.enquiries6m(), b.historyMonths(), b.totalEmi()) : null,
                g == null ? null : new EngineInput.Gst(g.filingRegularity(), g.turnoverGrowth(), g.bankGstGap()),
                null,
                "MICROFINANCE".equals(a.getSegment()),
                p.isSecured(),
                fraud,
                new EngineInput.Loan(a.getLoanAmount().doubleValue(), a.getTenureMonths()));
    }

    private <T> Optional<T> snap(LoanApplication a, String kind, Class<T> type) {
        return snapshots.findFirstByApplicationIdAndKindOrderByIdDesc(a.getId(), kind)
                .map(DataSnapshot::getPayloadJson).map(j -> Json.read(j, type));
    }

    private static BigDecimal bd(double v, int scale) { return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP); }

    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Small helper so a re-run with the same fraud status does not attempt an invalid self-transition. */
    static final class StateMachineGuard {
        static boolean fraudAllowed(String from, String to) {
            return StateMachine.allowed(Domain.FRAUD, from, to) && !(from.equals(to) && !"CLEAR".equals(from));
        }
    }
}
