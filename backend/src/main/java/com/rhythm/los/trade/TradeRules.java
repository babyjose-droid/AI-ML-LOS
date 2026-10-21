package com.rhythm.los.trade;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Trade finance rules, pure. Invoice checks TF-01 to TF-12 decide whether a drawdown can be funded; limit-structure checks
 * LS-01 to LS-04 decide whether the programme and buyer sublimits of an application are ready for underwriting.
 *
 * Kinds: RECEIVABLES (the borrower sells to rated buyers, the buyer pays), VENDOR (the borrower supplies an anchor, the anchor
 * pays), DEALER (the anchor sells to the borrower, the lender pays the anchor, the borrower repays).
 */
public final class TradeRules {
    private TradeRules() {}

    public record Check(String id, String label, String result, String reason, String ruling) {
        public boolean blocks() { return "FAIL".equals(result) || "REFER".equals(result) && ruling == null || "PENDING".equals(result); }
    }

    public record Party(Long id, String name, String kind, String programme, String gstin, String status, boolean empanelled,
                        double advanceRate, int maxTenorDays, int graceDays, boolean confirmationRequired, double concentrationCap,
                        Double programmeLimit, double programmeUsed) {}

    /** utilised: principal outstanding on the limit, without this invoice. buyerOutstanding: this borrower's outstanding on this buyer. */
    public record LimitFacts(String kind, String status, LocalDate validTo, double limit, double utilised, Long anchorId, String borrowerGstin,
                             Double sublimit, double buyerOutstanding, int worstOverdueDays, String worstOverdueRef) {}

    public record InvoiceFacts(String invoiceNo, LocalDate invoiceDate, LocalDate dueDate, String sellerGstin, String buyerGstin,
                               Double base, Double gst, Double total, String duplicateOf, boolean confirmed, Map<String, Object> read) {}

    public record Result(String status, double advance, double available, double advanceRate, List<Check> checks) {}

    public static final Set<String> HARD = Set.of("TF-01", "TF-03", "TF-04", "TF-08");
    public static final int STALE_DAYS = 60;

    static final Map<String, String> LABEL = new LinkedHashMap<>();
    static {
        LABEL.put("TF-01", "Limit active and within its validity");
        LABEL.put("TF-02", "Buyer or anchor approved for this limit");
        LABEL.put("TF-03", "Invoice not financed before (number and file)");
        LABEL.put("TF-04", "Seller and buyer GSTINs are the right parties'");
        LABEL.put("TF-05", "Invoice adds up (taxable value + GST = total)");
        LABEL.put("TF-06", "Invoice is recent (not dated ahead, not stale)");
        LABEL.put("TF-07", "Due date in the future and within the maximum tenor");
        LABEL.put("TF-08", "Advance within the available limit and the programme");
        LABEL.put("TF-09", "Within the buyer sublimit and concentration cap");
        LABEL.put("TF-10", "Invoice confirmed by the buyer or anchor");
        LABEL.put("TF-11", "No drawdown overdue beyond the grace period");
        LABEL.put("TF-12", "Invoice read by AI matches the details entered");
    }

    static Check c(String id, String result, String reason) { return new Check(id, LABEL.get(id), result, reason, null); }

