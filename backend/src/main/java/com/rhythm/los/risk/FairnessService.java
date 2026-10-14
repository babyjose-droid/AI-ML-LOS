package com.rhythm.los.risk;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.decision.engine.Features;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

/**
 * Fairness monitoring (FSD 21, RBI Fair Practices Code): approval rate, price and risk by gender, age band, region and
 * segment, on the latest decision of each application in the period. The adverse impact ratio compares each group's
 * approval rate with the best group's; below 0.80 (the four-fifths rule) is flagged for review. Groups under 30
 * decisions are shown but not judged. Gender, age and region are not model inputs; this checks the outcomes anyway,
 * because other inputs can stand in for them.
 */
@Service
public class FairnessService {
    static final int MIN_GROUP = 30;
    static final Map<Character, String> PIN_REGION = Map.of('1', "North (Delhi, Haryana, Punjab, HP, J&K)", '2', "North (UP, Uttarakhand)",
            '3', "West (Rajasthan, Gujarat)", '4', "West (Maharashtra, MP, Chhattisgarh)", '5', "South (AP, Telangana, Karnataka)",
            '6', "South (Tamil Nadu, Kerala)", '7', "East (West Bengal, Odisha, North East)", '8', "East (Bihar, Jharkhand)");

    private final DecisionRecordRepository decisions;
    private final LoanApplicationRepository apps;

    public FairnessService(DecisionRecordRepository decisions, LoanApplicationRepository apps) {
        this.decisions = decisions;
        this.apps = apps;
    }

    record Row(LoanApplication a, DecisionRecord d) {
        boolean approved() { return d.getDecision().startsWith("APPROVE") || ("REFER".equals(d.getDecision()) && List.of("SANCTIONED", "DISBURSED").contains(a.getAppState())); }
    }

    static String ageBand(int age) { return age < 25 ? "Under 25" : age < 35 ? "25–34" : age < 45 ? "35–44" : age < 55 ? "45–54" : "55 and over"; }

    static String region(String pin) {
        if (pin == null || pin.isBlank()) return "Not given";
        return PIN_REGION.getOrDefault(pin.charAt(0), "Other");
    }

    public Map<String, Object> report(int days) {
        Instant since = Instant.now().minus(Math.max(1, Math.min(days, 730)), ChronoUnit.DAYS);
        Map<Long, DecisionRecord> latest = new HashMap<>();
        for (DecisionRecord d : decisions.findAll()) {
            if (d.getCreatedAt() == null || d.getCreatedAt().isBefore(since) || "INCOMPLETE".equals(d.getDecision())) continue;
            latest.merge(d.getApplicationId(), d, (x, y) -> x.getId() > y.getId() ? x : y);
        }
        List<Row> rows = new ArrayList<>();
        for (DecisionRecord d : latest.values()) apps.findById(d.getApplicationId()).ifPresent(a -> rows.add(new Row(a, d)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("decisions", rows.size());
        out.put("approvalRate", rows.isEmpty() ? null : rate(rows.stream().filter(Row::approved).count(), rows.size()));
        List<Map<String, Object>> dims = new ArrayList<>();
        dims.add(dimension("gender", "Gender", rows, r -> r.a().getGender() == null || r.a().getGender().isBlank() ? "Not given" : label(r.a().getGender())));
        dims.add(dimension("age", "Age band", rows, r -> ageBand(r.a().ageYears())));
        dims.add(dimension("region", "Region (PIN zone)", rows, r -> region(r.a().getPincode())));
        dims.add(dimension("segment", "Segment", rows, r -> label(r.a().getSegment())));
        out.put("dimensions", dims);
        out.put("flags", dims.stream().mapToLong(m -> (Long) m.get("flagged")).sum());
        out.put("modelInputs", Features.ALL.stream().map(Features.Def::label).toList());
        return out;
    }

    static String label(String s) { return s == null ? "" : s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' '); }
    static double rate(long a, long b) { return b == 0 ? 0 : Math.round(1000.0 * a / b) / 1000.0; }

    Map<String, Object> dimension(String key, String name, List<Row> rows, Function<Row, String> group) {
        Map<String, List<Row>> by = new TreeMap<>();
        for (Row r : rows) by.computeIfAbsent(group.apply(r), k -> new ArrayList<>()).add(r);
        double best = by.values().stream().filter(g -> g.size() >= MIN_GROUP).mapToDouble(g -> (double) g.stream().filter(Row::approved).count() / g.size()).max().orElse(0);
        List<Map<String, Object>> groups = new ArrayList<>();
        long flagged = 0;
        for (var e : by.entrySet()) {
            List<Row> g = e.getValue();
            long ok = g.stream().filter(Row::approved).count();
            double ar = (double) ok / g.size();
            Double air = best > 0 && g.size() >= MIN_GROUP ? Math.round(1000.0 * ar / best) / 1000.0 : null;
            String status = g.size() < MIN_GROUP ? "TOO_FEW" : air != null && air < 0.8 ? "REVIEW" : "OK";
            if ("REVIEW".equals(status)) flagged++;
            OptionalDouble rateAvg = g.stream().filter(Row::approved).filter(r -> r.d().getRate() != null).mapToDouble(r -> r.d().getRate().doubleValue()).average();
            OptionalDouble pdAvg = g.stream().mapToDouble(r -> r.d().getPd().doubleValue()).average();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", e.getKey());
            m.put("decisions", g.size());
            m.put("approved", ok);
            m.put("approvalRate", rate(ok, g.size()));
            m.put("adverseImpactRatio", air);
            m.put("avgRateApproved", rateAvg.isPresent() ? Math.round(rateAvg.getAsDouble() * 100) / 100.0 : null);
            m.put("avgPd", pdAvg.isPresent() ? Math.round(pdAvg.getAsDouble() * 10000) / 10000.0 : null);
            m.put("status", status);
            groups.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("groups", groups);
        m.put("flagged", flagged);
        return m;
    }
}
