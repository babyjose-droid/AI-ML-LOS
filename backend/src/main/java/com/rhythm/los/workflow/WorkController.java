package com.rhythm.los.workflow;

import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.application.StateMachine;
import com.rhythm.los.integration.DataFetchService;
import com.rhythm.los.integration.IntegrationLog;
import com.rhythm.los.integration.IntegrationLogRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class WorkController {
    private final MyWorkService work;
    private final LoanApplicationRepository apps;
    private final IntegrationLogRepository logs;
    private final DataFetchService data;

    public WorkController(MyWorkService work, LoanApplicationRepository apps, IntegrationLogRepository logs, DataFetchService data) {
        this.work = work;
        this.apps = apps;
        this.logs = logs;
        this.data = data;
    }

    @GetMapping("/my-work")
    public List<MyWorkService.Task> myWork() { return work.tasks(CurrentUser.get()); }

    @GetMapping("/pipeline/summary")
    public Map<String, Object> summary() {
        List<LoanApplication> all = apps.findAll();
        Map<String, Long> byState = new LinkedHashMap<>();
        for (String s : List.of("DRAFT", "SUBMITTED", "UNDERWRITING", "SANCTIONED", "DISBURSED", "REJECTED", "WITHDRAWN"))
            byState.put(s, all.stream().filter(a -> s.equals(a.getAppState())).count());
        double disbursed = all.stream().filter(a -> "DISBURSED".equals(a.getAppState())).mapToDouble(a -> a.getLoanAmount().doubleValue()).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("byState", byState);
        m.put("pendingSanction", all.stream().filter(a -> a.getSanctionState().startsWith("PENDING_")).count());
        m.put("fraudReview", all.stream().filter(a -> "REVIEW".equals(a.getFraudState())).count());
        m.put("disbursedAmount", disbursed);
        m.put("dlq", logs.countByStatus("DLQ"));
        return m;
    }

    @GetMapping("/state-machine")
    public Map<Domain, Map<String, Set<String>>> stateMachine() { return StateMachine.table(); }

    @GetMapping("/integrations/logs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','COMPLIANCE')")
    public List<IntegrationLog> integrationLogs(@RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) return logs.findByStatusOrderByIdDesc(status);
        return logs.findAllByOrderByIdDesc(PageRequest.of(0, 300));
    }

    @PostMapping("/integrations/logs/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public LoanApplication retry(@PathVariable Long id) { return data.retry(id, CurrentUser.get()); }
}
