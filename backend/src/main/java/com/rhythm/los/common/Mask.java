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

    public static final class PanSerializer extends JsonSerializer<String> {
        @Override public void serialize(String v, JsonGenerator g, SerializerProvider p) throws IOException { g.writeString(pan(v)); }
    }

    public static final class AccountSerializer extends JsonSerializer<String> {
        @Override public void serialize(String v, JsonGenerator g, SerializerProvider p) throws IOException { g.writeString(account(v)); }
    }
}
