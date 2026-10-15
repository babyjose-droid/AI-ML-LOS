package com.rhythm.los.vkyc;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Which country an IP address belongs to, from a table of address ranges (CIDR,country). The lender loads its geo-IP
 * provider's ranges (for example a MaxMind or IP2Location export) through {@code rhythm.vkyc.ip-country-file};
 * the built-in rows are samples for testing only. Private and loopback addresses are reported as PRIVATE.
 */
public final class IpCountry {
    public record Range(long from, long to, String country) {}
    public record Result(String country, String note) {
        public boolean outsideIndia() { return country != null && !"IN".equals(country) && !"PRIVATE".equals(country); }
    }

    /** Samples only: a documentation range for each case, so tests and demos can show both outcomes. */
    public static final List<String> SAMPLES = List.of(
            "203.0.113.0/24,IN",    // TEST-NET-3, treated as an Indian address in the demo
            "198.51.100.0/24,GB",   // TEST-NET-2, treated as a UK address in the demo
            "81.2.69.0/24,GB",      // the range geo-IP vendors use in their own test data
            "8.8.8.0/24,US");

    private final List<Range> ranges = new ArrayList<>();

    public IpCountry(List<String> lines) {
        for (String l : lines) {
            if (l == null || l.isBlank() || l.startsWith("#")) continue;
            String[] p = l.split(",");
            if (p.length < 2) continue;
            Range r = parse(p[0].trim(), p[1].trim().toUpperCase());
            if (r != null) ranges.add(r);
        }
    }

    public int size() { return ranges.size(); }

    static Range parse(String cidr, String country) {
        try {
            String[] c = cidr.split("/");
            long base = v4(c[0]);
            if (base < 0) return null;
            int bits = c.length > 1 ? Integer.parseInt(c[1]) : 32;
            if (bits < 0 || bits > 32) return null;
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            long from = base & mask, to = from | (~mask & 0xFFFFFFFFL);
            return new Range(from, to, country);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** IPv4 as an unsigned number, or -1 when it is not a dotted IPv4 address. */
    static long v4(String ip) {
        String[] p = ip.trim().split("\\.");
        if (p.length != 4) return -1;
        long v = 0;
        for (String x : p) {
            if (!x.matches("\\d{1,3}")) return -1;
            int n = Integer.parseInt(x);
            if (n > 255) return -1;
            v = (v << 8) | n;
        }
        return v;
    }

    public Result lookup(String ip) {
        if (ip == null || ip.isBlank()) return new Result(null, "No IP address");
        String s = ip.trim();
        if (s.startsWith("::ffff:")) s = s.substring(7);
        long v = v4(s);
        if (v < 0) {
            try {
                InetAddress a = InetAddress.getByName(s.replaceAll("[^0-9a-fA-F:.]", ""));
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || s.toLowerCase().startsWith("fc") || s.toLowerCase().startsWith("fd"))
                    return new Result("PRIVATE", "Private network address");
            } catch (Exception ignored) { }
            return new Result(null, "IPv6 address not in the table");
        }
        if (inRange(v, "10.0.0.0/8") || inRange(v, "172.16.0.0/12") || inRange(v, "192.168.0.0/16") || inRange(v, "127.0.0.0/8") || inRange(v, "100.64.0.0/10") || inRange(v, "169.254.0.0/16"))
            return new Result("PRIVATE", "Private network address (office, VPN or proxy)");
        for (Range r : ranges) if (v >= r.from() && v <= r.to()) return new Result(r.country(), null);
        return new Result(null, "Address not in the geo-IP table");
    }

    static boolean inRange(long v, String cidr) { Range r = parse(cidr, "X"); return r != null && v >= r.from() && v <= r.to(); }
}
