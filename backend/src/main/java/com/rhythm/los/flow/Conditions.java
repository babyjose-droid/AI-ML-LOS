package com.rhythm.los.flow;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, safe condition language for skip rules, for example:
 *   segment = SALARIED AND amount <= 5,00,000 AND incomeVerifiedViaAA = true
 *   decision = APPROVE AND amount <= 200000
 *   existingCustomer = true OR channel = BORROWER_PORTAL
 * AND binds tighter than OR. Operators: = != < <= > >=. Numbers may use Indian commas, a ₹ sign, or L/lakh and Cr.
 * Unknown fields are refused when the workflow is saved, so a rule can never silently do nothing.
 */
public final class Conditions {
    private Conditions() {}

    public static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("amount", "Loan amount applied for (₹)");
        FIELDS.put("tenure", "Tenure in months");
        FIELDS.put("segment", "SALARIED, MSME or MICROFINANCE");
        FIELDS.put("product", "Product code");
        FIELDS.put("channel", "BRANCH or BORROWER_PORTAL");
        FIELDS.put("age", "Applicant age in years");
        FIELDS.put("income", "Declared monthly income (₹)");
        FIELDS.put("existingCustomer", "true if an earlier loan with this PAN was disbursed");
        FIELDS.put("incomeVerifiedViaAA", "true if bank data came from the Account Aggregator");
        FIELDS.put("incomeVerifiedViaStatement", "true if bank data came from an AI-read bank statement");
        FIELDS.put("decision", "APPROVE, APPROVE_WITH_CONDITIONS, REFER or REJECT (after underwriting)");
        FIELDS.put("pd", "Probability of default, e.g. 0.03 (after underwriting)");
        FIELDS.put("band", "Risk band A–E (after underwriting)");
        FIELDS.put("score", "Rhythm score 300–900 (after underwriting)");
    }

    private static final Pattern CLAUSE = Pattern.compile("^\\s*([A-Za-z]+)\\s*(<=|>=|!=|=|<|>)\\s*(.+?)\\s*$");

    /** Returns an error message, or null when the rule is valid. Blank rules are valid (never skip). */
    public static String validate(String rule) {
        if (rule == null || rule.isBlank()) return null;
        if (rule.length() > 300) return "Rule is too long (300 characters at most)";
        for (String or : split(rule, "OR")) for (String and : split(or, "AND")) {
            Matcher m = CLAUSE.matcher(and);
            if (!m.matches()) return "Cannot read \"" + and.trim() + "\": write field, operator, value (e.g. amount <= 500000)";
            String f = canonical(m.group(1));
            if (f == null) return "Unknown field \"" + m.group(1) + "\". Use one of: " + String.join(", ", FIELDS.keySet());
            if (Set.of("<", "<=", ">", ">=").contains(m.group(2)) && number(m.group(3)) == null)
                return "\"" + m.group(3) + "\" is not a number";
        }
        return null;
    }

    /** True when the rule holds for these facts. A clause on a missing fact is false. */
    public static boolean eval(String rule, Map<String, Object> facts) {
        if (rule == null || rule.isBlank()) return false;
        for (String or : split(rule, "OR")) {
            boolean all = true;
            for (String and : split(or, "AND")) {
                if (!clause(and, facts)) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean clause(String text, Map<String, Object> facts) {
        Matcher m = CLAUSE.matcher(text);
        if (!m.matches()) return false;
        String f = canonical(m.group(1));
        if (f == null) return false;
        Object v = facts.get(f);
        if (v == null) return false;
        String op = m.group(2), raw = m.group(3).replaceAll("^['\"]|['\"]$", "");
        Double want = number(raw);
        if (v instanceof Number n && want != null) {
            double x = n.doubleValue();
            return switch (op) {
                case "=" -> Math.abs(x - want) < 1e-9;
                case "!=" -> Math.abs(x - want) >= 1e-9;
                case "<" -> x < want;
                case "<=" -> x <= want;
                case ">" -> x > want;
                default -> x >= want;
            };
        }
        String a = String.valueOf(v).trim(), b = raw.trim();
        return switch (op) {
            case "=" -> a.equalsIgnoreCase(b);
            case "!=" -> !a.equalsIgnoreCase(b);
            default -> false;
        };
    }

    static String canonical(String field) {
        for (String k : FIELDS.keySet()) if (k.equalsIgnoreCase(field)) return k;
        return null;
    }

    static Double number(String s) {
        String x = s.trim().replace("₹", "").replace(",", "").replace(" ", "").toLowerCase();
        double mult = 1;
        if (x.endsWith("lakh")) { mult = 100_000; x = x.substring(0, x.length() - 4); }
        else if (x.endsWith("l")) { mult = 100_000; x = x.substring(0, x.length() - 1); }
        else if (x.endsWith("cr")) { mult = 10_000_000; x = x.substring(0, x.length() - 2); }
        try { return Double.parseDouble(x) * mult; } catch (NumberFormatException e) { return null; }
    }

    private static List<String> split(String s, String word) {
        return Arrays.asList(s.split("(?i)\\s+" + word + "\\s+"));
    }
}
