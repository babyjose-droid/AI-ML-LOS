package com.rhythm.los.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/** Time-based one-time passwords (RFC 6238, 30 s, 6 digits, SHA-1) for staff MFA with any authenticator app. */
public final class Totp {
    private Totp() {}
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RNG = new SecureRandom();

    public static String newSecret() {
        byte[] b = new byte[20];
        RNG.nextBytes(b);
        return base32(b);
    }

    public static String uri(String issuer, String account, String secret) {
        String i = java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String a = java.net.URLEncoder.encode(account, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + i + ":" + a + "?secret=" + secret + "&issuer=" + i + "&digits=6&period=30";
    }

    /** Accepts the current code and one step either side (clock drift). */
    public static boolean verify(String secret, String code, long epochSeconds) {
        if (secret == null || code == null || !code.matches("\\d{6}")) return false;
        long step = epochSeconds / 30;
        for (long s = step - 1; s <= step + 1; s++) if (code(secret, s).equals(code)) return true;
        return false;
    }

    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        s = s.replace("=", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Bad base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) { out.put((byte) (buffer >> (bits - 8))); bits -= 8; }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
