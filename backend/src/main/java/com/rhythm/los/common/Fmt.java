package com.rhythm.los.common;

/** Indian number formatting (12,34,567). */
public final class Fmt {
    private Fmt() {}

    public static String inr(double x) {
        long v = Math.round(x);
        boolean neg = v < 0;
        String s = Long.toString(Math.abs(v));
        if (s.length() > 3) {
            String last3 = s.substring(s.length() - 3);
            String rest = s.substring(0, s.length() - 3);
            StringBuilder b = new StringBuilder();
            int i = rest.length();
            while (i > 2) { b.insert(0, "," + rest.substring(i - 2, i)); i -= 2; }
            b.insert(0, rest.substring(0, i));
            s = b + "," + last3;
        }
        return (neg ? "-" : "") + s;
    }

    public static String rupees(double x) { return "₹" + inr(x); }

    public static String pct(double x, int digits) {
        return String.format("%." + digits + "f%%", x * 100);
    }
}
