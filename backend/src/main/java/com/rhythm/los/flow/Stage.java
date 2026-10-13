package com.rhythm.los.flow;

import java.util.List;

/**
 * One stage of a product workflow, as configured in the workflow designer.
 * mode: AUTO (system completes it), AUTO_REVIEW (system, then a person confirms), MANUAL (a person completes it).
 * onFailure: what happens when the stage fails or its SLA is breached: QUEUE, ESCALATE, REJECT, PAUSE.
 * skip: optional condition; when true the stage is skipped, e.g. "segment = SALARIED AND amount <= 500000".
 */
public record Stage(String key, String name, String role, int sla, String mode, String onFailure, String skip, List<String> integrations) {}
