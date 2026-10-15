package com.rhythm.los.docgen;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocTemplateEngineTest {
    static Map<String, Object> data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("applicant", Map.of("name", "Rohan <Kulkarni>", "city", "Pune"));
        d.put("loan", Map.of("amount", 600000, "rate", 18.5, "secured", false, "tenureMonths", 36));
        d.put("today", "2026-10-15");
        d.put("coApplicants", List.of(Map.of("name", "Sneha", "relationship", "Spouse")));
        d.put("guarantors", List.of());
        return d;
    }

    @Test
    void fillsValuesWithFormatsAndEscapes() {
        var r = DocTemplateEngine.render("<p>{{applicant.name}} borrows {{loan.amount | rupees}} ({{loan.amount | words}}) at {{loan.rate | percent}} on {{today | date}}; {{missing.field}}</p>",
                data(), x -> null);
        assertThat(r.html()).isEqualTo("<p>Rohan &lt;Kulkarni&gt; borrows ₹6,00,000 (Rupees Six Lakh Only) at 18.5% on 15 October 2026; </p>");
        assertThat(r.missing()).containsExactly("missing.field");
        assertThat(DocTemplateEngine.words(12_34_56_789)).isEqualTo("Rupees Twelve Crore Thirty Four Lakh Fifty Six Thousand Seven Hundred and Eighty Nine Only");
        assertThat(DocTemplateEngine.rupees(1234.5)).isEqualTo("₹1,234.50");
        assertThat(DocTemplateEngine.indian(100000000)).isEqualTo("10,00,00,000");
    }

    @Test
    void repeatsConditionsAndClauses() {
        String t = "{{#each coApplicants}}<li>{{@number}}. {{name}} ({{relationship}}) of {{applicant.city}}</li>{{/each}}"
                + "{{#each guarantors}}x{{else}}<p>No guarantor</p>{{/each}}"
                + "{{#if loan.secured}}S{{else}}U{{/if}}{{#if loan.tenureMonths >= 36}}L{{/if}}{{#unless guarantors}}N{{/unless}}"
                + "{{#if applicant.city == \"Pune\"}}P{{/if}}{{> CL_ONE}}";
        var r = DocTemplateEngine.render(t, data(), n -> n.equals("CL_ONE") ? "<b>{{applicant.city}}</b>" : null);
        assertThat(r.html()).isEqualTo("<li>1. Sneha (Spouse) of Pune</li><p>No guarantor</p>ULNP<b>Pune</b>");
    }

    @Test
    void reportsMistakesClearly() {
        Map<String, Set<String>> fields = Map.of("applicant.name", Set.of(), "coApplicants", Set.of("name", "relationship"), "loan.amount", Set.of());
        assertThat(DocTemplateEngine.check("{{applicant.name}} {{#each coApplicants}}{{name}}{{/each}} {{> CL_X}}", fields, Set.of("CL_X"))).isEmpty();
        assertThat(DocTemplateEngine.check("{{applicant.nam}}", fields, Set.of())).containsExactly("Unknown field {{applicant.nam}}");
        assertThat(DocTemplateEngine.check("{{#each coApplicants}}{{pan}}{{/each}}", fields, Set.of())).anyMatch(e -> e.contains("{{pan}}"));
        assertThat(DocTemplateEngine.check("{{> CL_NONE}}", fields, Set.of())).containsExactly("No clause or block called CL_NONE");
        assertThat(DocTemplateEngine.check("{{#if applicant.name}}x", fields, Set.of()).get(0)).contains("is not closed");
        assertThat(DocTemplateEngine.check("{{#each coApplicants}}x{{/if}}", fields, Set.of()).get(0)).contains("closed by {{/if}}");
        assertThat(DocTemplateEngine.check("{{loan.amount | dollars}}", fields, Set.of()).get(0)).contains("Unknown format");
        assertThatThrownBy(() -> DocTemplateEngine.render("{{> A1}}", Map.of(), n -> "{{> A1}}")).hasMessageContaining("too deeply");
    }
}
