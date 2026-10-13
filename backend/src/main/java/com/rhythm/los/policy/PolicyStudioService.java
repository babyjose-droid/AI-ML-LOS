package com.rhythm.los.policy;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.org.Role;
import com.rhythm.los.portfolio.HistoricalLoan;
import com.rhythm.los.portfolio.HistoricalLoanRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Policy studio: draft a change to the credit policy, simulate it on past loans and recent applications, then send it
 * for approval. A different person (the CRO) approves it, and only then does it become the live policy. Every step is
 * in the audit trail.
 */
@Service
public class PolicyStudioService {
    static final Set<Role> MAKERS = EnumSet.of(Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN);
    static final Set<Role> CHECKERS = EnumSet.of(Role.CRO);
    private static final Pattern V = Pattern.compile("-v(\\d+)$");

    private final PolicyRepository repo;
    private final PolicyService policy;
    private final HistoricalLoanRepository book;
    private final DecisionRecordRepository decisions;
    private final AuditService audit;
    private final com.rhythm.los.model.ModelService models;

    public PolicyStudioService(PolicyRepository repo, PolicyService policy, HistoricalLoanRepository book, DecisionRecordRepository decisions, AuditService audit,
                               com.rhythm.los.model.ModelService models) {
        this.models = models;
        this.repo = repo;
        this.policy = policy;
        this.book = book;
        this.decisions = decisions;
        this.audit = audit;
    }

    public PolicyVersion get(Long id) { return repo.findById(id).orElseThrow(() -> ApiException.notFound("Policy version")); }

    public PolicyParams params(PolicyVersion v) { return Json.read(v.getParamsJson(), PolicyParams.class); }

    public PolicyVersion live() {
        return repo.findFirstByStatusOrderByIdDesc("LIVE").orElseThrow(() -> ApiException.conflict("NO_LIVE_POLICY", "No live policy"));
    }

    @Transactional
    public PolicyVersion createDraft(String notes, CurrentUser by) {
        requireMaker(by);
        repo.findAllByOrderByIdDesc().stream().filter(v -> Set.of("DRAFT", "PENDING_APPROVAL").contains(v.getStatus())).findFirst().ifPresent(v -> {
            throw ApiException.conflict("DRAFT_EXISTS", "Policy " + v.getVersion() + " is already " + v.getStatus().toLowerCase().replace('_', ' ') + "; finish or discard it first");
        });
        PolicyVersion base = live();
        String version = nextVersion();
        PolicyParams p = withVersion(params(base), version);
        PolicyVersion d = new PolicyVersion();
        d.setVersion(version);
        d.setStatus("DRAFT");
        d.setParamsJson(Json.write(p));
        d.setNotes(notes == null || notes.isBlank() ? "Draft based on " + base.getVersion() : notes.trim());
        d.setCreatedBy(by.username());
        d.setBasedOn(base.getVersion());
        repo.save(d);
        audit.record(by, "policy.draft_created", "POLICY", version, null, "Based on " + base.getVersion());
        return d;
    }

    @Transactional
    public PolicyVersion update(Long id, Map<String, Object> changes, String notes, CurrentUser by) {
        PolicyVersion d = get(id);
        requireDraftOwner(d, by);
        Map<String, Object> m = PolicyFields.asMap(params(d));
        Set<String> keys = new HashSet<>();
        PolicyFields.ALL.forEach(f -> keys.add(f.key()));
        if (changes != null) for (var e : changes.entrySet()) {
            if (!keys.contains(e.getKey())) throw ApiException.unprocessable("UNKNOWN_FIELD", "Unknown policy parameter " + e.getKey());
            if (!(e.getValue() instanceof Number)) throw ApiException.unprocessable("NOT_A_NUMBER", e.getKey() + " must be a number");
            m.put(e.getKey(), e.getValue());
        }
        PolicyParams p = Json.MAPPER.convertValue(m, PolicyParams.class);
        List<String> errors = PolicyFields.validate(p);
        if (!errors.isEmpty()) throw ApiException.unprocessable("INVALID_POLICY", String.join("; ", errors));
        d.setParamsJson(Json.write(p));
        if (notes != null && !notes.isBlank()) d.setNotes(notes.trim().length() > 500 ? notes.trim().substring(0, 500) : notes.trim());
        d.setSimulationJson(null); // parameters changed: the old simulation no longer applies
        repo.save(d);
        return d;
    }

