package com.rhythm.los.document;

import java.util.*;

/** Name similarity tolerant of case, order, initials and small OCR errors. Returns 0..1. */
public final class NameMatch {
    private NameMatch() {}

    public static double score(String a, String b) {
        List<String> x = tokens(a), y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        List<String> small = x.size() <= y.size() ? x : y, big = x.size() <= y.size() ? y : x;
        List<String> pool = new ArrayList<>(big);
        double matched = 0;
        for (String t : small) {
            String best = null;
            double bestScore = 0;
            for (String u : pool) {
                double s = t.equals(u) ? 1 : (t.length() == 1 || u.length() == 1) && t.charAt(0) == u.charAt(0) ? 0.9 : ratio(t, u);
                if (s > bestScore) { bestScore = s; best = u; }
            }
            if (best != null && bestScore >= 0.8) { matched += bestScore; pool.remove(best); }
        }
        double tokenScore = matched / big.size();
        double charScore = ratio(String.join(" ", sorted(x)), String.join(" ", sorted(y)));
        return Math.round(Math.max(tokenScore, charScore * 0.95) * 1000) / 1000.0;
    }

    static List<String> tokens(String s) {
        if (s == null) return List.of();
        String n = s.toUpperCase().replaceAll("[^A-Z ]", " ").replaceAll("\\b(MR|MRS|MS|SHRI|SMT|KUM|DR)\\b", " ").trim();
        if (n.isEmpty()) return List.of();
        return Arrays.asList(n.split("\\s+"));
    }

    private static List<String> sorted(List<String> l) {
        List<String> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    static double ratio(String a, String b) {
        double d = levenshtein(a, b);
        int m = Math.max(a.length(), b.length());
        return m == 0 ? 1 : 1.0 - d / m;
    }

    /** Characters OCR confuses cost less to substitute. */
    private static final String[] CONFUSABLE = {"IL1", "O0Q", "S5", "B8", "Z2", "G6"};

    static double subCost(char x, char y) {
        if (x == y) return 0;
        for (String g : CONFUSABLE) if (g.indexOf(x) >= 0 && g.indexOf(y) >= 0) return 0.25;
        return 1;
    }

    static double levenshtein(String a, String b) {
        double[] prev = new double[b.length() + 1], cur = new double[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                double c = subCost(a.charAt(i - 1), b.charAt(j - 1));
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            double[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
