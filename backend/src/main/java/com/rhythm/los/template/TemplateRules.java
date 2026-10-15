package com.rhythm.los.template;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Placeholders each message may use, rendering, and checks on what staff type. Pure, so it is unit tested. */
public final class TemplateRules {
    private TemplateRules() {}

    public static final Set<String> CHANNELS = Set.of("LETTER", "SMS", "WHATSAPP", "EMAIL");
    public static final Set<String> LANGUAGES = Set.of("en", "hi", "mr", "ta", "ml", "kn", "te", "bn", "gu");
    static final Pattern VAR = Pattern.compile("\\{([A-Za-z]+)}");

    /** Customer messages and the details they can carry. Letter wording (sanction letter, KFS) takes no placeholders. */
    public static final Map<String, List<String>> MESSAGES = new LinkedHashMap<>();
    static {
        MESSAGES.put("OTP_LOGIN", List.of("otp", "lender", "minutes"));
        MESSAGES.put("PARTY_INVITE", List.of("applicantFirstName", "role", "link", "lender"));
        MESSAGES.put("VKYC_LINK", List.of("name", "link", "hours", "lender"));
        MESSAGES.put("SANCTION_SMS", List.of("name", "appNo", "amount", "lender"));
        MESSAGES.put("DISBURSED_SMS", List.of("name", "loanRef", "amount", "emi", "firstEmiDate", "lender"));
        MESSAGES.put("EMI_REMINDER", List.of("name", "loanRef", "emi", "dueDate", "lender"));
        MESSAGES.put("PTP_REMINDER", List.of("name", "loanRef", "amount", "date", "lender"));
    }
    /** Every message must carry these (an OTP message without the OTP is useless). */
    static final Map<String, List<String>> REQUIRED = Map.of("OTP_LOGIN", List.of("otp"), "PARTY_INVITE", List.of("link"), "VKYC_LINK", List.of("link"));

    public static List<String> placeholders(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = VAR.matcher(body == null ? "" : body);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /** Problems with a template, empty when it can be saved. */
    public static List<String> check(String channel, String code, String language, String body) {
        List<String> e = new ArrayList<>();
        if (!CHANNELS.contains(channel)) e.add("Channel must be one of " + new TreeSet<>(CHANNELS));
        if (!LANGUAGES.contains(language)) e.add("Language must be one of " + new TreeSet<>(LANGUAGES));
        String b = body == null ? "" : body.trim();
        if (b.isEmpty()) e.add("The text is empty");
        if (b.length() > ("LETTER".equals(channel) || "EMAIL".equals(channel) ? 4000 : 1000)) e.add("The text is too long");
        if (b.contains("<") && !"EMAIL".equals(channel)) e.add("No HTML: plain text only");
        if ("LETTER".equals(channel)) {
            if (!code.matches("[a-zA-Z]{2,40}")) e.add("Unknown letter wording key");
            if (!placeholders(b).isEmpty()) e.add("Letter wording takes no placeholders");
            return e;
        }
        List<String> allowed = MESSAGES.get(code);
        if (allowed == null) { e.add("Unknown message " + code); return e; }
        for (String p : placeholders(b)) if (!allowed.contains(p)) e.add("{" + p + "} cannot be used here; use " + String.join(", ", allowed.stream().map(x -> "{" + x + "}").toList()));
        for (String r : REQUIRED.getOrDefault(code, List.of())) if (!placeholders(b).contains(r)) e.add("The message must include {" + r + "}");
        if ("SMS".equals(channel) && render(b, sample(code)).length() > 320) e.add("Too long for two SMS (" + render(b, sample(code)).length() + " characters with sample details; 320 at most)");
        return e;
    }

    public static String render(String body, Map<String, String> vars) {
        Matcher m = VAR.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), m.group(0))));
        m.appendTail(sb);
        return sb.toString();
    }

    public static Map<String, String> sample(String code) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("otp", "482913"); s.put("lender", "Rhythm Demo Finance"); s.put("minutes", "10");
        s.put("applicantFirstName", "Rohan"); s.put("role", "a guarantor"); s.put("link", "https://los.example.in/party/AbC123xYz");
        s.put("name", "Sneha Kulkarni"); s.put("hours", "48"); s.put("appNo", "APP-300018"); s.put("amount", "₹6,00,000");
        s.put("loanRef", "LN-300018"); s.put("emi", "₹19,930"); s.put("firstEmiDate", "5 Dec 2026"); s.put("dueDate", "5 Jan 2027"); s.put("date", "20 Nov 2026");
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : MESSAGES.getOrDefault(code, List.of())) out.put(k, s.get(k));
        return out;
    }
}
