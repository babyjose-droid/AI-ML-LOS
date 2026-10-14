package com.rhythm.los.collateral;

import com.rhythm.los.common.Fmt;

import com.rhythm.los.document.NameMatch;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Collateral AI engine (FSD "Collateral AI and unified BRE"): EC analysis, title chain, vision checks, valuation and
 * LTV, the 10-dimension risk vector and rules CR-01 to CR-11. Pure: the service gathers the evidence and stores the
 * result. The rules decide the outcome; the risk score is for information and pricing.
 */
public final class CollateralEngine {
    private CollateralEngine() {}

    public static final String MODEL_VERSION = "COL-AI-1.0";
    public static final Set<String> PROPERTY = Set.of("RESIDENTIAL", "SHOP", "PLOT");

    public record Doc(long id, String type, Map<String, Object> fields, List<Map<String, Object>> transactions, double confidence, boolean manipulated) {
        Object f(String k) { return fields == null ? null : fields.get(k); }
        String s(String k) { Object v = f(k); return v == null ? null : v.toString().trim(); }
    }
    public record Media(long id, String label, Double distanceM, String quality, String reuseRef, double tamper, boolean manipulated) {}
    public record Val(long id, String source, LocalDate date, double market, Double realisable, Double distress, boolean approved, String valuer) {}
    public record Policy(String type, int ecYears, int titleDepthYears, double haircut, double maxLtv, int valuationMaxAgeMonths, Double secondValuationAbove,
                         double varianceLimit, int minImages, int gpsRadiusM, double coverageMin, List<String> requiredDocs, List<String> requiredPhotos) {}
    public record Facts(String condition, String constructionStage, String declaredOwner, String applicantName, String surveyNo, String municipalId) {}
    /** A person's earlier ruling on a rule hit: CLEARED (accepted with a reason) or UPHELD (the issue is real). */
    public record Ruling(String ruleId, String reason, String outcome) {}
    public record Inputs(Policy pol, double requested, Facts facts, List<Doc> docs, List<Media> media, List<Val> vals, Double aiValue, double aiConfidence,
                         List<Ruling> rulings, LocalDate today) {}

    public record Hit(String id, String check, String output, boolean passed, String reason, List<String> evidence, String ruling) {}
    public record Dim(String name, double weight, int score, String evidence) {}
    public record ReviewNeed(String ruleId, String type, String role, int slaHours, String reason) {}
    public record Result(String status, int riskScore, List<Dim> risk, List<Hit> rules, Map<String, Object> ltv, Map<String, Object> ec, List<Map<String, Object>> title,
                         Map<String, Object> vision, Map<String, Object> valuation, List<String> conditions, List<ReviewNeed> reviews) {}

    static final Map<String, String> OUTPUT = Map.ofEntries(
            Map.entry("CR-01", "COLLATERAL FAIL"), Map.entry("CR-02", "COLLATERAL FAIL"), Map.entry("CR-03", "COLLATERAL REFER"),
            Map.entry("CR-04", "LEGAL REVIEW"), Map.entry("CR-05", "LEGAL REVIEW"), Map.entry("CR-06", "LEGAL REVIEW"),
            Map.entry("CR-07", "VALUATION REVIEW"), Map.entry("CR-08", "VALUATION REVIEW"), Map.entry("CR-09", "FIELD REVISIT"),
            Map.entry("CR-10", "FIELD REVISIT"), Map.entry("CR-11", "LTV CAP"));
    static final Map<String, String> CHECK = Map.ofEntries(
            Map.entry("CR-01", "Applicant is the current owner per the latest title or EC entry"),
            Map.entry("CR-02", "No confirmed image or document manipulation"),
            Map.entry("CR-03", "All mandatory documents present"),
            Map.entry("CR-04", "No unresolved charge"),
            Map.entry("CR-05", "Property identifiers consistent across documents"),
            Map.entry("CR-06", "EC covers the required period"),
            Map.entry("CR-07", "Valuation not older than the policy allows"),
            Map.entry("CR-08", "Valuation variance within the limit"),
            Map.entry("CR-09", "GPS within range and photo coverage enough"),
            Map.entry("CR-10", "No image reuse"),
            Map.entry("CR-11", "Exposure within maximum LTV"));

    static final Set<String> TRANSFERS = Set.of("SALE", "GIFT", "PARTITION", "COURT_ORDER");