    public static Result evaluate(InvoiceFacts f, LimitFacts l, Party p, Map<String, Map<String, Object>> rulings, LocalDate today) {
        List<Check> cs = new ArrayList<>();
        double rate = p == null ? 0 : p.advanceRate();
        double total = f.total() == null ? 0 : f.total();
        double advance = Math.floor(total * rate);
        double available = Math.max(0, l.limit() - l.utilised());

        // TF-01
        if (!"ACTIVE".equals(l.status())) cs.add(c("TF-01", "FAIL", "Limit is " + l.status().toLowerCase()));
        else if (l.validTo() != null && l.validTo().isBefore(today)) cs.add(c("TF-01", "FAIL", "Limit expired on " + l.validTo()));
        else cs.add(c("TF-01", "PASS", "Active to " + l.validTo()));

        // TF-02
        boolean programme = "VENDOR".equals(l.kind()) || "DEALER".equals(l.kind());
        if (p == null) cs.add(c("TF-02", "PENDING", programme ? "Anchor not set" : "Choose the buyer"));
        else if (!"ACTIVE".equals(p.status())) cs.add(c("TF-02", "FAIL", p.name() + " is " + p.status().toLowerCase()));
        else if (programme && !Objects.equals(p.id(), l.anchorId())) cs.add(c("TF-02", "FAIL", p.name() + " is not this limit's anchor"));
        else if (!programme && l.sublimit() == null) cs.add(c("TF-02", "REFER", "No sublimit on " + p.name() + " in this limit"));
        else if (!p.empanelled()) cs.add(c("TF-02", "REFER", p.name() + " is not empanelled"));
        else cs.add(c("TF-02", "PASS", p.name() + (programme ? " (" + p.programme().toLowerCase() + " programme)" : "") + " approved"));

        // TF-03
        cs.add(f.duplicateOf() != null ? c("TF-03", "FAIL", "Invoice " + f.invoiceNo() + " is already on " + f.duplicateOf())
                : f.invoiceNo() == null ? c("TF-03", "PENDING", "Invoice number needed") : c("TF-03", "PASS", "Not seen on any other drawdown"));

        // TF-04
        String expSeller = "DEALER".equals(l.kind()) ? p == null ? null : p.gstin() : l.borrowerGstin();
        String expBuyer = "DEALER".equals(l.kind()) ? l.borrowerGstin() : p == null ? null : p.gstin();
        String sellerWho = "DEALER".equals(l.kind()) ? "the anchor" : "the borrower", buyerWho = "DEALER".equals(l.kind()) ? "the borrower" : "the buyer";
        if (f.sellerGstin() == null || f.buyerGstin() == null) cs.add(c("TF-04", "PENDING", "Seller and buyer GSTINs needed"));
        else if (expSeller != null && !norm(expSeller).equals(norm(f.sellerGstin())))
            cs.add(c("TF-04", "FAIL", "Seller GSTIN " + f.sellerGstin() + " is not " + sellerWho + "'s (" + expSeller + ")"));
        else if (expBuyer != null && !norm(expBuyer).equals(norm(f.buyerGstin())))
            cs.add(c("TF-04", "FAIL", "Buyer GSTIN " + f.buyerGstin() + " is not " + buyerWho + "'s (" + expBuyer + ")"));
        else cs.add(c("TF-04", "PASS", "Seller " + f.sellerGstin() + ", buyer " + f.buyerGstin()));

        // TF-05
        if (f.base() == null || f.total() == null) cs.add(c("TF-05", "PENDING", "Taxable value and total needed"));
        else {
            double sum = f.base() + n(f.gst());
            cs.add(Math.abs(sum - f.total()) <= 1 ? c("TF-05", "PASS", rupees(f.base()) + " + GST " + rupees(n(f.gst())) + " = " + rupees(f.total()))
                    : c("TF-05", "REFER", "Taxable value + GST = " + rupees(sum) + ", invoice total " + rupees(f.total())));
        }

        // TF-06
        if (f.invoiceDate() == null) cs.add(c("TF-06", "PENDING", "Invoice date needed"));
        else if (f.invoiceDate().isAfter(today)) cs.add(c("TF-06", "REFER", "Dated " + f.invoiceDate() + ", after today"));
        else {
            long age = ChronoUnit.DAYS.between(f.invoiceDate(), today);
            cs.add(age > STALE_DAYS ? c("TF-06", "REFER", age + " days old (more than " + STALE_DAYS + ")") : c("TF-06", "PASS", "Dated " + f.invoiceDate() + " (" + age + " days ago)"));
        }

        // TF-07
        int maxTenor = p == null ? 90 : p.maxTenorDays();
        if (f.dueDate() == null) cs.add(c("TF-07", "PENDING", "Due date needed"));
        else if (!f.dueDate().isAfter(today)) cs.add(c("TF-07", "REFER", "Already due on " + f.dueDate()));
        else if (f.invoiceDate() != null && ChronoUnit.DAYS.between(f.invoiceDate(), f.dueDate()) > maxTenor)
            cs.add(c("TF-07", "REFER", ChronoUnit.DAYS.between(f.invoiceDate(), f.dueDate()) + " days' credit, above the " + maxTenor + " allowed"));
        else cs.add(c("TF-07", "PASS", "Due " + f.dueDate() + " (" + ChronoUnit.DAYS.between(today, f.dueDate()) + " days)"));

        // TF-08
        if (total <= 0) cs.add(c("TF-08", "PENDING", "Invoice total needed"));
        else if (advance > available + 0.5) cs.add(c("TF-08", "FAIL", "Advance " + rupees(advance) + " is above the available " + rupees(available)));
        else if (p != null && p.programmeLimit() != null && programme && p.programmeUsed() + advance > p.programmeLimit())
            cs.add(c("TF-08", "FAIL", p.name() + "'s programme would be " + rupees(p.programmeUsed() + advance) + " (limit " + rupees(p.programmeLimit()) + ")"));
        else cs.add(c("TF-08", "PASS", "Advance " + rupees(advance) + " (" + Math.round(rate * 100) + "% of " + rupees(total) + ") of " + rupees(available) + " available"));

        // TF-09
        if (programme) cs.add(c("TF-09", "PASS", "Single-anchor programme"));
        else if (p == null || total <= 0) cs.add(c("TF-09", "PENDING", "Buyer and amount needed"));
        else {
            List<String> why = new ArrayList<>();
            double after = l.buyerOutstanding() + advance;
            if (l.sublimit() != null && after > l.sublimit() + 0.5) why.add("on " + p.name() + " would be " + rupees(after) + " (sublimit " + rupees(l.sublimit()) + ")");
            if (l.limit() > 0 && after / l.limit() > p.concentrationCap() + 1e-9)
                why.add(Math.round(after / l.limit() * 100) + "% of the limit on one buyer (cap " + Math.round(p.concentrationCap() * 100) + "%)");
            cs.add(why.isEmpty() ? c("TF-09", "PASS", rupees(after) + " on " + p.name() + (l.sublimit() != null ? " of " + rupees(l.sublimit()) + " sublimit" : ""))
                    : c("TF-09", "REFER", String.join("; ", why)));
        }

        // TF-10
        if (p != null && !p.confirmationRequired()) cs.add(c("TF-10", "PASS", "Confirmation not required for " + p.name()));
        else cs.add(f.confirmed() ? c("TF-10", "PASS", "Confirmed by " + (p == null ? "the counterparty" : p.name()))
                : c("TF-10", "PENDING", "Awaiting confirmation from " + (p == null ? "the counterparty" : p.name())));

        // TF-11
        int grace = p == null ? 7 : p.graceDays();
        cs.add(l.worstOverdueDays() > grace ? c("TF-11", "REFER", l.worstOverdueRef() + " is " + l.worstOverdueDays() + " days overdue" + ("DEALER".equals(l.kind()) ? " (stop supply)" : ""))
                : c("TF-11", "PASS", l.worstOverdueDays() > 0 ? l.worstOverdueRef() + " " + l.worstOverdueDays() + " days late, within the " + grace + "-day grace" : "Nothing overdue"));

        // TF-12
        Map<String, Object> read = f.read() == null ? Map.of() : f.read();
        if (read.isEmpty()) cs.add(c("TF-12", "PASS", "Entered by staff (no AI reading)"));
        else {
            List<String> diff = new ArrayList<>();
            if (read.get("invoiceNo") != null && f.invoiceNo() != null && !norm(read.get("invoiceNo").toString()).equals(norm(f.invoiceNo()))) diff.add("invoice no. " + read.get("invoiceNo"));
            if (read.get("total") instanceof Number t && f.total() != null && Math.abs(t.doubleValue() - f.total()) > 1) diff.add("total " + rupees(t.doubleValue()));
            if (read.get("sellerGstin") != null && f.sellerGstin() != null && !norm(read.get("sellerGstin").toString()).equals(norm(f.sellerGstin()))) diff.add("seller GSTIN " + read.get("sellerGstin"));
            if (read.get("buyerGstin") != null && f.buyerGstin() != null && !norm(read.get("buyerGstin").toString()).equals(norm(f.buyerGstin()))) diff.add("buyer GSTIN " + read.get("buyerGstin"));
            if (read.get("dueDate") != null && f.dueDate() != null && !read.get("dueDate").toString().equals(f.dueDate().toString())) diff.add("due date " + read.get("dueDate"));
            cs.add(diff.isEmpty() ? c("TF-12", "PASS", "Number, total, both GSTINs and due date match the invoice") : c("TF-12", "REFER", "The invoice reads differently: " + String.join(", ", diff)));
        }

        Map<String, Map<String, Object>> rule = rulings == null ? Map.of() : rulings;
        List<Check> out = new ArrayList<>();
        for (Check ch : cs) {
            Map<String, Object> r = rule.get(ch.id());
            out.add(r != null && "REFER".equals(ch.result()) && !HARD.contains(ch.id())
                    ? new Check(ch.id(), ch.label(), ch.result(), ch.reason(), "Exception approved by " + r.get("by") + ": " + r.get("note")) : ch);
        }
        String status = out.stream().anyMatch(x -> "FAIL".equals(x.result())) ? "FAIL"
                : out.stream().anyMatch(x -> "REFER".equals(x.result()) && x.ruling() == null) ? "REFER"
                : out.stream().anyMatch(x -> "PENDING".equals(x.result())) ? "PENDING" : "PASS";
        return new Result(status, advance, available, rate, out);
    }

