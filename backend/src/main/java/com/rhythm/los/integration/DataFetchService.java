package com.rhythm.los.integration;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.integration.VendorModels.*;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/** KYC and external data fetch (Account Aggregator, bureau, GST), always through the gateway. */
@Service
public class DataFetchService {
    public static final String OP_KYC = "kyc.verify";
    public static final String OP_AA = "aa.fetch";
    public static final String OP_BUREAU = "bureau.pull";
    public static final String OP_GST = "gst.profile";

    private final ApplicationService apps;
    private final IntegrationGateway gateway;
    private final DataSnapshotRepository snapshots;
    private final IntegrationLogRepository logs;
    private final PolicyService policy;
    private final AuditService audit;
    private final Vendors.KycProvider kyc;
    private final Vendors.AccountAggregator aa;
    private final Vendors.CreditBureau bureau;
    private final Vendors.GstProvider gst;

    public DataFetchService(ApplicationService apps, IntegrationGateway gateway, DataSnapshotRepository snapshots,
                            IntegrationLogRepository logs, PolicyService policy, AuditService audit,
                            Vendors.KycProvider kyc, Vendors.AccountAggregator aa, Vendors.CreditBureau bureau, Vendors.GstProvider gst) {
        this.apps = apps;
        this.gateway = gateway;
        this.snapshots = snapshots;
        this.logs = logs;
        this.policy = policy;
        this.audit = audit;
        this.kyc = kyc;
        this.aa = aa;
        this.bureau = bureau;
        this.gst = gst;
    }

    private static final Set<String> ACTIVE = Set.of("SUBMITTED", "UNDERWRITING");

    private LoanApplication requireActive(Long id) {
        LoanApplication a = apps.get(id);
        if (!ACTIVE.contains(a.getAppState())) {
            throw ApiException.conflict("WRONG_STAGE", "Application must be submitted first (now " + a.getAppState() + ")");
        }
        return a;
    }

    @Transactional
    public LoanApplication runKyc(Long id, CurrentUser by) {
        LoanApplication a = requireActive(id);
        if (!"NOT_STARTED".equals(a.getKycState())) throw ApiException.conflict("ALREADY_DONE", "KYC already run: " + a.getKycState());
        var out = gateway.call(a.getId(), kyc.name(), OP_KYC, () -> kyc.verify(a),
                k -> "name " + k.nameMatch() + ", address " + k.addressMatch() + ", contact " + k.contactMatch(), null);
        if (!out.ok()) throw ApiException.conflict("VENDOR_FAILED", "KYC provider failed: " + out.error());
        KycBundle k = out.value();
        snapshots.save(new DataSnapshot(a.getId(), "KYC", kyc.name(), Json.write(k)));
        double s = idScore(k);
        var p = policy.live();
        String to = s < p.kycFail() ? "FAILED" : s < p.kycReview() ? "REVIEW" : "VERIFIED";
        apps.transition(a, Domain.KYC, to, "kyc.completed", by, "Identity match " + Math.round(s * 100) + "%");
        return a;
    }

    /** After video KYC or a document re-check, an operations user can clear or fail a KYC review. */
    @Transactional
    public LoanApplication resolveKyc(Long id, boolean verified, String note, CurrentUser by) {
        LoanApplication a = apps.get(id);
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        apps.transition(a, Domain.KYC, verified ? "VERIFIED" : "FAILED", verified ? "kyc.review_cleared" : "kyc.review_failed", by, note);
        return a;
    }

    public static double idScore(KycBundle k) {
        return 0.35 * k.nameMatch() + 0.20 * k.dobMatch() + 0.20 * k.idMatch() + 0.15 * k.addressMatch() + 0.10 * k.contactMatch();
    }

    @Transactional
    public LoanApplication fetchData(Long id, CurrentUser by) {
        LoanApplication a = requireActive(id);
        if ("FETCHED".equals(a.getDataState())) throw ApiException.conflict("ALREADY_DONE", "Data already fetched");
        boolean aaOk = has(a, "AA") || fetchAa(a, null);
        boolean buOk = has(a, "BUREAU") || fetchBureau(a, null);
        if ("MSME".equals(a.getSegment()) && !has(a, "GST")) fetchGst(a, null);
        updateDataState(a, aaOk, buOk, by);
        return a;
    }

