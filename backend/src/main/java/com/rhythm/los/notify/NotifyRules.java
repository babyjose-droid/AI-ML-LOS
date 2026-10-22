package com.rhythm.los.notify;

import java.util.*;

/** Pure: which alerts to add and which to drop, given the officer's current tasks and the alerts already kept. */
public final class NotifyRules {
    private NotifyRules() {}

    public record Plan(List<String> announce, List<String> silent, List<String> drop) {}

    /**
     * New tasks are announced (unread, pushed); on the officer's first sweep the tasks already open are kept as read so
     * the phone is not flooded. Alerts whose task has gone (done or moved on) are dropped, so a task that comes back
     * (for example a case sent back) is announced again.
     */
    public static Plan plan(Collection<String> current, Collection<String> kept, boolean firstSweep) {
        Set<String> cur = new LinkedHashSet<>(current), have = new HashSet<>(kept);
        List<String> add = cur.stream().filter(k -> !have.contains(k)).toList();
        List<String> drop = have.stream().filter(k -> !cur.contains(k)).sorted().toList();
        return firstSweep ? new Plan(List.of(), add, drop) : new Plan(add, List.of(), drop);
    }

    public static String key(String action, Long applicationId, String ref) { return action + ":" + (applicationId != null ? applicationId : ref == null ? "-" : ref); }
}
