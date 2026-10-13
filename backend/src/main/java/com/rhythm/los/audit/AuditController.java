package com.rhythm.los.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditRepository repo;
    private final AuditService audit;

    public AuditController(AuditRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE','CRO','CREDIT_MANAGER')")
    public List<AuditEvent> list(@RequestParam(required = false) Long applicationId,
                                 @RequestParam(defaultValue = "200") int limit) {
        if (applicationId != null) return repo.findByApplicationIdOrderByIdDesc(applicationId);
        return repo.findAllByOrderByIdDesc(PageRequest.of(0, Math.min(limit, 1000)));
    }

    /**
     * Audit export (CSV) for a date range, optionally one action prefix or one application. The trail is append-only in
     * the database (a trigger rejects updates and deletes), and every export is itself recorded.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE','CRO')")
    public org.springframework.http.ResponseEntity<String> export(@RequestParam String from, @RequestParam String to,
                                                                 @RequestParam(required = false) String action,
                                                                 @RequestParam(required = false) Long applicationId) {
        java.time.LocalDate f, t;
        try { f = java.time.LocalDate.parse(from); t = java.time.LocalDate.parse(to); }
        catch (Exception e) { throw com.rhythm.los.common.ApiException.unprocessable("BAD_DATE", "Dates must be YYYY-MM-DD"); }
        if (t.isBefore(f)) throw com.rhythm.los.common.ApiException.unprocessable("BAD_RANGE", "The end date is before the start date");
        if (f.plusDays(366).isBefore(t)) throw com.rhythm.los.common.ApiException.unprocessable("RANGE_TOO_LONG", "Export at most one year at a time");
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        List<AuditEvent> rows = repo.findByCreatedAtBetweenOrderByIdAsc(f.atStartOfDay(ist).toInstant(), t.plusDays(1).atStartOfDay(ist).toInstant());
        StringBuilder sb = new StringBuilder("id,time,actor,role,action,entity_type,entity_id,application_id,details\n");
        int n = 0;
        for (AuditEvent e : rows) {
            if (action != null && !action.isBlank() && !e.getAction().startsWith(action.trim())) continue;
            if (applicationId != null && !applicationId.equals(e.getApplicationId())) continue;
            sb.append(e.getId()).append(',').append(e.getCreatedAt()).append(',').append(csv(e.getActor())).append(',').append(csv(e.getActorRole()))
              .append(',').append(csv(e.getAction())).append(',').append(csv(e.getEntityType())).append(',').append(csv(e.getEntityId()))
              .append(',').append(e.getApplicationId() == null ? "" : e.getApplicationId()).append(',').append(csv(e.getDetails())).append('\n');
            n++;
        }
        audit.record(com.rhythm.los.security.CurrentUser.get(), "audit.exported", "AUDIT", from + ".." + to, applicationId,
                n + " events" + (action == null || action.isBlank() ? "" : " · action " + action));
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"rhythm-audit-" + from + "-to-" + to + ".csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(sb.toString());
    }

    /** CSV cell: quoted, quotes doubled, and a leading formula character neutralised (CSV injection). */
    static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ");
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v + "\"";
    }
}
