package com.rhythm.los.masters;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.decision.Delegation;
import com.rhythm.los.org.BranchRepository;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Master lookups (SCR-123): holiday calendar; roles and permissions with data scope and sanction limits (SCR-122). */
@RestController
public class MastersController {
    private final HolidayRepository holidays;
    private final SanctionLimitRepository limits;
    private final BranchRepository branches;
    private final AuditService audit;

    public MastersController(HolidayRepository holidays, SanctionLimitRepository limits, BranchRepository branches, AuditService audit) {
        this.holidays = holidays;
        this.limits = limits;
        this.branches = branches;
        this.audit = audit;
    }

    // ---- holidays ----

    @GetMapping("/api/masters/holidays")
    public List<Holiday> holidays() { return holidays.findAllByOrderByDateAsc(); }

    public record HolidayRequest(String date, String name, Long branchId) {}

    @PostMapping("/api/admin/holidays")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Holiday addHoliday(@RequestBody HolidayRequest r) {
        LocalDate d;
        try { d = LocalDate.parse(r.date()); } catch (Exception e) { throw ApiException.unprocessable("BAD_DATE", "Date must be YYYY-MM-DD"); }
        if (r.name() == null || r.name().trim().length() < 3) throw ApiException.unprocessable("NAME_REQUIRED", "Give the holiday a name");
        if (r.branchId() != null && !branches.existsById(r.branchId())) throw ApiException.notFound("Branch");
        boolean dup = r.branchId() == null ? holidays.existsByDateAndBranchIdIsNull(d) : holidays.existsByDateAndBranchId(d, r.branchId());
        if (dup) throw ApiException.conflict("DUPLICATE_HOLIDAY", "There is already a holiday on " + d);
        Holiday h = new Holiday();
        h.setDate(d);
        h.setName(r.name().trim());
        h.setBranchId(r.branchId());
        h.setCreatedBy(CurrentUser.get().username());
        holidays.save(h);
        audit.record(CurrentUser.get(), "master.holiday_added", "HOLIDAY", d.toString(), null, h.getName() + (r.branchId() == null ? " · all branches" : " · branch " + r.branchId()));
        return h;
    }

    @DeleteMapping("/api/admin/holidays/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<Void> removeHoliday(@PathVariable Long id) {
        Holiday h = holidays.findById(id).orElseThrow(() -> ApiException.notFound("Holiday"));
        holidays.delete(h);
        audit.record(CurrentUser.get(), "master.holiday_removed", "HOLIDAY", h.getDate().toString(), null, h.getName());
        return ResponseEntity.noContent().build();
    }

    // ---- roles, permissions and sanction limits ----

    @EventListener(ApplicationReadyEvent.class)
    public void loadLimits() { Delegation.use(matrix(limits.findAll())); }

    static Delegation.Matrix matrix(List<SanctionLimit> rows) {
        Map<String, SanctionLimit> m = new HashMap<>();
        rows.forEach(r -> m.put(r.getLevel(), r));
        SanctionLimit l1 = m.get("L1"), l2 = m.get("L2");
        if (l1 == null || l2 == null) return Delegation.Matrix.DEFAULT;
        return new Delegation.Matrix(l1.getMaxAmount() == null ? null : l1.getMaxAmount().doubleValue(),
                l2.getMaxAmount() == null ? null : l2.getMaxAmount().doubleValue(), l1.getMaxBand(), l2.getMaxBand(), l1.getMaxDeviations(), l2.getMaxDeviations());
    }