    /** Manual retry of a dead-lettered call from the integration log screen. */
    @Transactional
    public LoanApplication retry(Long logId, CurrentUser by) {
        IntegrationLog row = logs.findById(logId).orElseThrow(() -> ApiException.notFound("Integration log " + logId));
        if (!"DLQ".equals(row.getStatus())) throw ApiException.conflict("NOT_IN_DLQ", "Only dead-lettered calls can be retried");
        LoanApplication a = apps.get(row.getApplicationId());
        boolean ok = switch (row.getOperation()) {
            case OP_AA -> fetchAa(a, row.getId());
            case OP_BUREAU -> fetchBureau(a, row.getId());
            case OP_GST -> fetchGst(a, row.getId());
            default -> throw ApiException.unprocessable("NOT_RETRYABLE", "Operation " + row.getOperation() + " cannot be retried here");
        };
        if (ok) {
            for (IntegrationLog l : logs.findByApplicationIdAndOperationAndStatus(a.getId(), row.getOperation(), "DLQ")) {
                l.setStatus("RESOLVED");
                logs.save(l);
            }
            audit.record(by, "integration.retry_succeeded", "INTEGRATION", String.valueOf(logId), a.getId(), row.getOperation());
        } else {
            audit.record(by, "integration.retry_failed", "INTEGRATION", String.valueOf(logId), a.getId(), row.getOperation());
        }
        if (!"FETCHED".equals(a.getDataState()) && !"NOT_FETCHED".equals(a.getDataState())) {
            updateDataState(a, has(a, "AA"), has(a, "BUREAU"), by);
        }
        return a;
    }

    private void updateDataState(LoanApplication a, boolean aaOk, boolean buOk, CurrentUser by) {
        String to = aaOk && buOk ? "FETCHED" : (aaOk || buOk) ? "PARTIAL" : "FAILED";
        if (to.equals(a.getDataState())) return;
        apps.transition(a, Domain.DATA, to, "data.fetch_" + to.toLowerCase(), by,
                "AA " + (aaOk ? "ok" : "failed") + ", bureau " + (buOk ? "ok" : "failed"));
    }

    private boolean has(LoanApplication a, String kind) {
        return snapshots.findFirstByApplicationIdAndKindOrderByIdDesc(a.getId(), kind).isPresent();
    }

    private boolean fetchAa(LoanApplication a, Long retryOf) {
        var out = gateway.call(a.getId(), aa.name(), OP_AA, () -> aa.fetchStatements(a, 12),
                s -> s.months() + " months from " + s.fipName() + " " + s.maskedAccount(), retryOf);
        if (out.ok()) snapshots.save(new DataSnapshot(a.getId(), "AA", aa.name(), Json.write(out.value())));
        return out.ok();
    }

    private boolean fetchBureau(LoanApplication a, Long retryOf) {
        var out = gateway.call(a.getId(), bureau.name(), OP_BUREAU, () -> bureau.pull(a),
                b -> b.hit() ? b.bureau() + " score " + b.score() : b.bureau() + " no hit (thin file)", retryOf);
        if (out.ok()) snapshots.save(new DataSnapshot(a.getId(), "BUREAU", bureau.name(), Json.write(out.value())));
        return out.ok();
    }

    private boolean fetchGst(LoanApplication a, Long retryOf) {
        var out = gateway.call(a.getId(), gst.name(), OP_GST, () -> Optional.ofNullable(gst.profile(a)),
                g -> g.map(x -> "GSTIN " + x.gstin()).orElse("No GST registration"), retryOf);
        if (out.ok() && out.value().isPresent()) snapshots.save(new DataSnapshot(a.getId(), "GST", gst.name(), Json.write(out.value().get())));
        return out.ok();
    }
}