    @Transactional
    public Map<String, Object> simulate(Long id, CurrentUser by) {
        PolicyVersion d = get(id);
        if (!Set.of("DRAFT", "PENDING_APPROVAL").contains(d.getStatus())) throw ApiException.conflict("NOT_A_DRAFT", "Only drafts are simulated");
        Map<String, Object> result = simulation(params(live()), params(d));
        result.put("simulatedAt", Instant.now().toString());
        result.put("simulatedBy", by.username());
        if ("DRAFT".equals(d.getStatus())) {
            d.setSimulationJson(Json.write(result));
            repo.save(d);
        }
        return result;
    }

    @Transactional
    public PolicyVersion submit(Long id, CurrentUser by) {
        PolicyVersion d = get(id);
        requireDraftOwner(d, by);
        if (PolicyFields.diff(params(live()), params(d)).isEmpty()) throw ApiException.unprocessable("NO_CHANGES", "The draft is the same as the live policy");
        if (d.getSimulationJson() == null) throw ApiException.unprocessable("SIMULATE_FIRST", "Run the simulation on the latest parameters before sending for approval");
        d.setStatus("PENDING_APPROVAL");
        d.setSubmittedAt(Instant.now());
        repo.save(d);
        audit.record(by, "policy.submitted", "POLICY", d.getVersion(), null, changeText(d));
        return d;
    }

    public record Decision(boolean approve, String note) {}

