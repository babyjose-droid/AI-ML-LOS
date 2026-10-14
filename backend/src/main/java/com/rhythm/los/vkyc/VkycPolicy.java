package com.rhythm.los.vkyc;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * A product's video KYC (V-CIP) settings. When the applicant needs a call stays in the product's video KYC rule
 * (when KYC needs review, always, or above an amount); this adds who else needs one, how strict the AI checks are,
 * when customers can join, how many attempts, who may conduct the call and the questions the official must ask.
 *
 * RBI V-CIP fixes some things that are not settings here: a live call with a trained official, consent, location in
 * India, a random code read out by the customer, and a recording kept with the file.
 */
public record VkycPolicy(String parties, double minFaceMatch, boolean requireLiveness, boolean requirePanOnCamera, int linkHours,
                         String callFrom, String callTo, int maxAttempts, List<String> officerRoles, List<String> questions, List<String> languages) {

    /** NONE: never for co-applicants and guarantors; ON_KYC_REVIEW: to clear their KYC review; ALL: each one before the decision. */
    public static final Set<String> PARTY_MODES = Set.of("NONE", "ON_KYC_REVIEW", "ALL");
    public static final Set<String> ROLES = Set.of("OPERATIONS", "CREDIT_OFFICER", "CREDIT_MANAGER", "ADMIN");
    public static final List<String> LANGS = List.of("en", "hi", "mr", "ta", "ml", "kn", "te", "bn", "gu");
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final VkycPolicy DEFAULT = new VkycPolicy("ON_KYC_REVIEW", 0.363, true, true, 24, "00:00", "23:59", 3,
            List.of("OPERATIONS"), List.of("Confirm your full name and date of birth", "What is the loan for?"), List.of("en", "hi"));

    public static VkycPolicy parse(String json) {
        if (json == null || json.isBlank()) return DEFAULT;
        try {
            return normalise(Json.read(json, VkycPolicy.class));
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    static VkycPolicy normalise(VkycPolicy p) {
        if (p == null) return DEFAULT;
        return new VkycPolicy(p.parties() == null ? "ON_KYC_REVIEW" : p.parties().trim().toUpperCase(),
                p.minFaceMatch() <= 0 ? DEFAULT.minFaceMatch() : p.minFaceMatch(), p.requireLiveness(), p.requirePanOnCamera(),
                p.linkHours() <= 0 ? 24 : p.linkHours(), p.callFrom() == null || p.callFrom().isBlank() ? "00:00" : p.callFrom().trim(),
                p.callTo() == null || p.callTo().isBlank() ? "23:59" : p.callTo().trim(), p.maxAttempts() <= 0 ? 3 : p.maxAttempts(),
                p.officerRoles() == null || p.officerRoles().isEmpty() ? List.of("OPERATIONS") : List.copyOf(p.officerRoles()),
                p.questions() == null ? List.of() : p.questions().stream().filter(q -> q != null && !q.isBlank()).map(String::trim).toList(),
                p.languages() == null || p.languages().isEmpty() ? List.of("en") : List.copyOf(p.languages()));
    }

    /** Checks the settings; a 422 with a plain message when one is wrong. */
    public static VkycPolicy validated(VkycPolicy in) {
        VkycPolicy p = normalise(in);
        if (!PARTY_MODES.contains(p.parties())) throw bad("Co-applicants and guarantors must be one of " + PARTY_MODES);
        if (p.minFaceMatch() < 0.30 || p.minFaceMatch() > 0.80) throw bad("Minimum face match must be between 0.30 and 0.80 (the model's own threshold is 0.363)");
        if (p.linkHours() < 1 || p.linkHours() > 168) throw bad("A video KYC link is valid for 1 to 168 hours");
        if (p.maxAttempts() < 1 || p.maxAttempts() > 10) throw bad("Attempts per person must be 1 to 10");
        LocalTime from = time(p.callFrom()), to = time(p.callTo());
        if (!from.isBefore(to)) throw bad("Call hours must start before they end");
        for (String r : p.officerRoles()) if (!ROLES.contains(r)) throw bad("Officials can be " + ROLES);
        for (String l : p.languages()) if (!LANGS.contains(l)) throw bad("Unknown language " + l);
        if (p.questions().size() > 10) throw bad("At most 10 questions");
        for (String q : p.questions()) if (q.length() > 200) throw bad("A question can be at most 200 characters");
        return p;
    }

    private static ApiException bad(String m) { return ApiException.unprocessable("BAD_VKYC_POLICY", m); }

    static LocalTime time(String s) {
        try { return LocalTime.parse(s.length() == 4 ? "0" + s : s); }
        catch (Exception e) { throw bad("Call hours must be HH:MM, e.g. 09:00"); }
    }

    /** Whether a customer may join now (India time). */
    public boolean withinHours(ZonedDateTime now) {
        LocalTime t = now.withZoneSameInstant(IST).toLocalTime();
        return !t.isBefore(time(callFrom)) && !t.isAfter(time(callTo));
    }

    public boolean allTheTime() { return "00:00".equals(callFrom) && "23:59".equals(callTo); }

    /**
     * The product's verdict on the AI analysis: PASS only when every check the product asks for passed. Liveness and the
     * PAN on camera count only when required; the face match must reach the product's own minimum.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> judge(Map<String, Object> analysis) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> live = analysis.get("liveness") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> face = analysis.get("faceMatch") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Object panCheck = analysis.get("panCheck");
        List<String> aiReasons = analysis.get("reasons") instanceof List<?> l ? (List<String>) l : List.of();
        if (requireLiveness && !Boolean.TRUE.equals(live.get("passed")))
            reasons.addAll(live.get("reasons") instanceof List<?> l && !l.isEmpty() ? (List<String>) l : List.of("Liveness not shown"));
        double score = face.get("score") instanceof Number n ? n.doubleValue() : 0;
        if (score < minFaceMatch)
            reasons.add((face.get("reason") instanceof String r && !r.isBlank() ? r + ": face match " : "Face match ")
                    + Math.round(score * 100) / 100.0 + " is below the product's minimum " + minFaceMatch);
        if (requirePanOnCamera) {
            if (panCheck instanceof Map<?, ?> pc && !Boolean.TRUE.equals(pc.get("matches")))
                reasons.add(pc.get("found") == null ? "PAN shown on camera could not be read" : "PAN shown on camera (" + pc.get("found") + ") does not match");
            for (String r : aiReasons) if (r.contains("PAN")) reasons.add(r);
        }
        // the AI's own picture-quality findings (too dark, unreadable frames) always count
        for (String r : aiReasons) if (r.toLowerCase().contains("dark") || r.toLowerCase().contains("no frames")) reasons.add(r);
        Map<String, Object> out = new LinkedHashMap<>(analysis);
        out.put("aiStatus", analysis.get("status"));
        out.put("status", reasons.isEmpty() ? "PASS" : "REVIEW");
        out.put("reasons", reasons.stream().distinct().toList());
        out.put("policy", Map.of("minFaceMatch", minFaceMatch, "requireLiveness", requireLiveness, "requirePanOnCamera", requirePanOnCamera));
        return out;
    }

    /** True when a co-applicant or guarantor must have an approved video KYC before the decision. */
    public boolean partyNeedsCall(String kycState) {
        return "ALL".equals(parties) || "ON_KYC_REVIEW".equals(parties) && "REVIEW".equals(kycState);
    }
}