    static String norm(String s) { return s == null ? null : s.replaceAll("[^A-Za-z0-9/]", "").toUpperCase(Locale.ROOT); }
    static double num(Object o) { return o instanceof Number n ? n.doubleValue() : o == null ? 0 : parse(o.toString()); }
    static double parse(String s) { try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); } catch (Exception e) { return 0; } }
    static LocalDate date(Object o) { try { return o == null ? null : LocalDate.parse(o.toString().substring(0, 10)); } catch (Exception e) { return null; } }
    static boolean same(String a, String b) { return a != null && b != null && NameMatch.score(a, b) >= 0.8; }

    /** Gold: RBI LTV caps by loan size (Lending against gold collateral directions, 2025): 85% up to ₹2.5 lakh, 80% up to ₹5 lakh, 75% above. */
    public static double goldMaxLtv(double requested) { return requested <= 250_000 ? 0.85 : requested <= 500_000 ? 0.80 : 0.75; }

    // ---------------------------------------------------------------- EC and title chain

    public static Map<String, Object> ec(Inputs in) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Doc> ecs = in.docs().stream().filter(d -> "EC".equals(d.type())).toList();
        m.put("present", !ecs.isEmpty());
        if (ecs.isEmpty()) return m;
        Doc e = ecs.get(ecs.size() - 1);
        LocalDate from = date(e.f("periodFrom")), to = date(e.f("periodTo"));
        double years = from == null || to == null ? 0 : ChronoUnit.DAYS.between(from, to) / 365.25;
        List<Map<String, Object>> tx = new ArrayList<>(e.transactions() == null ? List.of() : e.transactions());
        tx.sort(Comparator.comparing(t -> Objects.toString(t.get("date"), "")));
        List<Map<String, Object>> open = new ArrayList<>(), matched = new ArrayList<>();
        for (Map<String, Object> t : tx) {
            if (!"MORTGAGE".equals(t.get("type"))) continue;
            Object no = t.get("docNo");
            Map<String, Object> rel = tx.stream().filter(r -> "RELEASE".equals(r.get("type"))
                    && (Objects.equals(r.get("linkedRef"), no) || (r.get("linkedRef") == null && same(Objects.toString(r.get("from"), null), Objects.toString(t.get("to"), null))
                    && Objects.toString(r.get("date"), "").compareTo(Objects.toString(t.get("date"), "")) > 0))).findFirst().orElse(null);
            Map<String, Object> c = new LinkedHashMap<>(t);
            if (rel != null) { c.put("releasedBy", rel.get("docNo")); c.put("releasedOn", rel.get("date")); matched.add(c); } else open.add(c);
        }
        Map<String, Object> last = null;
        for (Map<String, Object> t : tx) if (TRANSFERS.contains(Objects.toString(t.get("type"), ""))) last = t;
        m.put("docId", e.id());
        m.put("periodFrom", from == null ? null : from.toString());
        m.put("periodTo", to == null ? null : to.toString());
        m.put("years", Math.round(years * 10) / 10.0);
        m.put("requiredYears", in.pol().ecYears());
        m.put("current", to != null && !to.isBefore(in.today().minusDays(90)));
        m.put("transactions", tx);
        m.put("openCharges", open);
        m.put("releasedCharges", matched);
        m.put("latestOwner", last == null ? null : last.get("to"));
        m.put("latestTransfer", last);
        return m;
    }

    /** Ownership timeline from the EC transfers and the deeds, with gaps (missing links) and conflicts (double transfers). */
    public static List<Map<String, Object>> title(Inputs in, Map<String, Object> ec) {
        List<Map<String, Object>> links = new ArrayList<>();
        for (Doc d : in.docs()) {
            if (!Set.of("MOTHER_DEED", "SALE_DEED").contains(d.type()) || d.s("owner") == null) continue;
            LocalDate dt = date(d.f("registrationDate"));
            links.add(link(dt, d.type().equals("MOTHER_DEED") ? "Mother deed" : "Sale deed", d.s("seller"), d.s("owner"), "DOC-" + d.id()));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tx = (List<Map<String, Object>>) ec.getOrDefault("transactions", List.of());
        for (Map<String, Object> t : tx) {
            if (!TRANSFERS.contains(Objects.toString(t.get("type"), ""))) continue;
            String to = Objects.toString(t.get("to"), null);
            LocalDate dt = date(t.get("date"));
            // the same transfer may be in the deed and in the EC: keep one
            boolean dup = links.stream().anyMatch(l -> Objects.equals(l.get("date"), dt == null ? null : dt.toString()) && same((String) l.get("owner"), to));
            if (!dup) links.add(link(dt, "EC: " + t.get("type").toString().toLowerCase() + " " + t.get("docNo"), Objects.toString(t.get("from"), null), to, "DOC-" + ec.get("docId")));
        }
        links.sort(Comparator.comparing(l -> Objects.toString(l.get("date"), "")));
        String prev = null;
        Map<String, Integer> soldBy = new HashMap<>();
        for (Map<String, Object> l : links) {
            String from = (String) l.get("fromOwner");
            if (prev != null && from != null && !same(prev, from)) {
                l.put("flag", "GAP");
                l.put("note", "Transferred by " + from + " but the previous owner on record is " + prev);
            }
            if (from != null) {
                String k = norm(from);
                soldBy.merge(k, 1, Integer::sum);
                if (soldBy.get(k) > 1) { l.put("flag", "CONFLICT"); l.put("note", from + " transferred the property more than once"); }
            }
            prev = (String) l.get("owner");
        }
        if (!links.isEmpty() && in.pol().titleDepthYears() > 0) {
            LocalDate first = date(links.get(0).get("date"));
            if (first != null && ChronoUnit.YEARS.between(first, in.today()) < in.pol().titleDepthYears() && links.get(0).get("fromOwner") == null) {
                links.get(0).put("flag", "GAP");
                links.get(0).put("note", "Chain starts in " + first.getYear() + "; policy asks for " + in.pol().titleDepthYears() + " years");
            }
        }
        return links;
    }

    static Map<String, Object> link(LocalDate d, String instrument, String from, String owner, String evidence) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("date", d == null ? null : d.toString());
        l.put("year", d == null ? null : d.getYear());
        l.put("instrument", instrument);
        l.put("fromOwner", from);
        l.put("owner", owner);
        l.put("evidence", evidence);
        return l;
    }

    // ---------------------------------------------------------------- evaluation

    public static Result evaluate(Inputs in) {
        Policy pol = in.pol();
        String type = pol.type();
        boolean property = PROPERTY.contains(type), gold = "GOLD".equals(type), vehicle = "VEHICLE".equals(type);
        List<Hit> hits = new ArrayList<>();
        Map<String, Object> ec = property ? ec(in) : Map.of("present", false);
        List<Map<String, Object>> title = property ? title(in, ec) : List.of();
        Set<String> present = new HashSet<>();
        in.docs().forEach(d -> present.add(d.type()));

        // CR-01 ownership
        String owner = null, ownerEvidence = null;
        if (property) {
            if (ec.get("latestOwner") != null) { owner = ec.get("latestOwner").toString(); ownerEvidence = "DOC-" + ec.get("docId"); }
            else {
                Doc deed = in.docs().stream().filter(d -> "SALE_DEED".equals(d.type()) && d.s("owner") != null).reduce((a, b) -> b).orElse(null);
                if (deed != null) { owner = deed.s("owner"); ownerEvidence = "DOC-" + deed.id(); }
            }
        } else if (vehicle) {
            Doc rc = in.docs().stream().filter(d -> "RC".equals(d.type()) && d.s("owner") != null).reduce((a, b) -> b).orElse(null);
            if (rc != null) { owner = rc.s("owner"); ownerEvidence = "DOC-" + rc.id(); }
        }
        if (gold) hits.add(hit("CR-01", true, "Gold declared as the applicant's own; ownership declaration recorded at appraisal", List.of()));
        else if (owner == null) hits.add(hit("CR-01", true, "Owner not yet known from the documents (checked once the deed, EC or RC is read)", List.of()));
        else {
            boolean ok = same(owner, in.facts().applicantName()) || (in.facts().declaredOwner() != null && same(owner, in.facts().declaredOwner()) && same(in.facts().declaredOwner(), in.facts().applicantName()));
            hits.add(hit("CR-01", ok, ok ? "Current owner " + owner + " matches the applicant" : "Current owner on record is " + owner + ", not the applicant " + in.facts().applicantName(), List.of(ownerEvidence)));
        }

        // CR-02 manipulation
        List<String> manip = new ArrayList<>();
        in.docs().stream().filter(Doc::manipulated).forEach(d -> manip.add("DOC-" + d.id()));
        in.media().stream().filter(m -> m.manipulated() || m.tamper() >= 0.9).forEach(m -> manip.add("IMG-" + m.id()));
        hits.add(hit("CR-02", manip.isEmpty(), manip.isEmpty() ? "No manipulation confirmed" : "Manipulation confirmed on " + String.join(", ", manip), manip));

        // CR-03 mandatory documents (and a valuation on record)
        List<String> missing = new ArrayList<>();
        for (String d : pol.requiredDocs()) if (!present.contains(d) && !(gold && "APPRAISAL".equals(d))) missing.add(d);
        List<Val> approved = in.vals().stream().filter(v -> v.approved() && !"AI".equals(v.source())).toList();
        if (approved.isEmpty()) missing.add(gold ? "APPRAISAL" : "VALUATION (recorded)");
        // COL-E01: a document read with low confidence counts only once a person confirms its fields
        List<String> unconfirmed = in.docs().stream().filter(d -> d.confidence() < 0.85).map(d -> "DOC-" + d.id() + " " + d.type()).toList();
        List<String> why3 = new ArrayList<>();
        if (!missing.isEmpty()) why3.add("Missing: " + String.join(", ", missing));
        if (!unconfirmed.isEmpty()) why3.add("Fields to confirm: " + String.join(", ", unconfirmed));
        hits.add(hit("CR-03", why3.isEmpty(), why3.isEmpty() ? "All mandatory documents present" : String.join("; ", why3), unconfirmed));

        // CR-04 charges
        if (property) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> open = (List<Map<String, Object>>) ec.getOrDefault("openCharges", List.of());
            hits.add(hit("CR-04", open.isEmpty(), open.isEmpty() ? (Boolean.TRUE.equals(ec.get("present")) ? "Every mortgage in the EC has a release" : "No EC yet: charges cannot be checked")
                    : "Open charge: " + open.stream().map(o -> o.get("docNo") + " (" + o.get("date") + ", in favour of " + o.get("to") + ")").reduce((a, b) -> a + "; " + b).orElse(""), List.of("DOC-" + ec.get("docId"))));
            if (!Boolean.TRUE.equals(ec.get("present"))) hits.set(hits.size() - 1, hit("CR-04", true, "No EC yet: charges are checked once it is read", List.of()));
        } else if (vehicle) {
            Doc rc = in.docs().stream().filter(d -> "RC".equals(d.type())).reduce((a, b) -> b).orElse(null);
            String hyp = rc == null ? null : rc.s("assignedTo");
            boolean noc = present.contains("NOC");
            boolean ok = hyp == null || hyp.isBlank() || hyp.equalsIgnoreCase("none") || noc;
            hits.add(hit("CR-04", ok, ok ? "No hypothecation on the RC" : "RC hypothecated to " + hyp + " with no NOC", rc == null ? List.of() : List.of("DOC-" + rc.id())));
        } else hits.add(hit("CR-04", true, "Not applicable to gold", List.of()));

        // CR-05 identifiers consistent
        List<String> idIssues = new ArrayList<>(), idEv = new ArrayList<>();
        if (property) {
            Map<String, String> survey = new LinkedHashMap<>(), municipal = new LinkedHashMap<>();
            for (Doc d : in.docs()) {
                if (d.s("surveyNo") != null) survey.put("DOC-" + d.id() + " " + d.type(), norm(d.s("surveyNo")));
                if (d.s("municipalId") != null) municipal.put("DOC-" + d.id() + " " + d.type(), norm(d.s("municipalId")));
            }
            if (in.facts().surveyNo() != null) survey.put("declared", norm(in.facts().surveyNo()));
            if (in.facts().municipalId() != null) municipal.put("declared", norm(in.facts().municipalId()));
            if (new HashSet<>(survey.values()).size() > 1) { idIssues.add("Survey/CTS numbers differ: " + survey); idEv.addAll(survey.keySet()); }
            if (new HashSet<>(municipal.values()).size() > 1) { idIssues.add("Municipal IDs differ: " + municipal); idEv.addAll(municipal.keySet()); }
            Doc deed = in.docs().stream().filter(d -> "SALE_DEED".equals(d.type())).reduce((a, b) -> b).orElse(null);
            Doc plan = in.docs().stream().filter(d -> Set.of("APPROVED_PLAN", "OC").contains(d.type())).reduce((a, b) -> b).orElse(null);
            if (deed != null && plan != null && num(deed.f("areaSqft")) > 0 && num(plan.f("areaSqft")) > 0) {
                double diff = Math.abs(num(deed.f("areaSqft")) - num(plan.f("areaSqft"))) / num(deed.f("areaSqft"));
                if (diff > 0.10) { idIssues.add("Area in the plan differs from the deed by " + Math.round(diff * 100) + "%"); idEv.add("DOC-" + deed.id()); idEv.add("DOC-" + plan.id()); }
            }
            if (plan != null && plan.s("permittedUse") != null) {
                String use = plan.s("permittedUse").toLowerCase(Locale.ROOT);
                boolean ok = "RESIDENTIAL".equals(type) ? use.contains("resid") : use.contains("commerc") || use.contains("non-agri") || use.contains("na ");
                if (!ok) { idIssues.add("Permitted use '" + plan.s("permittedUse") + "' does not match " + type.toLowerCase()); idEv.add("DOC-" + plan.id()); }
            }
        } else if (vehicle) {
            Map<String, String> ch = new LinkedHashMap<>();
            for (Doc d : in.docs()) if (d.s("chassisNo") != null) ch.put("DOC-" + d.id() + " " + d.type(), norm(d.s("chassisNo")));
            if (new HashSet<>(ch.values()).size() > 1) { idIssues.add("Chassis numbers differ: " + ch); idEv.addAll(ch.keySet()); }
        }
        hits.add(hit("CR-05", idIssues.isEmpty(), idIssues.isEmpty() ? "Identifiers consistent" : String.join("; ", idIssues), idEv));

        // CR-06 EC period
        if (property) {
            boolean presentEc = Boolean.TRUE.equals(ec.get("present"));
            double yrs = presentEc ? (double) ec.get("years") : 0;
            boolean ok = presentEc && yrs + 0.05 >= pol.ecYears() && Boolean.TRUE.equals(ec.get("current"));
            hits.add(hit("CR-06", ok || !presentEc && missing.contains("EC"), !presentEc ? "EC not uploaded (counted under missing documents)"
                    : ok ? "EC covers " + yrs + " years up to " + ec.get("periodTo") : "EC covers " + yrs + " of " + pol.ecYears() + " years" + (Boolean.TRUE.equals(ec.get("current")) ? "" : " and ends on " + ec.get("periodTo") + " (more than 90 days ago)"),
                    presentEc ? List.of("DOC-" + ec.get("docId")) : List.of()));
        } else hits.add(hit("CR-06", true, "Not applicable", List.of()));

        // CR-07 / CR-08 valuations
        Map<String, Object> valuation = new LinkedHashMap<>();
        double valueUsed = approved.stream().mapToDouble(Val::market).min().orElse(0);
        LocalDate newest = approved.stream().map(Val::date).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        long ageMonths = newest == null ? 0 : ChronoUnit.MONTHS.between(newest, in.today());
        boolean needSecond = pol.secondValuationAbove() != null && valueUsed > pol.secondValuationAbove() && approved.size() < 2;
        boolean ageOk = newest == null || ageMonths <= pol.valuationMaxAgeMonths();
        hits.add(hit("CR-07", ageOk && !needSecond, newest == null ? "No valuation yet (counted under missing documents)"
                : !ageOk ? "Latest valuation is " + ageMonths + " months old (policy " + pol.valuationMaxAgeMonths() + ")"
                : needSecond ? "Value above " + Fmt.rupees(pol.secondValuationAbove()) + " needs a second empanelled valuation" : "Valuation dated " + newest + " is within " + pol.valuationMaxAgeMonths() + " months",
                approved.stream().map(v -> "VAL-" + v.id()).toList()));
        double max = approved.stream().mapToDouble(Val::market).max().orElse(0);
        double variance = valueUsed > 0 ? (max - valueUsed) / valueUsed : 0;
        hits.add(hit("CR-08", variance <= pol.varianceLimit() + 1e-9, approved.isEmpty() ? "No valuation yet: no variance to check" : approved.size() < 2 ? "One valuation: no variance to check"
                : "Variance between valuations " + Math.round(variance * 1000) / 10.0 + "% (limit " + Math.round(pol.varianceLimit() * 100) + "%)", approved.stream().map(v -> "VAL-" + v.id()).toList()));
        valuation.put("reports", approved.size());
        valuation.put("valueUsed", Math.round(valueUsed));
        valuation.put("highest", Math.round(max));
        valuation.put("variance", Math.round(variance * 10000) / 10000.0);
        valuation.put("newestReport", newest == null ? null : newest.toString());
        valuation.put("ageMonths", ageMonths);
        valuation.put("realisable", approved.stream().map(Val::realisable).filter(Objects::nonNull).mapToDouble(Double::doubleValue).min().orElse(0));
        valuation.put("distress", approved.stream().map(Val::distress).filter(Objects::nonNull).mapToDouble(Double::doubleValue).min().orElse(0));
        if (in.aiValue() != null) {
            valuation.put("aiValue", Math.round(in.aiValue()));
            valuation.put("aiConfidence", in.aiConfidence());
            valuation.put("aiDeviation", valueUsed > 0 ? Math.round(10000 * (valueUsed - in.aiValue()) / in.aiValue()) / 10000.0 : null);
        }

        // CR-09 / CR-10 vision
        Map<String, Object> vision = new LinkedHashMap<>();
        if (!gold) {
            Set<String> labels = new HashSet<>();
            in.media().forEach(x -> labels.add(x.label()));
            long covered = pol.requiredPhotos().stream().filter(labels::contains).count();
            double coverage = pol.requiredPhotos().isEmpty() ? 1 : (double) covered / pol.requiredPhotos().size();
            List<Media> far = in.media().stream().filter(x -> x.distanceM() != null && x.distanceM() > pol.gpsRadiusM()).toList();
            List<Media> noGps = in.media().stream().filter(x -> x.distanceM() == null).toList();
            List<Media> poor = in.media().stream().filter(x -> Set.of("BLURRY", "DARK", "GLARE").contains(x.quality())).toList();
            double maxDist = in.media().stream().map(Media::distanceM).filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
            List<String> why = new ArrayList<>();
            if (in.media().size() < pol.minImages()) why.add(in.media().size() + " of " + pol.minImages() + " photos");
            if (coverage + 1e-9 < pol.coverageMin()) why.add("coverage " + Math.round(coverage * 100) + "% (needs " + Math.round(pol.coverageMin() * 100) + "%); missing " + pol.requiredPhotos().stream().filter(l -> !labels.contains(l)).toList());
            if (!far.isEmpty()) why.add(far.size() + " photo(s) taken " + Math.round(maxDist) + " m from the property (limit " + pol.gpsRadiusM() + " m)");
            if (!noGps.isEmpty()) why.add(noGps.size() + " photo(s) without GPS");
            hits.add(hit("CR-09", why.isEmpty(), why.isEmpty() ? in.media().size() + " photos, coverage " + Math.round(coverage * 100) + "%, all within " + pol.gpsRadiusM() + " m" : String.join("; ", why),
                    far.stream().map(x -> "IMG-" + x.id()).toList()));
            List<Media> reused = in.media().stream().filter(x -> x.reuseRef() != null).toList();
            hits.add(hit("CR-10", reused.isEmpty(), reused.isEmpty() ? "No photo seen on another file" : reused.size() + " photo(s) also used on " + reused.stream().map(Media::reuseRef).distinct().toList(),
                    reused.stream().map(x -> "IMG-" + x.id()).toList()));
            vision.put("photos", in.media().size());
            vision.put("coverage", Math.round(coverage * 100) / 100.0);
            vision.put("maxDistanceM", Math.round(maxDist));
            vision.put("poorQuality", poor.size());
            vision.put("reused", reused.size());
            vision.put("missingLabels", pol.requiredPhotos().stream().filter(l -> !labels.contains(l)).toList());
        } else {
            hits.add(hit("CR-09", true, "Not applicable to gold (appraised at the branch)", List.of()));
            hits.add(hit("CR-10", true, "Not applicable to gold", List.of()));
        }

        // CR-11 LTV
        double maxLtv = gold ? goldMaxLtv(in.requested()) : pol.maxLtv();
        double eligible = valueUsed * (1 - pol.haircut());
        double maxExposure = Math.floor(eligible * maxLtv + 1e-6);
        double recommended = Math.min(in.requested(), maxExposure);
        boolean cap = valueUsed > 0 && in.requested() > maxExposure;
        Map<String, Object> ltv = new LinkedHashMap<>();
        ltv.put("valueUsed", Math.round(valueUsed));
        ltv.put("haircut", pol.haircut());
        ltv.put("eligibleValue", Math.round(eligible));
        ltv.put("maxLtv", maxLtv);
        ltv.put("maxExposure", Math.round(maxExposure));
        ltv.put("requested", Math.round(in.requested()));
        ltv.put("recommended", Math.round(recommended));
        ltv.put("ltvRequested", valueUsed > 0 ? Math.round(10000 * in.requested() / valueUsed) / 10000.0 : null);
        ltv.put("ltvRecommended", valueUsed > 0 ? Math.round(10000 * recommended / valueUsed) / 10000.0 : null);
        ltv.put("ltvCap", cap);
        hits.add(hit("CR-11", !cap, valueUsed == 0 ? "No value yet" : cap ? "Requested " + Fmt.rupees(in.requested()) + " is above the maximum exposure " + Fmt.rupees(maxExposure) + ": capped"
                : "Requested " + Fmt.rupees(in.requested()) + " within the maximum exposure " + Fmt.rupees(maxExposure), List.of()));

        // apply earlier rulings: a cleared hit with the same reason passes; an upheld one fails
        List<Hit> ruled = new ArrayList<>();
        for (Hit h : hits) {
            if (h.passed() || "CR-11".equals(h.id())) { ruled.add(h); continue; }
            Ruling r = in.rulings().stream().filter(x -> x.ruleId().equals(h.id()) && x.reason().equals(h.reason())).reduce((a, b) -> b).orElse(null);
            if (r == null) ruled.add(h);
            else if ("CLEARED".equals(r.outcome())) ruled.add(new Hit(h.id(), h.check(), h.output(), true, h.reason(), h.evidence(), "CLEARED"));
            else ruled.add(new Hit(h.id(), h.check(), "COLLATERAL FAIL", false, h.reason(), h.evidence(), "UPHELD"));
        }

        // status
        boolean fail = ruled.stream().anyMatch(h -> !h.passed() && "COLLATERAL FAIL".equals(h.output()));
        boolean refer = ruled.stream().anyMatch(h -> !h.passed() && !"LTV CAP".equals(h.output()) && !"COLLATERAL FAIL".equals(h.output()));
        String status = fail ? "FAIL" : refer ? "REFER" : "PASS";

        List<ReviewNeed> reviews = new ArrayList<>();
        for (Hit h : ruled) {
            if (h.passed() || h.ruling() != null) continue;
            switch (h.output()) {
                case "LEGAL REVIEW" -> reviews.add(new ReviewNeed(h.id(), "LEGAL", "LEGAL_REVIEWER", 72, h.reason()));
                case "VALUATION REVIEW" -> reviews.add(new ReviewNeed(h.id(), "VALUATION", "VALUER", 48, h.reason()));
                case "FIELD REVISIT" -> reviews.add(new ReviewNeed(h.id(), "FIELD", "FIELD_OFFICER", 48, h.reason()));
                case "COLLATERAL REFER" -> reviews.add(new ReviewNeed(h.id(), "CREDIT", "CREDIT_OFFICER", 24, h.reason()));
                default -> { }
            }
        }

        List<String> conditions = new ArrayList<>();
        if (property) {
            conditions.add("Original title documents verified and deposited before disbursement");
            conditions.add("Property insurance assigned to the lender for at least the loan amount");
            conditions.add("Mortgage (deposit of title deeds) registered and filed with CERSAI within 30 days");
        } else if (vehicle) {
            conditions.add("Hypothecation in the lender's favour endorsed on the RC and the insurance");
        } else conditions.add("Gold sealed in a tamper-proof packet with the borrower's signature and kept in the branch vault");
        if (cap) conditions.add("LTV CAP: exposure limited to " + Fmt.rupees(maxExposure) + " (LTV " + Math.round(maxLtv * 100) + "% of eligible value)");

        List<Dim> risk = risk(in, ruled, ec, title, vision, valuation, ltv, missing, type);
        int score = (int) Math.round(risk.stream().mapToDouble(d -> d.weight() * d.score()).sum());
        return new Result(status, score, risk, ruled, ltv, ec, title, vision, valuation, conditions, reviews);
    }

    static Hit hit(String id, boolean passed, String reason, List<String> evidence) {
        return new Hit(id, CHECK.get(id), OUTPUT.get(id), passed, reason, evidence.stream().filter(Objects::nonNull).toList(), null);
    }

    static boolean failed(List<Hit> hs, String id) { return hs.stream().anyMatch(h -> h.id().equals(id) && !h.passed()); }

    /** The 10-dimension risk vector (0 = no concern, 100 = severe); weights from the FSD. */
    static List<Dim> risk(Inputs in, List<Hit> hs, Map<String, Object> ec, List<Map<String, Object>> title, Map<String, Object> vision,
                          Map<String, Object> valuation, Map<String, Object> ltv, List<String> missing, String type) {
        List<Dim> d = new ArrayList<>();
        long conflicts = title.stream().filter(l -> "CONFLICT".equals(l.get("flag"))).count(), gaps = title.stream().filter(l -> "GAP".equals(l.get("flag"))).count();
        d.add(new Dim("Legal", 0.20, failed(hs, "CR-01") ? 100 : conflicts > 0 ? 80 : gaps > 0 ? 40 : 0, failed(hs, "CR-01") ? "Ownership not with the applicant" : conflicts + " conflict(s), " + gaps + " gap(s) in the title chain"));
        @SuppressWarnings("unchecked") List<Object> open = (List<Object>) ec.getOrDefault("openCharges", List.of());
        d.add(new Dim("Encumbrance", 0.15, !open.isEmpty() ? 90 : PROPERTY.contains(type) && !Boolean.TRUE.equals(ec.get("present")) ? 60 : 0, open.size() + " open charge(s)"));
        d.add(new Dim("Identity", 0.10, failed(hs, "CR-05") ? 70 : 0, failed(hs, "CR-05") ? "Identifier mismatch" : "Identifiers consistent"));
        Object dist = vision.get("maxDistanceM");
        int loc = "GOLD".equals(type) ? 0 : failed(hs, "CR-09") ? 70 : dist == null ? 50 : (int) Math.min(30, ((Number) dist).doubleValue() / Math.max(1, in.pol().gpsRadiusM()) * 30);
        d.add(new Dim("Location", 0.10, loc, dist == null ? "No GPS evidence" : "Farthest photo " + dist + " m"));
        String cond = in.facts().condition();
        int phys = ("POOR".equals(cond) ? 70 : "AVERAGE".equals(cond) ? 30 : 0) + ("UNDER_CONSTRUCTION".equals(in.facts().constructionStage()) ? 30 : 0);
        d.add(new Dim("Physical", 0.05, Math.min(100, phys), "Condition " + Objects.toString(cond, "not recorded") + ", " + Objects.toString(in.facts().constructionStage(), "stage not recorded")));
        int liq = switch (type) { case "PLOT" -> 50; case "SHOP" -> 40; case "VEHICLE" -> 30; case "RESIDENTIAL" -> 20; default -> 0; };
        d.add(new Dim("Liquidity", 0.05, liq, type.toLowerCase() + " collateral"));
        Object dev = valuation.get("aiDeviation");
        int val = (failed(hs, "CR-07") ? 50 : 0) + (failed(hs, "CR-08") ? 50 : 0) + (dev instanceof Number n && Math.abs(n.doubleValue()) > 0.2 ? 30 : 0);
        d.add(new Dim("Valuation", 0.10, Math.min(100, val), "Variance " + valuation.get("variance") + (dev == null ? "" : ", AI deviation " + dev)));
        Object lr = ltv.get("ltvRequested");
        double ratio = ((Number) ltv.get("maxExposure")).doubleValue() <= 0 ? 1.5 : ((Number) ltv.get("requested")).doubleValue() / ((Number) ltv.get("maxExposure")).doubleValue();
        d.add(new Dim("LTV", 0.10, ratio > 1 ? 80 : (int) Math.round(ratio * 40), "Requested LTV " + lr));
        int docScore = Math.min(100, missing.size() * 25 + (int) in.docs().stream().filter(x -> x.confidence() < 0.85).count() * 10);
        d.add(new Dim("Documentation", 0.05, docScore, missing.isEmpty() ? "Complete" : "Missing " + missing));
        double tamper = in.media().stream().mapToDouble(Media::tamper).max().orElse(0);
        int fraud = failed(hs, "CR-02") || failed(hs, "CR-10") ? 100 : (int) Math.round(tamper * 100);
        d.add(new Dim("Fraud", 0.10, fraud, failed(hs, "CR-10") ? "Image reuse" : failed(hs, "CR-02") ? "Manipulation" : "Max tamper score " + tamper));
        return d;
    }

    /** Unified credit + collateral decision (FSD 7). */
    public static Map<String, Object> unified(String creditDecision, String collateralStatus, List<String> conditions, Double maxExposure, double requested) {
        String credit = creditDecision == null ? "PENDING" : creditDecision;
        String col = collateralStatus == null ? "PENDING" : collateralStatus;
        String out;
        if ("REJECT".equals(credit) || "FAIL".equals(col)) out = "REJECT";
        else if (Set.of("REFER", "INCOMPLETE", "PENDING").contains(credit) || !"PASS".equals(col)) out = "REFER";
        else out = "APPROVE_WITH_CONDITIONS";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("credit", credit);
        m.put("collateral", col);
        m.put("decision", out);
        m.put("recommendedAmount", maxExposure == null ? null : Math.round(Math.min(requested, maxExposure)));
        m.put("conditions", "APPROVE_WITH_CONDITIONS".equals(out) ? conditions : List.of());
        if ("FAIL".equals(col) && !"REJECT".equals(credit)) m.put("note", "The secured offer is declined; an unsecured limit may be assessed separately");
        return m;
    }
}
