package com.rhythm.los.application;

import java.util.*;

/**
 * Allowed transitions per domain. Anything not listed is rejected with INVALID_TRANSITION
 * and never reaches the database.
 */
public final class StateMachine {
    private static final Map<Domain, Map<String, Set<String>>> T = new EnumMap<>(Domain.class);
    private static final Map<Domain, String> INITIAL = new EnumMap<>(Domain.class);

    static {
        init(Domain.APP, "DRAFT");
        allow(Domain.APP, "DRAFT", "SUBMITTED", "WITHDRAWN", "REJECTED");
        allow(Domain.APP, "SUBMITTED", "UNDERWRITING", "WITHDRAWN", "REJECTED");
        allow(Domain.APP, "UNDERWRITING", "SANCTIONED", "REJECTED");
        allow(Domain.APP, "SANCTIONED", "DISBURSED", "REJECTED");

        init(Domain.KYC, "NOT_STARTED");
        allow(Domain.KYC, "NOT_STARTED", "VERIFIED", "REVIEW", "FAILED");
        allow(Domain.KYC, "REVIEW", "VERIFIED", "FAILED");

        init(Domain.DATA, "NOT_FETCHED");
        allow(Domain.DATA, "NOT_FETCHED", "FETCHED", "PARTIAL", "FAILED");
        allow(Domain.DATA, "PARTIAL", "FETCHED", "FAILED");
        allow(Domain.DATA, "FAILED", "FETCHED", "PARTIAL");

        init(Domain.DOCS, "PENDING");
        allow(Domain.DOCS, "PENDING", "COMPLETE", "DEFICIENT");
        allow(Domain.DOCS, "DEFICIENT", "COMPLETE");

        init(Domain.FIELD, "NOT_EVALUATED");
        allow(Domain.FIELD, "NOT_EVALUATED", "NOT_REQUIRED", "REQUIRED");
        allow(Domain.FIELD, "REQUIRED", "DONE");

        init(Domain.DECISION, "PENDING");
        allow(Domain.DECISION, "PENDING", "APPROVE", "APPROVE_WITH_CONDITIONS", "REFER", "REJECT");
        allow(Domain.DECISION, "APPROVE", "PENDING");
        allow(Domain.DECISION, "APPROVE_WITH_CONDITIONS", "PENDING");
        allow(Domain.DECISION, "REFER", "PENDING");

        init(Domain.FRAUD, "NOT_CHECKED");
        allow(Domain.FRAUD, "NOT_CHECKED", "CLEAR", "REVIEW", "BLOCK");
        allow(Domain.FRAUD, "CLEAR", "CLEAR", "REVIEW", "BLOCK");
        allow(Domain.FRAUD, "REVIEW", "CLEAR", "BLOCK");

        init(Domain.SANCTION, "NOT_STARTED");
        allow(Domain.SANCTION, "NOT_STARTED", "PENDING_L1", "PENDING_L2", "PENDING_L3");
        allow(Domain.SANCTION, "PENDING_L1", "PENDING_L2", "SANCTIONED", "DECLINED", "NOT_STARTED");
        allow(Domain.SANCTION, "PENDING_L2", "PENDING_L3", "SANCTIONED", "DECLINED", "NOT_STARTED");
        allow(Domain.SANCTION, "PENDING_L3", "SANCTIONED", "DECLINED", "NOT_STARTED");
        allow(Domain.SANCTION, "SANCTIONED", "KFS_ACCEPTED");

        init(Domain.DISB, "NOT_STARTED");
        allow(Domain.DISB, "NOT_STARTED", "READY");
        allow(Domain.DISB, "READY", "DISBURSED", "FAILED");
        allow(Domain.DISB, "FAILED", "READY");
    }

    private StateMachine() {}

    private static void init(Domain d, String s) {
        INITIAL.put(d, s);
        T.put(d, new HashMap<>());
    }

    private static void allow(Domain d, String from, String... to) {
        T.get(d).computeIfAbsent(from, k -> new LinkedHashSet<>()).addAll(Arrays.asList(to));
    }

    public static String initial(Domain d) { return INITIAL.get(d); }

    public static boolean allowed(Domain d, String from, String to) {
        return T.get(d).getOrDefault(from, Set.of()).contains(to);
    }

    /** Full transition table, for the state-machine page and documentation. */
    public static Map<Domain, Map<String, Set<String>>> table() {
        Map<Domain, Map<String, Set<String>>> copy = new EnumMap<>(Domain.class);
        T.forEach((d, m) -> copy.put(d, Collections.unmodifiableMap(m)));
        return copy;
    }
}
