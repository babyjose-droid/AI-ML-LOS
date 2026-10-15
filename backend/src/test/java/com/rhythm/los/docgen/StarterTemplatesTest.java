package com.rhythm.los.docgen;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Every starter template and clause is valid, uses only known fields and fills in completely with sample values. */
class StarterTemplatesTest {
    static final Path SEED = Path.of("src/main/resources/docgen/seed");

    static Map<String, String> seeds() throws Exception {
        Map<String, String> m = new TreeMap<>();
        try (var files = Files.list(SEED)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList())
                m.put(p.getFileName().toString().replace(".html", ""), Files.readString(p));
        }
        return m;
    }

    @Test
    void allStarterTemplatesAreValidAndComplete() throws Exception {
        Map<String, String> s = seeds();
        assertThat(s.size()).isEqualTo(20);
        Set<String> partials = new TreeSet<>(DocFields.BUILT_IN.keySet());
        partials.addAll(s.keySet().stream().filter(k -> k.startsWith("CL_")).collect(Collectors.toSet()));
        java.util.function.Function<String, String> lookup = n -> DocFields.BUILT_IN.containsKey(n) ? DocFields.BUILT_IN.get(n) : s.get(n);
        for (var e : s.entrySet()) {
            assertThat(DocTemplateEngine.check(e.getValue(), DocFields.catalog(), partials)).as(e.getKey()).isEmpty();
            var r = DocTemplateEngine.render(e.getValue(), DocFields.sample(), lookup);
            assertThat(r.html()).as(e.getKey()).doesNotContain("{{");
        }
        for (String block : DocFields.BUILT_IN.values()) assertThat(DocTemplateEngine.check(block, DocFields.catalog(), partials)).isEmpty();
        var kfs = DocTemplateEngine.render(s.get("KFS"), DocFields.sample(), lookup);
        assertThat(kfs.html()).contains("Annual Percentage Rate").contains("₹6,00,000").contains("Rupees Six Lakh Only").contains("Foreclosure charge");
        var agreement = DocTemplateEngine.render(s.get("LOAN_AGREEMENT"), DocFields.sample(), lookup);
        assertThat(agreement.html()).contains("jointly and severally").contains("Sneha Kulkarni").contains("Guarantor: Madhav Joshi");
        assertThat(DocTemplateEngine.paths(s.get("SANCTION_LETTER"), lookup)).contains("loan.amount", "sanction.conditions", "parties").doesNotContain("today");
    }
}
