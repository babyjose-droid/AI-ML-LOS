package com.rhythm.los.flow;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRulesTest {

    static List<Stage> plDefault() {
        return new ArrayList<>(WorkflowDefinitions.DEFAULT.stream().map(StageLibrary::defaults).toList());
    }

    @Test
    void skipRulesReadIndianAmountsAndBooleans() {
        Map<String, Object> f = new HashMap<>(Map.of("amount", 450000.0, "segment", "SALARIED", "incomeVerifiedViaAA", true, "existingCustomer", false));
        assertThat(Conditions.eval("segment = SALARIED AND amount <= 5,00,000 AND incomeVerifiedViaAA = true", f)).isTrue();
        assertThat(Conditions.eval("amount <= 4L", f)).isFalse();
        assertThat(Conditions.eval("amount <= ₹4.5 lakh", f)).isTrue();
        assertThat(Conditions.eval("existingCustomer = true OR amount < 5L", f)).isTrue();
        assertThat(Conditions.eval("decision = APPROVE", f)).as("missing fact is false").isFalse();
        assertThat(Conditions.eval("", f)).isFalse();
        assertThat(Conditions.validate("amount <= banana")).contains("not a number");
        assertThat(Conditions.validate("colour = red")).contains("Unknown field");
        assertThat(Conditions.validate("amount")).contains("Cannot read");
        assertThat(Conditions.validate("decision = APPROVE AND amount <= 2L")).isNull();
    }

    @Test
    void defaultSequenceIsValid() {
        assertThat(WorkflowDesignService.validate(plDefault())).isEmpty();
    }

    @Test
    void validationEnforcesShapeOrderAndSettings() {
        List<Stage> s = plDefault();
        s.removeIf(x -> x.key().equals("kyc"));
        assertThat(WorkflowDesignService.validate(s)).anyMatch(e -> e.contains("Consent & KYC is mandatory"));

        s = plDefault();
        Collections.swap(s, 0, 1);
        assertThat(WorkflowDesignService.validate(s)).anyMatch(e -> e.contains("must start with Lead"));

        s = plDefault();
        int uw = 4, sanction = 6;
        Collections.swap(s, uw, sanction);
        assertThat(WorkflowDesignService.validate(s)).anyMatch(e -> e.contains("Underwriting must come before Sanction"));

        s = plDefault();
        Stage f = s.get(3);
        s.set(3, new Stage(f.key(), f.name(), "BORROWER", 0, "AUTO", "EXPLODE", "amount <=", f.integrations()));
        List<String> e = WorkflowDesignService.validate(s);
        assertThat(e).anyMatch(x -> x.contains("SLA must be between"))
                .anyMatch(x -> x.contains("mode must be one of MANUAL"))
                .anyMatch(x -> x.contains("choose a staff role"))
                .anyMatch(x -> x.contains("on failure must be one of"))
                .anyMatch(x -> x.contains("skip rule"));

        s = plDefault();
        Stage k = s.get(1);
        s.set(1, new Stage(k.key(), k.name(), k.role(), k.sla(), k.mode(), k.onFailure(), "amount < 1L", k.integrations()));
        assertThat(WorkflowDesignService.validate(s)).anyMatch(x -> x.contains("cannot have a skip rule"));

        s = plDefault();
        s.add(1, StageLibrary.defaults("grt"));
        s.add(1, StageLibrary.defaults("cgt"));
        assertThat(WorkflowDesignService.validate(s)).isEmpty();
        Collections.swap(s, 1, 2);
        assertThat(WorkflowDesignService.validate(s)).anyMatch(x -> x.contains("Group formation (CGT) must come before"));
        s.add(3, StageLibrary.defaults("kyc"));
        assertThat(WorkflowDesignService.validate(s)).anyMatch(x -> x.contains("appears more than once"));
    }

    @Test
    void diffDescribesChanges() {
        List<Stage> a = plDefault();
        List<Stage> b = plDefault();
        Stage f = b.get(3);
        b.set(3, new Stage(f.key(), f.name(), f.role(), 48, f.mode(), "ESCALATE", "amount <= 8L", f.integrations()));
        b.remove(5); // cam
        assertThat(WorkflowDesignService.diff(a, b)).contains("Removed Credit memo", "Field visit: SLA 24 h → 48 h", "Field visit: on failure QUEUE → ESCALATE")
                .anyMatch(x -> x.startsWith("Field visit: skip rule"));
        assertThat(StageLibrary.escalationRole("FIELD_OFFICER")).isEqualTo("CREDIT_OFFICER");
        assertThat(StageLibrary.escalationRole("CREDIT_MANAGER")).isEqualTo("CRO");
    }

    static Stage par(Stage s) { return new Stage(s.key(), s.name(), s.role(), s.sla(), s.mode(), s.onFailure(), s.skip(), s.integrations(), true); }

    @Test
    void parallelStagesFormGroupsButGatesStayAlone() {
        List<Stage> s = plDefault();
        int docs = s.indexOf(s.stream().filter(x -> x.key().equals("docs")).findFirst().orElseThrow());
        s.set(docs, par(s.get(docs)));                       // documents alongside KYC
        assertThat(WorkflowDesignService.validate(s)).isEmpty();
        assertThat(WorkflowDefinitions.parse(com.rhythm.los.common.Json.write(s)).get(docs).parallel()).isTrue();
        assertThat(WorkflowDefinitions.parse("[{\"key\":\"lead\",\"name\":\"Lead\",\"role\":\"SALES\",\"sla\":24,\"mode\":\"MANUAL\",\"onFailure\":\"QUEUE\",\"skip\":\"\",\"integrations\":[]}]")
                .get(0).parallel()).isFalse();

        List<Stage> bad = plDefault();
        bad.set(0, par(bad.get(0)));
        assertThat(WorkflowDesignService.validate(bad)).anyMatch(e -> e.contains("Lead & application cannot run alongside"));
        bad = plDefault();
        int uw = bad.indexOf(bad.stream().filter(x -> x.key().equals("uw")).findFirst().orElseThrow());
        bad.set(uw, par(bad.get(uw)));
        assertThat(WorkflowDesignService.validate(bad)).anyMatch(e -> e.contains("Underwriting cannot run alongside"));
        bad = plDefault();
        int cam = bad.indexOf(bad.stream().filter(x -> x.key().equals("cam")).findFirst().orElse(bad.get(1)));
        if (bad.get(cam).key().equals("cam") && bad.get(cam - 1).key().equals("uw")) {
            bad.set(cam, par(bad.get(cam)));
            assertThat(WorkflowDesignService.validate(bad)).anyMatch(e -> e.contains("needs Underwriting finished first"));
        }
    }
}