    // ---------------------------------------------------------------- the limit structure (before underwriting)

    public record SubFacts(Party buyer, double amount) {}

    public static List<Check> structure(String kind, double limit, String borrowerGstin, String pan, Party anchor, List<SubFacts> subs) {
        List<Check> cs = new ArrayList<>();
        boolean programme = "VENDOR".equals(kind) || "DEALER".equals(kind);
        if (programme) {
            if (anchor == null) cs.add(new Check("LS-01", "Anchor programme", "PENDING", "Choose the anchor", null));
            else if (!"ACTIVE".equals(anchor.status())) cs.add(new Check("LS-01", "Anchor programme", "FAIL", anchor.name() + " is " + anchor.status().toLowerCase(), null));
            else if (!kind.equals(anchor.programme())) cs.add(new Check("LS-01", "Anchor programme", "FAIL", anchor.name() + " runs a " + String.valueOf(anchor.programme()).toLowerCase() + " programme, not " + kind.toLowerCase(), null));
            else cs.add(new Check("LS-01", "Anchor programme", "PASS", anchor.name() + ": " + kind.toLowerCase() + " programme, " + Math.round(anchor.advanceRate() * 100) + "% advance, " + anchor.maxTenorDays() + " days", null));
            if (anchor != null && anchor.programmeLimit() != null)
                cs.add(anchor.programmeUsed() + limit > anchor.programmeLimit()
                        ? new Check("LS-02", "Programme headroom", "FAIL", "Limits under " + anchor.name() + " would be " + rupees(anchor.programmeUsed() + limit) + " of " + rupees(anchor.programmeLimit()), null)
                        : new Check("LS-02", "Programme headroom", "PASS", rupees(anchor.programmeUsed() + limit) + " of " + rupees(anchor.programmeLimit()) + " used with this limit", null));
        } else {
            if (subs.isEmpty()) cs.add(new Check("LS-03", "Buyer sublimits", "PENDING", "Add at least one buyer and its sublimit", null));
            for (SubFacts s : subs) {
                Party b = s.buyer();
                String id = "LS-03";
                if (!"ACTIVE".equals(b.status())) cs.add(new Check(id, "Buyer " + b.name(), "FAIL", b.name() + " is " + b.status().toLowerCase(), null));
                else if (s.amount() > limit + 0.5) cs.add(new Check(id, "Buyer " + b.name(), "FAIL", "Sublimit " + rupees(s.amount()) + " is above the limit", null));
                else if (!b.empanelled()) cs.add(new Check(id, "Buyer " + b.name(), "REFER", b.name() + " is not empanelled: credit to approve", null));
                else cs.add(new Check(id, "Buyer " + b.name(), "PASS", rupees(s.amount()) + (b.programmeLimit() != null ? " · buyer exposure " + rupees(b.programmeUsed() + s.amount()) + " of " + rupees(b.programmeLimit()) : ""), null));
            }
        }
        String g = borrowerGstin == null ? "" : norm(borrowerGstin);
        if (g.isEmpty()) cs.add(new Check("LS-04", "Borrower GSTIN", "PENDING", "Enter the borrower's GSTIN", null));
        else if (!g.matches("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) cs.add(new Check("LS-04", "Borrower GSTIN", "FAIL", g + " is not a valid GSTIN", null));
        else if (pan != null && !g.substring(2, 12).equalsIgnoreCase(pan)) cs.add(new Check("LS-04", "Borrower GSTIN", "FAIL", "GSTIN " + g + " is not on the borrower's PAN", null));
        else cs.add(new Check("LS-04", "Borrower GSTIN", "PASS", g + " on the borrower's PAN", null));
        return cs;
    }

    public static boolean structureOk(List<Check> cs) { return cs.stream().noneMatch(x -> "FAIL".equals(x.result()) || "PENDING".equals(x.result())); }

    // ---------------------------------------------------------------- helpers

    static double n(Double d) { return d == null ? 0 : d; }
    static String norm(String s) { return s == null ? "" : s.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT); }

    public static String rupees(double v) {
        long r = Math.round(v);
        String s = Long.toString(Math.abs(r));
        if (s.length() > 3) {
            String head = s.substring(0, s.length() - 3), tail = s.substring(s.length() - 3);
            StringBuilder b = new StringBuilder();
            while (head.length() > 2) { b.insert(0, "," + head.substring(head.length() - 2)); head = head.substring(0, head.length() - 2); }
            s = head + b + "," + tail;
        }
        return (r < 0 ? "-₹" : "₹") + s;
    }
}