    /** What each role can do, the data it sees and its sanction authority. Generated from the access rules in code. */
    static final Map<Role, List<String>> CAPABILITIES = new LinkedHashMap<>();
    static final Map<Role, String> SCOPE = new EnumMap<>(Role.class);
    static {
        CAPABILITIES.put(Role.SALES, List.of("Create and edit applications", "Upload documents", "Record and withdraw borrower consent", "Borrower accepts KFS", "Stages the workflow assigns to Sales"));
        CAPABILITIES.put(Role.OPERATIONS, List.of("Create and edit applications", "Run automated checks", "KYC review and video KYC", "Document review", "Disbursement", "Integration log and retries", "Stages the workflow assigns to Operations"));
        CAPABILITIES.put(Role.FIELD_OFFICER, List.of("Field visits", "Group formation (CGT/GRT)", "Vehicle valuation"));
        CAPABILITIES.put(Role.CREDIT_OFFICER, List.of("Run the decision engine", "Edit and sign the credit memo", "Sanction within L1 limits", "Review queue"));
        CAPABILITIES.put(Role.CREDIT_MANAGER, List.of("Everything a credit officer does", "Sanction within L2 limits", "Draft credit policy and products", "Risk models"));
        CAPABILITIES.put(Role.CRO, List.of("Sanction any case (L3)", "Approve policy, product, workflow and model changes (checker)", "Policy rollback"));
        CAPABILITIES.put(Role.FRAUD_ANALYST, List.of("Fraud referrals: clear or confirm"));
        CAPABILITIES.put(Role.COMPLIANCE, List.of("Read-only: applications, audit trail and integration logs", "Audit export"));
        CAPABILITIES.put(Role.ADMIN, List.of("Users, branches, masters, products, workflows, connectors", "Every action (for support)"));
        for (Role r : Role.values()) SCOPE.put(r, "Own branch and the units under it; head office sees all");
        SCOPE.put(Role.BORROWER, "Own applications only");
    }

    @GetMapping("/api/masters/roles")
    public Map<String, Object> roles() {
        List<Map<String, Object>> rs = new ArrayList<>();
        for (var e : CAPABILITIES.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", e.getKey().name());
            m.put("can", e.getValue());
            m.put("dataScope", SCOPE.get(e.getKey()));
            m.put("sanctionLevel", e.getKey().sanctionLevel() == 0 ? null : "L" + e.getKey().sanctionLevel());
            rs.add(m);
        }
        List<SanctionLimit> ls = new ArrayList<>(limits.findAll());
        ls.sort(Comparator.comparing(SanctionLimit::getLevel));
        return Map.of("roles", rs, "limits", ls);
    }

    public record LimitRequest(BigDecimal maxAmount, String maxBand, Integer maxDeviations, String note) {}

    @PutMapping("/api/admin/sanction-limits/{level}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public SanctionLimit updateLimit(@PathVariable String level, @RequestBody LimitRequest r) {
        SanctionLimit l = limits.findById(level).orElseThrow(() -> ApiException.notFound("Sanction level " + level));
        if (r.note() == null || r.note().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Record the board approval or reason (at least 10 characters)");
        if ("L3".equals(level) && r.maxAmount() != null) throw ApiException.unprocessable("TOP_LEVEL", "L3 has no amount limit");
        if (r.maxBand() != null && !r.maxBand().matches("[A-E]")) throw ApiException.unprocessable("BAD_BAND", "Band must be A to E");
        if (r.maxDeviations() != null && (r.maxDeviations() < 0 || r.maxDeviations() > 99)) throw ApiException.unprocessable("BAD_DEVIATIONS", "Deviations must be 0 to 99");
        if (r.maxAmount() != null && r.maxAmount().signum() <= 0) throw ApiException.unprocessable("BAD_AMOUNT", "Amount must be positive");
        String before = l.getMaxAmount() + "/" + l.getMaxBand() + "/" + l.getMaxDeviations();
        if (!"L3".equals(level)) l.setMaxAmount(r.maxAmount() == null ? l.getMaxAmount() : r.maxAmount());
        if (r.maxBand() != null) l.setMaxBand(r.maxBand());
        if (r.maxDeviations() != null) l.setMaxDeviations(r.maxDeviations());
        List<SanctionLimit> all = new ArrayList<>(limits.findAll());
        all.replaceAll(x -> x.getLevel().equals(level) ? l : x);
        Delegation.Matrix m = matrix(all);
        if (m.l1Max() != null && m.l2Max() != null && m.l1Max() >= m.l2Max()) throw ApiException.unprocessable("INCONSISTENT", "L1 amount must be below the L2 amount");
        if (m.l1Band().compareTo(m.l2Band()) > 0) throw ApiException.unprocessable("INCONSISTENT", "L1 cannot approve worse bands than L2");
        if (m.l1Deviations() > m.l2Deviations()) throw ApiException.unprocessable("INCONSISTENT", "L1 cannot allow more deviations than L2");
        l.setUpdatedBy(CurrentUser.get().username());
        l.setUpdatedAt(Instant.now());
        limits.save(l);
        Delegation.use(m);
        audit.record(CurrentUser.get(), "master.sanction_limit_changed", "SANCTION_LIMIT", level, null,
                before + " → " + l.getMaxAmount() + "/" + l.getMaxBand() + "/" + l.getMaxDeviations() + " · " + r.note().trim());
        return l;
    }
}
