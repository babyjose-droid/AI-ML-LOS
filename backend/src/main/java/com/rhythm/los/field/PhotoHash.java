package com.rhythm.los.field;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exact (SHA-256) and perceptual (difference hash, 64 bits) fingerprints of a photo, for reuse detection. */
public final class PhotoHash {
    private PhotoHash() {}

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** dHash: 9x8 greyscale thumbnail, one bit per neighbouring-pixel comparison. Survives resizing and re-compression. */
    public static String dhash(byte[] b) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b));
            if (img == null) return null;
            int w = 9, h = 8;
            double[][] g = new double[h][w];
            int iw = img.getWidth(), ih = img.getHeight();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    // average of the block of source pixels behind each thumbnail pixel
                    int x0 = x * iw / w, x1 = Math.max(x0 + 1, (x + 1) * iw / w), y0 = y * ih / h, y1 = Math.max(y0 + 1, (y + 1) * ih / h);
                    double sum = 0;
                    int n = 0;
                    for (int yy = y0; yy < y1; yy += Math.max(1, (y1 - y0) / 8))
                        for (int xx = x0; xx < x1; xx += Math.max(1, (x1 - x0) / 8)) {
                            int rgb = img.getRGB(Math.min(xx, iw - 1), Math.min(yy, ih - 1));
                            sum += 0.299 * ((rgb >> 16) & 255) + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255);
                            n++;
                        }
                    g[y][x] = sum / Math.max(1, n);
                }
            long bits = 0;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < 8; x++) bits = (bits << 1) | (g[y][x] > g[y][x + 1] ? 1 : 0);
            return String.format("%016x", bits);
        } catch (Exception e) {
            return null;
        }
    }

    public static int distance(String a, String b) {
        if (a == null || b == null) return 64;
        return Long.bitCount(Long.parseUnsignedLong(a, 16) ^ Long.parseUnsignedLong(b, 16));
    }

    /** Metres between two points (haversine). */
    public static double metres(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
