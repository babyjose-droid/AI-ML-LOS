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
    private final com.rhythm.los.vkyc.VkycSessionRepository vkyc;
    private final com.rhythm.los.model.ModelService models;
    private final PolicyService policy;
    private final DecisionRecordRepository decisions;

    private final com.rhythm.los.flow.WorkflowService workflow;
    private final com.rhythm.los.flow.WorkflowDefinitions defs;

    public DecisionService(ApplicationService apps, LoanApplicationRepository appRepo, DataSnapshotRepository snapshots,
                           DocumentService documents, PolicyService policy, DecisionRecordRepository decisions,
                           com.rhythm.los.vkyc.VkycSessionRepository vkyc, com.rhythm.los.model.ModelService models,
                           com.rhythm.los.flow.WorkflowService workflow, com.rhythm.los.flow.WorkflowDefinitions defs) {
        this.workflow = workflow;
        this.defs = defs;
        this.models = models;
        this.vkyc = vkyc;
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
        Product prod = apps.product(a);
        if (prod.videoKycRequired(a.getLoanAmount().doubleValue())
                && vkyc.findByApplicationIdOrderByIdDesc(a.getId()).stream().noneMatch(s -> s.getPartyId() == null && "APPROVED".equals(s.getStatus())))
            b.add("Video KYC required for this product: not yet approved");
        if (Set.of("SANCTIONED", "KFS_ACCEPTED", "DECLINED").contains(a.getSanctionState())) b.add("Already " + a.getSanctionState());
        b.addAll(workflow.pendingBefore(a, "uw"));
        PartyGate pg = partyGate();
        if (pg != null) b.addAll(pg.blockers(a, "DECISION"));
        return b;
    }

    public java.util.Optional<java.time.Instant> latestCreatedAt(Long appId) {
        return decisions.findFirstByApplicationIdOrderByIdDesc(appId).map(DecisionRecord::getCreatedAt);
    }

    /** The decision fields of an engine result, exactly as stored and as returned by the stateless Decision API (FR-UW-07). */
    public static DecisionRecord toRecord(EngineResult r, Delegation.Level lvl, List<String> conditions) {
        DecisionRecord d = new DecisionRecord();
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
        d.setDelegationLevel("REJECT".equals(r.decision()) || "INCOMPLETE".equals(r.decision()) ? null : lvl.level());
        d.setMissingData(r.missingData().isEmpty() ? null : Json.write(r.missingData()));
        d.setRulesJson(Json.write(r.rules()));
        d.setContributionsJson(Json.write(r.contributions()));
        d.setStepsJson(Json.write(r.steps()));
        d.setReasonCodes(String.join(",", r.reasonCodes()));
        d.setConditionsJson(Json.write(conditions));
        d.setNarrative(r.narrative().length() > 2000 ? r.narrative().substring(0, 2000) : r.narrative());
        d.setModelVersion(r.modelVersion());
        d.setPolicyVersion(r.policyVersion());
        return d;
    }

    /**
     * Stateless decision (Decision API, FSD 15): runs the same engine with the live policy and champion model on a
     * caller-supplied input; nothing is stored. When a product code is given, its eligibility and price band apply.
     */
    public DecisionRecord evaluate(EngineInput in, String productCode) {
        if (in == null || in.loan() == null) throw ApiException.unprocessable("BAD_INPUT", "Input and loan (amount, tenure) are required");
        if (productCode != null && !productCode.isBlank()) {
            Product p = productLookup.apply(productCode);
            in = new EngineInput(in.id(), in.name(), in.segment(), in.age(), in.vintage(), in.kyc(), in.declaredIncome(), in.essential(), in.bank(),
                    in.bureau(), in.gst(), in.internal(), in.jlg(), in.secured(), in.fraud(), in.loan(),
                    new EngineInput.Eligibility(p.getCode(), p.getMinAge(), p.getMaxAge(),
                            p.getMinVintageYears() == null ? 0 : p.getMinVintageYears().doubleValue(),
                            p.getMinMonthlyIncome() == null ? 0 : p.getMinMonthlyIncome().doubleValue(), null,
                            p.getRateMin() == null ? 0 : p.getRateMin().doubleValue(), p.getRateMax() == null ? 0 : p.getRateMax().doubleValue()));
        }
        PolicyParams pol = policy.liveFor(in.eligibility() == null ? null : in.eligibility().product(), in.segment());
        EngineResult r = UnderwritingEngine.underwrite(in, pol, models.champion(in.bureau() != null).orElse(null), models.fraudChampion().orElse(null));
        Delegation.Level lvl = Delegation.of(r);
        DecisionRecord d = toRecord(r, lvl, new ArrayList<>(r.conditions()));
        d.setInputJson(Json.write(in));
        d.setInputHash(sha256(Json.write(in) + "|" + Json.write(pol)));
        d.setCreatedBy("api");
        return d;
    }

    private java.util.function.Function<String, Product> productLookup = c -> { throw ApiException.notFound("Product " + c); };

    @org.springframework.beans.factory.annotation.Autowired
    void setProducts(com.rhythm.los.product.ProductService products) { this.productLookup = c -> products.definition(c, null); }

    @Transactional
    public DecisionRecord run(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        List<String> blockers = blockers(a);
        if (!blockers.isEmpty()) throw ApiException.conflict("PRECONDITION_FAILED", "Cannot decide yet: " + String.join("; ", blockers));

        Product p = apps.product(a);
        PolicyParams pol = policy.liveFor(p.getCode(), a.getSegment());
        EngineInput in = buildInput(a, p, pol);
        EngineResult r = UnderwritingEngine.underwrite(in, pol, models.champion(in.bureau() != null).orElse(null), models.fraudChampion().orElse(null));
        Delegation.Level lvl = Delegation.of(r);
        String hash = sha256(Json.write(in) + "|" + Json.write(pol));
        DecisionRecord prev = latest(appId).orElse(null);
        // nothing new since the last INCOMPLETE: do not record the same answer again
        if ("INCOMPLETE".equals(r.decision()) && prev != null && "INCOMPLETE".equals(prev.getDecision()) && hash.equals(prev.getInputHash())) return prev;
        boolean decided = !"REJECT".equals(r.decision()) && !"INCOMPLETE".equals(r.decision());
        // new to credit (no bureau record): the field visit becomes mandatory (FSD 13.2), unless the product never visits
        boolean needFieldNtc = decided && in.bureau() == null && "NOT_REQUIRED".equals(a.getFieldState())
                && !"NEVER".equals(p.getFieldVisitRule()) && defs.stages(a).stream().anyMatch(x -> "field".equals(x.key()));
        boolean provisional = decided && ("REQUIRED".equals(a.getFieldState()) || needFieldNtc);
        List<String> conditions = new ArrayList<>(r.conditions());
        if (provisional) conditions.add(0, "Provisional: field visit pending. Sanction is blocked until the visit is submitted");

        DecisionRecord d = toRecord(r, lvl, conditions);
        d.setApplicationId(a.getId());
        d.setCreditMemo(CreditMemoWriter.write(a, p, in, r, lvl));
        d.setNetworkScore(in.fraud() == null ? null : in.fraud().networkScore());
        var card = scorecards == null ? java.util.Optional.<com.rhythm.los.risk.Scorecard.Result>empty() : scorecards.score(a, in, r, fieldData.apply(a));
        if (card.isPresent()) d.setScorecardJson(Json.write(card.get()));
        String inputJson = Json.write(in);
        d.setInputJson(inputJson);
        d.setInputHash(hash);
        d.setCreatedBy(by.username());
        d = decisions.save(d);
        models.shadow(d.getId(), a.getId(), in, pol, r);

        // states
        if (!"PENDING".equals(a.getDecisionState())) apps.transition(a, Domain.DECISION, "PENDING", "decision.rerun", by, null);
        if (a.getSanctionState().startsWith("PENDING_")) apps.transition(a, Domain.SANCTION, "NOT_STARTED", "sanction.reset", by, "Decision re-run");
        if ("SUBMITTED".equals(a.getAppState())) apps.transition(a, Domain.APP, "UNDERWRITING", "application.underwriting", by, null);
        apps.transition(a, Domain.DECISION, r.decision(), "decision.made", by,
                "INCOMPLETE".equals(r.decision()) ? "Missing: " + String.join("; ", r.missingData())
                        : "PD " + String.format("%.1f%%", r.pd() * 100) + ", band " + r.band() + ", " + r.decision() + (provisional ? " (provisional: field visit pending)" : ""));
        if (!a.getFraudState().equals(r.fraudStatus()) || "NOT_CHECKED".equals(a.getFraudState())) {
            if (StateMachineGuard.fraudAllowed(a.getFraudState(), r.fraudStatus()))
                apps.transition(a, Domain.FRAUD, r.fraudStatus(), "fraud.scored", by, "Fraud score " + r.fraud());
        }
        if ("INCOMPLETE".equals(r.decision())) {
            // no sanction routing on partial data; the application waits for the named items (FSD 14.1 step 4)
            return d;
        }
        if (needFieldNtc)
            apps.transition(a, Domain.FIELD, "REQUIRED", "field.required_no_bureau", CurrentUser.SYSTEM,
                    "No bureau record (new to credit): field visit is mandatory");
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
        EngineInput.Fraud fraud = new EngineInput.Fraud((int) dup, documents.anyTampered(a.getId()), (int) Math.max(1, velocity),
                s.rotationShare(), network == null ? 0 : network.score(a));

        return new EngineInput(
                a.getAppNo(), a.getApplicantName(), a.getSegment(), a.ageYears(),
                a.getBusinessVintageYears().doubleValue(), kyc,
                a.getDeclaredMonthlyIncome().doubleValue(), a.getEssentialExpenses().doubleValue(),
                new EngineInput.Bank(s.inflows(), s.outflows(), s.avgBalance(), s.bounces(), s.detectedEmi(), s.gambling(),
                        snapshots.findFirstByApplicationIdAndKindOrderByIdDesc(a.getId(), "AA").map(x -> DataFetchService.STATEMENT_VENDOR.equals(x.getVendor()) ? "bank statement" : "AA").orElse("AA"),
                        documents.employerCheck(a.getId()).map(c -> !c.matches()).orElse(false)),
                b != null && b.hit() ? new EngineInput.Bureau(b.score(), b.activeLines(), b.maxDpd12(), b.ever90(), b.enquiries6m(), b.historyMonths(), b.totalEmi()) : null,
                g == null ? null : new EngineInput.Gst(g.filingRegularity(), g.turnoverGrowth(), g.bankGstGap()),
                null,
                "MICROFINANCE".equals(a.getSegment()) && workflow.groupRecognised(a),
                p.isSecured(),
                fraud,
                new EngineInput.Loan(a.getLoanAmount().doubleValue(), a.getTenureMonths(), "REVOLVING".equals(p.getRepaymentFrequency()), "BULLET".equals(p.getRepaymentFrequency())),
                new EngineInput.Eligibility(p.getCode(), p.getMinAge(), p.getMaxAge(),
                        p.getMinVintageYears() == null ? 0 : p.getMinVintageYears().doubleValue(),
                        p.getMinMonthlyIncome() == null ? 0 : p.getMinMonthlyIncome().doubleValue(),
                        served(a, p),
                        p.getRateMin() == null ? 0 : p.getRateMin().doubleValue(), p.getRateMax() == null ? 0 : p.getRateMax().doubleValue()),
                households == null ? null : households.engineInput(a.getId()).orElse(null),
                partyGate() == null ? null : partyGate().joint(a));
    }

    /** Co-applicants and guarantors, looked up lazily (the party module depends on the workflow, not on the decision). */
    public interface PartyGate {
        List<String> blockers(LoanApplication a, String gate);
        EngineInput.Joint joint(LoanApplication a);
    }

    private org.springframework.beans.factory.ObjectProvider<PartyGate> partyGates;

    @org.springframework.beans.factory.annotation.Autowired
    void setPartyGates(org.springframework.beans.factory.ObjectProvider<PartyGate> p) { this.partyGates = p; }

    private PartyGate partyGate() { return partyGates == null ? null : partyGates.getIfAvailable(); }

    private com.rhythm.los.risk.NetworkService network;
    private com.rhythm.los.risk.HouseholdService households;
    private com.rhythm.los.risk.ScorecardService scorecards;
    private java.util.function.Function<LoanApplication, java.util.Map<String, Double>> fieldData = x -> java.util.Map.of();

    @org.springframework.beans.factory.annotation.Autowired
    void setRisk(com.rhythm.los.risk.NetworkService n, com.rhythm.los.risk.HouseholdService h, com.rhythm.los.risk.ScorecardService sc) {
        this.network = n;
        this.households = h;
        this.scorecards = sc;
    }

    /** Field-visit characteristics for the industry scorecards (stock value, shop size, animals...). */
    public void setFieldData(java.util.function.Function<LoanApplication, java.util.Map<String, Double>> f) { this.fieldData = f; }

    /** Geography (FSD 14.1 step 3): null until branches carry their PIN codes and products (Branch hierarchy). */
    Boolean served(LoanApplication a, Product p) { return geography == null ? null : geography.served(a.getPincode(), p.getCode()); }

    private com.rhythm.los.org.Geography geography;

    @org.springframework.beans.factory.annotation.Autowired
    void setGeography(com.rhythm.los.org.Geography g) { this.geography = g; }

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
            // a fraud review is closed only by the fraud analyst, never by a re-run of the engine
            return StateMachine.allowed(Domain.FRAUD, from, to) && !(from.equals(to) && !"CLEAR".equals(from))
                    && !("REVIEW".equals(from) && "CLEAR".equals(to));
        }
    }
}
