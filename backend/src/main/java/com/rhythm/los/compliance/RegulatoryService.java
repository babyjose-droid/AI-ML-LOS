package com.rhythm.los.compliance;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.*;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.org.Branch;
import com.rhythm.los.org.BranchRepository;
import com.rhythm.los.risk.FairnessService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Regulatory returns from the loan book: asset classification and provisioning, NPA movement, exposure
 * concentration and the credit information (CIC) submission file.
 */
@Service
public class RegulatoryService {
    private final LiveLoanRepository loans;
    private final LoanSnapshotRepository snaps;
    private final LoanApplicationRepository apps;
    private final BranchRepository branches;
    private final LoanBookService book;
    private final AuditService audit;

    public RegulatoryService(LiveLoanRepository loans, LoanSnapshotRepository snaps, LoanApplicationRepository apps, BranchRepository branches,
                             LoanBookService book, AuditService audit) {
        this.loans = loans;
        this.snaps = snaps;
        this.apps = apps;
        this.branches = branches;
        this.book = book;
        this.audit = audit;
    }

    /** RBI sub-classes of NPA by age: sub-standard up to 12 months, doubtful D1 (up to 1 year doubtful), D2 (1-3), D3 (above 3). */
    public static String npaClass(LocalDate npaSince, LocalDate on) {
        long m = npaSince == null ? 0 : ChronoUnit.MONTHS.between(npaSince, on);
        return m <= 12 ? "SUB-STANDARD" : m <= 24 ? "DOUBTFUL-1" : m <= 48 ? "DOUBTFUL-2" : "DOUBTFUL-3";
    }

