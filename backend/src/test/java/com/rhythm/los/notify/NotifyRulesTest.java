package com.rhythm.los.notify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyRulesTest {
    @Test
    void firstSweepKeepsOpenTasksQuietly() {
        var p = NotifyRules.plan(List.of("process:1", "sanction:2"), List.of(), true);
        assertThat(p.announce()).isEmpty();
        assertThat(p.silent()).containsExactly("process:1", "sanction:2");
        assertThat(p.drop()).isEmpty();
    }

    @Test
    void newTasksAreAnnouncedAndFinishedOnesDropped() {
        var p = NotifyRules.plan(List.of("sanction:2", "disburse:3"), List.of("process:1", "sanction:2"), false);
        assertThat(p.announce()).containsExactly("disburse:3");
        assertThat(p.silent()).isEmpty();
        assertThat(p.drop()).containsExactly("process:1");
    }

    @Test
    void aTaskThatComesBackIsAnnouncedAgain() {
        var gone = NotifyRules.plan(List.of(), List.of("runDecision:5"), false);
        assertThat(gone.drop()).containsExactly("runDecision:5");
        var back = NotifyRules.plan(List.of("runDecision:5"), List.of(), false);
        assertThat(back.announce()).containsExactly("runDecision:5");
    }

    @Test
    void keysUseTheApplicationOrTheReference() {
        assertThat(NotifyRules.key("process", 7L, "APP-7")).isEqualTo("process:7");
        assertThat(NotifyRules.key("policy", null, "v3")).isEqualTo("policy:v3");
        assertThat(NotifyRules.key("integrations", null, null)).isEqualTo("integrations:-");
    }
}