    @Transactional
    public PolicyVersion decide(Long id, Decision req, CurrentUser by) {
        PolicyVersion d = get(id);
        if (!CHECKERS.contains(by.role())) throw ApiException.forbidden("NOT_CHECKER", "Only the CRO can approve a credit policy");
        if (!"PENDING_APPROVAL".equals(d.getStatus())) throw ApiException.conflict("NOT_PENDING", "Policy is " + d.getStatus());
        if (by.username().equals(d.getCreatedBy())) throw ApiException.forbidden("MAKER_CHECKER", "The person who drafted a policy cannot approve it");
        String note = req.note() == null ? "" : req.note().trim();
        if (note.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        d.setChecker(by.username());
        d.setDecidedAt(Instant.now());
        d.setDecisionNote(note.length() > 1000 ? note.substring(0, 1000) : note);
        if (req.approve()) {
            PolicyVersion old = live();
            old.setStatus("RETIRED");
            old.setRetiredAt(Instant.now());
            repo.save(old);
            d.setStatus("LIVE");
            d.setLiveFrom(Instant.now());
            audit.record(by, "policy.approved_live", "POLICY", d.getVersion(), null, "Replaces " + old.getVersion() + " · " + changeText(d) + " · " + note);
        } else {
            d.setStatus("DRAFT");
            d.setSubmittedAt(null);
            audit.record(by, "policy.sent_back", "POLICY", d.getVersion(), null, note);
        }
        repo.save(d);
        return d;
    }

    @Transactional
    public void discard(Long id, CurrentUser by) {
        PolicyVersion d = get(id);
        requireDraftOwner(d, by);
        d.setStatus("REJECTED");
        d.setDecidedAt(Instant.now());
        d.setDecisionNote("Discarded by " + by.username());
        repo.save(d);
        audit.record(by, "policy.discarded", "POLICY", d.getVersion(), null, "Draft discarded");
    }

    public List<Map<String, Object>> changes(PolicyVersion v) {
        PolicyParams base = v.getBasedOn() == null ? params(v) : repo.findAllByOrderByIdDesc().stream()
                .filter(x -> x.getVersion().equals(v.getBasedOn())).findFirst().map(this::params).orElse(params(v));
        return PolicyFields.diff(base, params(v));
    }

    // ---------------------------------------------------------------- simulation

    /** Runs the decision engine with the live and the draft policy on the loan book and on recent applications. */
    public Map<String, Object> simulation(PolicyParams live, PolicyParams draft) {
        com.rhythm.los.decision.engine.PdModel pd = models.champion().orElse(null);
        Agg hl = new Agg(), hd = new Agg(), rl = new Agg(), rd = new Agg();
        int swapIn = 0, swapInBad = 0, swapOut = 0, swapOutBad = 0;
        Map<String, int[]> ruleHits = new TreeMap<>();
        Map<String, Map<String, Integer>> segments = new TreeMap<>();
        for (HistoricalLoan h : book.findAllByOrderByIdAsc()) {
            EngineInput in = Json.read(h.getInputJson(), EngineInput.class);
            EngineResult a = UnderwritingEngine.underwrite(in, live, pd), b = UnderwritingEngine.underwrite(in, draft, pd);
            hl.add(a, in, live, h.isOutcomeBad());
            hd.add(b, in, draft, h.isOutcomeBad());
            boolean ia = approved(a), ib = approved(b);
            if (!ia && ib) { swapIn++; if (h.isOutcomeBad()) swapInBad++; }
            if (ia && !ib) { swapOut++; if (h.isOutcomeBad()) swapOutBad++; }
            hits(ruleHits, a, 0);
            hits(ruleHits, b, 1);
            Map<String, Integer> s = segments.computeIfAbsent(h.getSegment(), k -> new TreeMap<>());
            s.merge("loans", 1, Integer::sum);
            if (ia) s.merge("liveApproved", 1, Integer::sum);
            if (ib) s.merge("draftApproved", 1, Integer::sum);
            if (ia && h.isOutcomeBad()) s.merge("liveBad", 1, Integer::sum);
            if (ib && h.isOutcomeBad()) s.merge("draftBad", 1, Integer::sum);
        }
        Map<Long, DecisionRecord> latest = new HashMap<>();
        for (DecisionRecord r : decisions.findAll()) latest.merge(r.getApplicationId(), r, (x, y) -> x.getId() > y.getId() ? x : y);
        List<Map<String, Object>> changed = new ArrayList<>();
        for (DecisionRecord r : latest.values().stream().sorted(Comparator.comparing(DecisionRecord::getId).reversed()).toList()) {
            EngineInput in;
            try { in = Json.read(r.getInputJson(), EngineInput.class); } catch (Exception e) { continue; }
            EngineResult a = UnderwritingEngine.underwrite(in, live, pd), b = UnderwritingEngine.underwrite(in, draft, pd);
            rl.add(a, in, live, null);
            rd.add(b, in, draft, null);
            if (!a.decision().equals(b.decision()) || Math.abs(a.recAmt() - b.recAmt()) >= 1 || Math.abs(a.rate() - b.rate()) >= 0.01) {
                if (changed.size() < 50) changed.add(Map.of("applicationId", r.getApplicationId(), "appNo", in.id(), "name", in.name(),
                        "live", a.decision(), "draft", b.decision(), "liveAmount", a.recAmt(), "draftAmount", b.recAmt(),
                        "liveRate", a.rate(), "draftRate", b.rate(), "pd", a.pd()));
            }
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        ruleHits.forEach((k, v) -> rules.add(Map.of("rule", k, "label", RULE_LABEL.getOrDefault(k, k), "live", v[0], "draft", v[1])));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("liveVersion", live.version());
        out.put("draftVersion", draft.version());
        out.put("changes", PolicyFields.diff(live, draft));
        out.put("book", Map.of("loans", hl.n, "source", bookSource(), "live", hl.view(), "draft", hd.view()));
        out.put("swaps", Map.of("swapIn", swapIn, "swapInBadRate", rate(swapInBad, swapIn), "swapOut", swapOut, "swapOutBadRate", rate(swapOutBad, swapOut)));
        out.put("segments", segments);
        out.put("recent", Map.of("applications", rl.n, "live", rl.view(), "draft", rd.view(), "changed", changed));
        out.put("ruleHits", rules);
        return out;
    }

    static final Map<String, String> RULE_LABEL = Map.ofEntries(
            Map.entry("R01", "Age limits"), Map.entry("R02", "KYC match: decline"), Map.entry("R03", "KYC match: review"),
            Map.entry("R04", "Fraud score: decline"), Map.entry("R05", "Fraud score: refer"), Map.entry("R06", "Months of bank data"),
            Map.entry("R07", "Recent serious delinquency"), Map.entry("R08", "Bureau enquiries"), Map.entry("R09", "PD above decline band"),
            Map.entry("R10", "PD above auto-approve band"), Map.entry("R11", "No EMI capacity"), Map.entry("R12", "EMI above capacity (counter-offer)"),
            Map.entry("R13", "Above auto-approval limit"), Map.entry("R14", "Betting in bank statement"), Map.entry("R15", "Employer mismatch"));

    private String bookSource() {
        long synth = book.countBySource("SYNTHETIC"), imported = book.countBySource("IMPORTED");
        return imported > 0 && synth > 0 ? "MIXED" : imported > 0 ? "IMPORTED" : synth > 0 ? "SYNTHETIC" : "NONE";
    }

    private static void hits(Map<String, int[]> m, EngineResult r, int idx) {
        for (EngineResult.RuleResult x : r.rules()) if (!x.pass()) m.computeIfAbsent(x.id(), k -> new int[2])[idx]++;
    }

    static boolean approved(EngineResult r) { return r.decision().startsWith("APPROVE"); }

    private static double rate(int a, int b) { return b == 0 ? 0 : Math.round(10000.0 * a / b) / 10000.0; }

    /** Aggregates engine results for one policy. Bad rate only where outcomes are known (the loan book). */
    static final class Agg {
        int n, approve, conditional, refer, reject, bad, withOutcome;
        double amount, expectedLoss, pdSum;

        void add(EngineResult r, EngineInput in, PolicyParams p, Boolean isBad) {
            n++;
            switch (r.decision()) {
                case "APPROVE" -> approve++;
                case "APPROVE_WITH_CONDITIONS" -> conditional++;
                case "REFER" -> refer++;
                default -> reject++;
            }
            if (approved(r)) {
                amount += r.recAmt();
                expectedLoss += r.pd() * (in.secured() ? p.lgdSecured() : p.lgdUnsecured()) * r.recAmt();
                pdSum += r.pd();
                if (isBad != null) { withOutcome++; if (isBad) bad++; }
            }
        }

        Map<String, Object> view() {
            int appr = approve + conditional;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("approve", approve);
            m.put("conditional", conditional);
            m.put("refer", refer);
            m.put("reject", reject);
            m.put("approvalRate", rate(appr, n));
            m.put("referRate", rate(refer, n));
            m.put("rejectRate", rate(reject, n));
            m.put("straightThroughRate", rate(appr + reject, n));
            m.put("approvedAmount", Math.round(amount));
            m.put("expectedLoss", Math.round(expectedLoss));
            m.put("expectedLossRate", amount == 0 ? 0 : Math.round(10000 * expectedLoss / amount) / 10000.0);
            m.put("avgPdApproved", appr == 0 ? 0 : Math.round(10000 * pdSum / appr) / 10000.0);
            if (withOutcome > 0) m.put("badRateApproved", rate(bad, withOutcome));
            return m;
        }
    }

    // ---------------------------------------------------------------- helpers

    private String changeText(PolicyVersion d) {
        List<Map<String, Object>> c = changes(d);
        if (c.isEmpty()) return "no parameter changes";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> x : c) sb.append(sb.isEmpty() ? "" : "; ").append(x.get("label")).append(" ").append(x.get("from")).append(" → ").append(x.get("to"));
        return sb.toString();
    }

    private void requireMaker(CurrentUser by) {
        if (!MAKERS.contains(by.role())) throw ApiException.forbidden("NOT_MAKER", "Only credit managers and the CRO can draft credit policy");
    }

    private void requireDraftOwner(PolicyVersion d, CurrentUser by) {
        requireMaker(by);
        if (!"DRAFT".equals(d.getStatus())) throw ApiException.conflict("NOT_A_DRAFT", "Policy " + d.getVersion() + " is " + d.getStatus() + " and cannot be changed");
        if (!by.username().equals(d.getCreatedBy())) throw ApiException.forbidden("NOT_YOUR_DRAFT", "Only " + d.getCreatedBy() + " can change this draft");
    }

    private String nextVersion() {
        int max = 0;
        for (PolicyVersion v : repo.findAllByOrderByIdDesc()) {
            Matcher m = V.matcher(v.getVersion());
            if (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        YearMonth ym = YearMonth.now();
        return String.format("POL-%d.%02d-v%d", ym.getYear(), ym.getMonthValue(), max + 1);
    }

    static PolicyParams withVersion(PolicyParams p, String version) {
        Map<String, Object> m = PolicyFields.asMap(p);
        m.put("version", version);
        return Json.MAPPER.convertValue(m, PolicyParams.class);
    }
}