    public Map<String, Object> assetClassification(CurrentUser u) {
        Map<String, Object> s = new LinkedHashMap<>(book.summary(u));
        LocalDate on = LocalDate.now();
        Map<String, double[]> npa = new LinkedHashMap<>();
        for (String c : List.of("SUB-STANDARD", "DOUBTFUL-1", "DOUBTFUL-2", "DOUBTFUL-3")) npa.put(c, new double[3]);
        for (LiveLoan l : book.visible(u)) {
            if (!"ACTIVE".equals(l.getStatus()) || !"NPA".equals(l.getClassification())) continue;
            double[] v = npa.get(npaClass(l.getNpaSince(), LoanBookService.asOf(l)));
            v[0]++; v[1] += l.getOutstanding().doubleValue(); v[2] += l.getProvisionIracp().doubleValue();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        npa.forEach((k, v) -> rows.add(Map.of("class", k, "loans", (int) v[0], "outstanding", Math.round(v[1]), "provisionIracp", Math.round(v[2]))));
        s.put("npaByAge", rows);
        s.put("asOf", on.toString());
        return s;
    }

    record Move(LiveLoan loan, LoanSnapshot open, LoanSnapshot close) {}

    public Map<String, Object> npaMovement(LocalDate from, LocalDate to, CurrentUser u) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.unprocessable("BAD_PERIOD", "Give a period: from and to (YYYY-MM-DD), to on or after from");
        Map<Long, List<LoanSnapshot>> byLoan = snaps.findAll().stream().collect(Collectors.groupingBy(LoanSnapshot::getLoanId));
        double opening = 0, additions = 0, upgraded = 0, recovered = 0, writtenOff = 0, reduction = 0, closing = 0;
        int nOpen = 0, nAdd = 0, nUp = 0, nRec = 0, nWo = 0, nClose = 0;
        for (LiveLoan l : book.visible(u)) {
            List<LoanSnapshot> h = byLoan.getOrDefault(l.getId(), List.of());
            LoanSnapshot o = h.stream().filter(s -> s.getFeedDate().isBefore(from)).max(Comparator.comparing(LoanSnapshot::getFeedDate)).orElse(null);
            LoanSnapshot c = h.stream().filter(s -> !s.getFeedDate().isAfter(to)).max(Comparator.comparing(LoanSnapshot::getFeedDate)).orElse(null);
            boolean wasNpa = o != null && "NPA".equals(o.getClassification()), isNpa = c != null && "NPA".equals(c.getClassification());
            double ob = o == null ? 0 : o.getOutstanding().doubleValue(), cb = c == null ? 0 : c.getOutstanding().doubleValue();
            if (wasNpa) { opening += ob; nOpen++; }
            if (isNpa) { closing += cb; nClose++; }
            if (!wasNpa && isNpa) { additions += cb; nAdd++; }
            if (wasNpa && !isNpa) {
                if ("WRITTEN_OFF".equals(l.getStatus())) { writtenOff += ob; nWo++; }
                else if (cb < 1) { recovered += ob; nRec++; }
                else { upgraded += ob; nUp++; }
            }
            if (wasNpa && isNpa) reduction += Math.max(0, ob - cb) - Math.max(0, cb - ob);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("opening", Map.of("loans", nOpen, "amount", Math.round(opening)));
        m.put("additions", Map.of("loans", nAdd, "amount", Math.round(additions)));
        m.put("upgradations", Map.of("loans", nUp, "amount", Math.round(upgraded)));
        m.put("recoveries", Map.of("loans", nRec, "amount", Math.round(recovered)));
        m.put("writeOffs", Map.of("loans", nWo, "amount", Math.round(writtenOff)));
        m.put("netReductionInExisting", Math.round(reduction));
        m.put("closing", Map.of("loans", nClose, "amount", Math.round(closing)));
        return m;
    }

    String region(LiveLoan l, Map<Long, LoanApplication> byId, Map<Long, Branch> br) {
        LoanApplication a = l.getApplicationId() == null ? null : byId.get(l.getApplicationId());
        if (a != null && a.getPincode() != null && !a.getPincode().isBlank()) return FairnessService.region(a.getPincode());
        if (l.getBranchId() != null && br.containsKey(l.getBranchId())) return "Branch " + br.get(l.getBranchId()).getCity();
        return "Not known (LMS loan without an address)";
    }

    public Map<String, Object> exposure(CurrentUser u) {
        List<LiveLoan> act = book.visible(u).stream().filter(l -> "ACTIVE".equals(l.getStatus())).toList();
        double total = act.stream().mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
        Map<Long, LoanApplication> byId = apps.findAll().stream().collect(Collectors.toMap(LoanApplication::getId, a -> a));
        Map<Long, Branch> br = branches.findAll().stream().collect(Collectors.toMap(Branch::getId, b -> b));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", Math.round(total));
        m.put("loans", act.size());
        m.put("bySegment", group(act, LiveLoan::getSegment, total));
        m.put("byProduct", group(act, LiveLoan::getProductCode, total));
        m.put("byRegion", group(act, l -> region(l, byId, br), total));
        // single-borrower concentration: the 20 largest
        Map<String, Double> byBorrower = act.stream().collect(Collectors.groupingBy(l -> l.getApplicationId() != null && byId.containsKey(l.getApplicationId())
                ? Objects.toString(byId.get(l.getApplicationId()).getPan(), l.getLoanRef()) : l.getLoanRef(), Collectors.summingDouble(l -> l.getOutstanding().doubleValue())));
        List<Map.Entry<String, Double>> top = byBorrower.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(20).toList();
        double topSum = top.stream().mapToDouble(Map.Entry::getValue).sum();
        m.put("top20Share", total == 0 ? 0 : Math.round(10000 * topSum / total) / 10000.0);
        m.put("largestShare", total == 0 || top.isEmpty() ? 0 : Math.round(10000 * top.get(0).getValue() / total) / 10000.0);
        return m;
    }

    static List<Map<String, Object>> group(List<LiveLoan> act, java.util.function.Function<LiveLoan, String> key, double total) {
        Map<String, List<LiveLoan>> g = act.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> rows = new ArrayList<>();
        g.forEach((k, ls) -> {
            double os = ls.stream().mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
            double npa = ls.stream().filter(l -> "NPA".equals(l.getClassification())).mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
            rows.add(Map.of("key", k, "loans", ls.size(), "outstanding", Math.round(os), "share", total == 0 ? 0 : Math.round(10000 * os / total) / 10000.0,
                    "gnpa", os == 0 ? 0 : Math.round(10000 * npa / os) / 10000.0));
        });
        rows.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("outstanding")).reversed());
        return rows;
    }

    static final String CIC_HEAD = "MemberReference,AccountNumber,BorrowerName,PAN,DateOfBirth,DateOpened,SanctionedAmount,CurrentBalance,AmountOverdue,DaysPastDue,AssetClassification,AccountStatus,Restructured,DateReported";

    /** Credit information submission file (fortnightly) for every loan in the book, with a check of rows the bureaus will reject. */
    public Map<String, Object> cic(LocalDate asOf, CurrentUser u, boolean withFile) {
        LocalDate on = asOf == null ? LocalDate.now() : asOf;
        Map<Long, LoanApplication> byId = apps.findAll().stream().collect(Collectors.toMap(LoanApplication::getId, a -> a));
        StringBuilder b = new StringBuilder(CIC_HEAD).append('\n');
        int rows = 0, missingPan = 0, missingDob = 0;
        for (LiveLoan l : book.visible(u)) {
            if (l.getDisbursedOn().isAfter(on)) continue;
            LoanApplication a = l.getApplicationId() == null ? null : byId.get(l.getApplicationId());
            String pan = a == null ? "" : Objects.toString(a.getPan(), "");
            String dob = a == null || a.getDob() == null ? "" : a.getDob().toString();
            if (pan.isEmpty()) missingPan++;
            if (dob.isEmpty()) missingDob++;
            String cls = !"ACTIVE".equals(l.getStatus()) ? "" : "NPA".equals(l.getClassification()) ? npaClass(l.getNpaSince(), on) : l.getClassification();
            String status = "ACTIVE".equals(l.getStatus()) ? "OPEN" : "CLOSED".equals(l.getStatus()) ? "CLOSED" : "WRITTEN-OFF";
            b.append(String.join(",", "RHYTHM", l.getLoanRef(), CompliancePackService.q(l.getBorrowerName()), pan, dob, l.getDisbursedOn().toString(),
                    l.getSanctionedAmount().toPlainString(), l.getOutstanding().toPlainString(), l.getOverdue().toPlainString(), String.valueOf(l.getDpd()), cls, status,
                    l.isRestructured() ? "Y" : "N", on.toString())).append('\n');
            rows++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", on.toString());
        m.put("rows", rows);
        m.put("missingPan", missingPan);
        m.put("missingDob", missingDob);
        m.put("ready", rows > 0 && missingPan == 0);
        m.put("dueDates", "The 15th and the last day of each month (data as of those dates), to all four credit information companies");
        if (withFile) {
            m.put("file", b.toString());
            audit.record(u, "regulatory.cic_exported", "REPORT", "CIC-" + on, null, rows + " rows (PAN included; restricted file)");
        }
        return m;
    }
}
