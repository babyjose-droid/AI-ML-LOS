package com.rhythm.los.tenant;

import com.rhythm.los.flow.WorkflowService;
import com.rhythm.los.masters.MastersController;
import com.rhythm.los.policy.PolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background jobs run once per tenant, each in its own schema. */
@Component
public class TenantJobs {
    private static final Logger log = LoggerFactory.getLogger(TenantJobs.class);
    private final TenantRegistry registry;
    private final WorkflowService workflow;
    private final PolicyService policy;
    private final MastersController masters;
    private final BenchmarkService benchmarks;

    public TenantJobs(TenantRegistry registry, WorkflowService workflow, PolicyService policy, MastersController masters, BenchmarkService benchmarks) {
        this.registry = registry;
        this.workflow = workflow;
        this.policy = policy;
        this.masters = masters;
        this.benchmarks = benchmarks;
    }

    void each(String job, Runnable r) {
        for (TenantRegistry.Entry e : registry.active()) {
            try { TenantContext.run(e.tenant(), r); } catch (RuntimeException x) { log.warn("{} failed for tenant {}: {}", job, e.code(), x.getMessage()); }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void loadLimits() {
        for (TenantRegistry.Entry e : registry.active()) if (!"default".equals(e.code())) TenantContext.run(e.tenant(), masters::loadLimits);
    }

    @Scheduled(fixedDelayString = "${rhythm.workflow.sla-check-ms:60000}", initialDelayString = "${rhythm.workflow.sla-initial-delay-ms:60000}")
    public void slaSweep() { each("SLA sweep", workflow::slaSweep); }

    @Scheduled(fixedDelayString = "${rhythm.policy.activation-check-ms:60000}", initialDelayString = "${rhythm.policy.activation-initial-delay-ms:5000}")
    public void policyActivation() { each("Policy activation", policy::activateDue); }

    @Scheduled(cron = "0 40 2 * * *", zone = "Asia/Kolkata")
    public void benchmarks() { benchmarks.computeAll(); }
}
