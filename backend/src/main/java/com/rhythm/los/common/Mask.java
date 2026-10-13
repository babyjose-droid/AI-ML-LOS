package com.rhythm.los.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Masking for identifiers (FSD 23): PAN and account numbers appear only masked in screens, API responses, logs and
 * stored payloads. Full values stay inside the service for matching and for vendor calls.
 */
public final class Mask {
    private Mask() {}

    /** ABCPK1234L → ABxxxxxx4L */
    public static String pan(String pan) {
        if (pan == null || pan.length() < 4) return pan;
        return pan.substring(0, 2) + "x".repeat(pan.length() - 4) + pan.substring(pan.length() - 2);
    }

    /** 501001002002 → xxxxxxxx2002 */
    public static String account(String acc) {
        if (acc == null || acc.length() <= 4) return acc;
        return "x".repeat(acc.length() - 4) + acc.substring(acc.length() - 4);
    }

    private static final java.util.regex.Pattern PAN_RX = java.util.regex.Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");
    private static final java.util.regex.Pattern AADHAAR_RX = java.util.regex.Pattern.compile("\\b[2-9][0-9]{3}[ -]?[0-9]{4}[ -]?([0-9]{4})\\b");
    private static final java.util.regex.Pattern ACCOUNT_RX = java.util.regex.Pattern.compile("\\b[0-9]{9,18}\\b");

    /** Masks PANs, Aadhaar numbers (last 4 kept) and long account numbers inside free text such as vendor log bodies. */
    public static String text(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = PAN_RX.matcher(s).replaceAll(m -> pan(m.group()));
        out = AADHAAR_RX.matcher(out).replaceAll(m -> "XXXX XXXX " + m.group(1));
        out = ACCOUNT_RX.matcher(out).replaceAll(m -> account(m.group()));
        return out;
    }

    public static final class PanSerializer extends JsonSerializer<String> {
        @Override public void serialize(String v, JsonGenerator g, SerializerProvider p) throws IOException { g.writeString(pan(v)); }
    }

    public static final class AccountSerializer extends JsonSerializer<String> {
        @Override public void serialize(String v, JsonGenerator g, SerializerProvider p) throws IOException { g.writeString(account(v)); }
    }
}
