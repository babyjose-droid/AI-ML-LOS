package com.rhythm.los.trade;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.collateral.CollateralAi;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.document.StorageService;
import com.rhythm.los.field.PhotoHash;
import com.rhythm.los.flow.WorkflowService;
import com.rhythm.los.integration.IntegrationGateway;
import com.rhythm.los.integration.VendorModels.PayoutResult;
import com.rhythm.los.integration.Vendors;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.org.Role;
import com.rhythm.los.product.Product;
import com.rhythm.los.product.ProductRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Trade finance: the programme and buyer sublimits of an application, the revolving limit it activates, and the invoices drawn
 * against it (read by AI, checked, confirmed by the buyer or anchor, funded maker-checker, repaid on the due date).
 */
@Service
public class TradeService {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<String> CLOSED = Set.of("REJECTED", "WITHDRAWN");
    static final Set<String> LIVE = Set.of("FUNDED", "PART_PAID");
    static final Set<Role> CAPTURE = Set.of(Role.ADMIN, Role.SALES, Role.OPERATIONS, Role.CREDIT_OFFICER, Role.CREDIT_MANAGER);
    static final Set<Role> OPS = Set.of(Role.ADMIN, Role.OPERATIONS);
    static final Set<Role> EXCEPTIONS = Set.of(Role.ADMIN, Role.CREDIT_MANAGER, Role.CRO);
    static final Set<Role> MASTERS = Set.of(Role.ADMIN, Role.OPERATIONS, Role.CRO);

    private final TradeCounterpartyRepository parties;
    private final TradeLimitRepository limits;
    private final TradeSublimitRepository subs;
    private final TradeInvoiceRepository invoices;
    private final LoanApplicationRepository apps;
    private final ProductRepository products;
    private final StorageService storage;
    private final CollateralAi ai;
    private final WorkflowService workflow;
    private final IntegrationGateway gateway;
    private final Vendors.PayoutProvider payout;
    private final DataScope scope;
    private final AuditService audit;

    public TradeService(TradeCounterpartyRepository parties, TradeLimitRepository limits, TradeSublimitRepository subs, TradeInvoiceRepository invoices,
                        LoanApplicationRepository apps, ProductRepository products, StorageService storage, CollateralAi ai, WorkflowService workflow,
                        IntegrationGateway gateway, Vendors.PayoutProvider payout, DataScope scope, AuditService audit) {
        this.parties = parties;
        this.limits = limits;
        this.subs = subs;
        this.invoices = invoices;
        this.apps = apps;
        this.products = products;
        this.storage = storage;
        this.ai = ai;
        this.workflow = workflow;
        this.gateway = gateway;
        this.payout = payout;
        this.scope = scope;
        this.audit = audit;
    }

    public record SubRequest(Long counterpartyId, BigDecimal amount) {}
    public record StructureRequest(Long anchorId, String borrowerGstin, List<SubRequest> sublimits) {}
    public record InvoiceRequest(Long counterpartyId, String invoiceNo, String invoiceDate, String dueDate, String poNo, String sellerGstin, String buyerGstin,
                                 BigDecimal baseAmount, BigDecimal gstAmount, BigDecimal total) {}
    public record PartyRequest(String code, String name, String kind, String programme, String gstin, String pan, String city, String industry, String rating,
                               BigDecimal programmeLimit, Integer maxTenorDays, BigDecimal advanceRate, Integer graceDays, String recourse, BigDecimal fldgPct,
                               Boolean confirmationRequired, BigDecimal concentrationCap, String repaidBy, String accountName, String accountNo, String ifsc,
                               Boolean empanelled, String status, String notes) {}

    // ---------------------------------------------------------------- access

    LoanApplication app(Long appId, CurrentUser u) {
        LoanApplication a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Application"));
        Set<Long> vis = scope.visibleBranches(u);
        if (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) throw ApiException.notFound("Application");
        return a;
    }

    Optional<Product> product(LoanApplication a) { return products.findByCode(a.getProductCode()); }

    public String kind(LoanApplication a) { return product(a).map(Product::getTradeKind).filter(k -> !k.isBlank()).orElse(null); }

    public boolean isTrade(LoanApplication a) { return kind(a) != null; }

    static void require(CurrentUser u, Set<Role> roles, String what) {
        if (!roles.contains(u.role())) throw ApiException.forbidden("ROLE", "Your role cannot " + what);
    }

    TradeInvoice invoice(Long id) { return invoices.findById(id).orElseThrow(() -> ApiException.notFound("Invoice")); }

    TradeLimit limitOf(TradeInvoice x) { return limits.findById(x.limitId).orElseThrow(() -> ApiException.notFound("Limit")); }

    // ---------------------------------------------------------------- figures

    static double d(BigDecimal b) { return b == null ? 0 : b.doubleValue(); }
    static BigDecimal money(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }
    static LocalDate today() { return LocalDate.now(IST); }

    /** Principal still out on a drawdown: repayments pay the interest first, then the principal. */
    static double principalOut(TradeInvoice x) {
        if (!LIVE.contains(x.status) && !"REPAID".equals(x.status)) return 0;
        return Math.max(0, d(x.fundedAmount) - Math.max(0, d(x.repaidAmount) - d(x.interestAmount)));
    }

    static double amountDue(TradeInvoice x) { return Math.max(0, d(x.fundedAmount) + d(x.interestAmount) - d(x.repaidAmount)); }

    static int overdueDays(TradeInvoice x) {
        if (!LIVE.contains(x.status) || x.dueDate == null) return 0;
        long n = ChronoUnit.DAYS.between(x.dueDate, today());
        return n > 0 ? (int) n : 0;
    }

    double utilised(TradeLimit l, Long exceptInvoice) {
        return invoices.findByLimitIdOrderByIdDesc(l.id).stream().filter(x -> !x.id.equals(exceptInvoice)).mapToDouble(TradeService::principalOut).sum();
    }

    /** All limits under an anchor (sanctioned or proposed), without one: the programme in use. */
    double programmeUsed(TradeCounterparty anchor, Long exceptLimit) {
        double s = 0;
        for (TradeLimit l : limits.findByAnchorId(anchor.id)) {
            if (l.id.equals(exceptLimit) || Set.of("CLOSED", "EXPIRED").contains(l.status)) continue;
            LoanApplication a = apps.findById(l.applicationId).orElse(null);
            if (a == null || CLOSED.contains(a.getAppState())) continue;
            s += l.sanctionedLimit != null ? d(l.sanctionedLimit) : a.getLoanAmount().doubleValue();
        }
        return s;
    }

    /** Sublimits on a buyer across all limits, without one: the buyer's exposure. */
    double buyerSublimits(TradeCounterparty b, Long exceptLimit) {
        double s = 0;
        for (TradeSublimit x : subs.findByCounterpartyId(b.id)) if (!x.limitId.equals(exceptLimit)) s += d(x.amount);
        return s;
    }

    TradeRules.Party party(TradeCounterparty p, double used) {
        return new TradeRules.Party(p.id, p.name, p.kind, p.programme, p.gstin, p.status, p.empanelled, d(p.advanceRate), p.maxTenorDays, p.graceDays,
                p.confirmationRequired, d(p.concentrationCap), p.programmeLimit == null ? null : d(p.programmeLimit), used);
    }

    double limitAmount(TradeLimit l, LoanApplication a) { return l.sanctionedLimit != null ? d(l.sanctionedLimit) : a.getLoanAmount().doubleValue(); }

    // ---------------------------------------------------------------- the limit on an application

    public Map<String, Object> view(Long appId, CurrentUser u) {
        LoanApplication a = app(appId, u);
        String kind = kind(a);
        if (kind == null) throw ApiException.unprocessable("NOT_TRADE_PRODUCT", "This product is not a trade-finance limit");
        TradeLimit l = limits.findByApplicationId(appId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("product", a.getProductCode());
        m.put("requested", a.getLoanAmount());
        m.put("suggestedGstin", a.getPan() == null ? null : "27" + a.getPan().toUpperCase(Locale.ROOT) + "1Z5");
        boolean programme = !"RECEIVABLES".equals(kind);
        m.put("anchors", parties.findAllByOrderByNameAsc().stream().filter(p -> "ANCHOR".equals(p.kind) && kind.equals(p.programme)).map(this::partyRow).toList());
        m.put("buyers", programme ? List.of() : parties.findAllByOrderByNameAsc().stream().filter(p -> "BUYER".equals(p.kind)).map(this::partyRow).toList());
        m.put("limit", l == null ? null : limitRow(l, a));
        m.put("anchor", l == null || l.anchorId == null ? null : parties.findById(l.anchorId).map(this::partyRow).orElse(null));
        m.put("sublimits", l == null ? List.of() : subRows(l));
        m.put("structure", l == null || l.checksJson == null ? List.of() : Json.read(l.checksJson, new TypeReference<List<Map<String, Object>>>() {}));
        m.put("invoices", l == null ? List.of() : invoices.findByLimitIdOrderByIdDesc(l.id).stream().map(x -> invoiceRow(x, l, a)).toList());
        boolean open = !CLOSED.contains(a.getAppState());
        m.put("canEditStructure", open && CAPTURE.contains(u.role()) && (l == null || "PROPOSED".equals(l.status)) && "NOT_STARTED".equals(a.getSanctionState()));
        m.put("canSubmit", l != null && "ACTIVE".equals(l.status) && CAPTURE.contains(u.role()));
        m.put("canOperate", OPS.contains(u.role()));
        m.put("canRule", EXCEPTIONS.contains(u.role()));
        m.put("me", u.username());
        return m;
    }

    @Transactional
    public Map<String, Object> saveStructure(Long appId, StructureRequest r, CurrentUser u) {
        require(u, CAPTURE, "set the programme and limit");
        LoanApplication a = app(appId, u);
        String kind = kind(a);
        if (kind == null) throw ApiException.unprocessable("NOT_TRADE_PRODUCT", "This product is not a trade-finance limit");
        if (CLOSED.contains(a.getAppState())) throw ApiException.conflict("CLOSED", "The application is closed");
        if (!"NOT_STARTED".equals(a.getSanctionState())) throw ApiException.conflict("SANCTIONED", "The limit structure is fixed once sanction starts");
        TradeLimit l = limits.findByApplicationId(appId).orElseGet(() -> {
            TradeLimit n = new TradeLimit(); n.applicationId = appId; n.productCode = a.getProductCode(); n.createdBy = u.username(); return n;
        });
        if (!"PROPOSED".equals(l.status)) throw ApiException.conflict("ACTIVE", "The limit is " + l.status.toLowerCase());
        if (r.borrowerGstin() != null) l.borrowerGstin = r.borrowerGstin().replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        boolean programme = !"RECEIVABLES".equals(kind);
        if (programme && r.anchorId() != null) l.anchorId = parties.findById(r.anchorId()).orElseThrow(() -> ApiException.notFound("Anchor")).id;
        l.updatedAt = Instant.now();
        limits.save(l);
        if (!programme && r.sublimits() != null) {
            subs.deleteAll(subs.findByLimitId(l.id));
            subs.flush();
            for (SubRequest s : r.sublimits()) {
                if (s.counterpartyId() == null || s.amount() == null || s.amount().signum() <= 0) continue;
                TradeCounterparty b = parties.findById(s.counterpartyId()).orElseThrow(() -> ApiException.notFound("Buyer"));
                if (!"BUYER".equals(b.kind)) throw ApiException.unprocessable("NOT_A_BUYER", b.name + " is an anchor, not a buyer");
                TradeSublimit x = new TradeSublimit(); x.limitId = l.id; x.counterpartyId = b.id; x.amount = s.amount().setScale(2, RoundingMode.HALF_UP);
                subs.save(x);
            }
        }
        if (l.limitNo == null) { l.limitNo = "LIM-" + (5000 + l.id); }
        evaluateStructure(a, l);
        audit.record(u, "trade.structure_saved", "TRADE_LIMIT", l.limitNo, appId, kind + (l.anchorId != null ? " · anchor " + l.anchorId : "") + " · " + subs.findByLimitId(l.id).size() + " buyer(s)");
        return view(appId, u);
    }

    List<TradeRules.Check> evaluateStructure(LoanApplication a, TradeLimit l) {
        String kind = kind(a);
        double amount = limitAmount(l, a);
        TradeCounterparty anchor = l.anchorId == null ? null : parties.findById(l.anchorId).orElse(null);
        List<TradeRules.SubFacts> sf = new ArrayList<>();
        for (TradeSublimit s : subs.findByLimitId(l.id))
            parties.findById(s.counterpartyId).ifPresent(b -> sf.add(new TradeRules.SubFacts(party(b, buyerSublimits(b, l.id)), d(s.amount))));
        List<TradeRules.Check> cs = TradeRules.structure(kind, amount, l.borrowerGstin, a.getPan(), anchor == null ? null : party(anchor, programmeUsed(anchor, l.id)), sf);
        boolean was = l.structureOk;
        l.structureOk = TradeRules.structureOk(cs);
        l.checksJson = Json.write(cs);
        limits.save(l);
        if (l.structureOk) workflow.recordAutomatic(a, "limit", "PASS", "Programme and limit structure ready (" + l.limitNo + ")");
        else if (was) workflow.recordAutomatic(a, "limit", "FAIL", "Limit structure no longer meets the checks");
        return cs;
    }

    /** Sanction needs the programme or buyer sublimits in place. */
    public void requireReadyForSanction(LoanApplication a) {
        if (!isTrade(a)) return;
        TradeLimit l = limits.findByApplicationId(a.getId()).orElseThrow(() -> ApiException.conflict("LIMIT_NOT_SET", "Set the programme and limit (Limit tab) before sanction"));
        List<TradeRules.Check> cs = evaluateStructure(a, l);
        if (!l.structureOk) throw ApiException.conflict("LIMIT_NOT_READY", "Limit structure: " + String.join("; ", cs.stream()
                .filter(c -> "FAIL".equals(c.result()) || "PENDING".equals(c.result())).map(c -> c.id() + " " + c.reason()).toList()));
    }

    public List<String> activationBlockers(LoanApplication a) {
        if (!isTrade(a)) return List.of();
        TradeLimit l = limits.findByApplicationId(a.getId()).orElse(null);
        if (l == null) return List.of("Limit structure not set");
        return evaluateStructure(a, l).stream().filter(c -> "FAIL".equals(c.result()) || "PENDING".equals(c.result())).map(c -> c.id() + " " + c.reason()).toList();
    }

    /** "Disbursement" of a limit: it becomes available for drawdowns; nothing is paid until an invoice is funded. */
    @Transactional
    public TradeLimit activate(LoanApplication a, double sanctioned, double ratePa, String by) {
        TradeLimit l = limits.findByApplicationId(a.getId()).orElseThrow(() -> ApiException.conflict("LIMIT_NOT_SET", "Limit structure not set"));
        l.status = "ACTIVE";
        l.sanctionedLimit = money(sanctioned);
        l.ratePa = BigDecimal.valueOf(ratePa).setScale(3, RoundingMode.HALF_UP);
        l.validFrom = today();
        l.validTo = today().plusMonths(a.getTenureMonths());
        l.updatedAt = Instant.now();
        limits.save(l);
        // sublimits never above the sanctioned limit
        for (TradeSublimit s : subs.findByLimitId(l.id)) if (d(s.amount) > sanctioned) { s.amount = money(sanctioned); subs.save(s); }
        audit.record(by, "SYSTEM", "trade.limit_activated", "TRADE_LIMIT", l.limitNo, a.getId(), "Limit " + TradeRules.rupees(sanctioned) + " active to " + l.validTo);
        return l;
    }

    @Transactional
    public Map<String, Object> freeze(Long appId, boolean freeze, String reason, CurrentUser u) {
        require(u, EXCEPTIONS, freeze ? "freeze a limit" : "unfreeze a limit");
        LoanApplication a = app(appId, u);
        TradeLimit l = limits.findByApplicationId(appId).orElseThrow(() -> ApiException.notFound("Limit"));
        if (reason == null || reason.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        if (freeze && !"ACTIVE".equals(l.status)) throw ApiException.conflict("NOT_ACTIVE", "Limit is " + l.status.toLowerCase());
        if (!freeze && !"FROZEN".equals(l.status)) throw ApiException.conflict("NOT_FROZEN", "Limit is " + l.status.toLowerCase());
        l.status = freeze ? "FROZEN" : "ACTIVE";
        l.frozenReason = freeze ? reason.trim() : null;
        limits.save(l);
        audit.record(u, freeze ? "trade.limit_frozen" : "trade.limit_unfrozen", "TRADE_LIMIT", l.limitNo, a.getId(), reason.trim());
        return view(appId, u);
    }

    // ---------------------------------------------------------------- invoices (drawdowns)

    @Transactional
    public Map<String, Object> submit(Long appId, MultipartFile file, Long counterpartyId, CurrentUser u) throws Exception {
        require(u, CAPTURE, "submit invoices");
        LoanApplication a = app(appId, u);
        TradeLimit l = limits.findByApplicationId(appId).orElseThrow(() -> ApiException.conflict("NO_LIMIT", "No limit on this application"));
        if (!"ACTIVE".equals(l.status)) throw ApiException.conflict("LIMIT_" + l.status, "The limit is " + l.status.toLowerCase() + (l.frozenReason == null ? "" : ": " + l.frozenReason));
        if (file == null || file.isEmpty()) throw ApiException.unprocessable("EMPTY_FILE", "Choose the invoice file");
        if (file.getSize() > 25L * 1024 * 1024) throw ApiException.unprocessable("TOO_LARGE", "Files must be under 25 MB");
        String name = file.getOriginalFilename() == null ? "invoice" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!Set.of(".pdf", ".jpg", ".jpeg", ".png").contains(ext)) throw ApiException.unprocessable("BAD_FILE", "Upload a PDF, JPG or PNG");
        byte[] bytes = file.getBytes();
        TradeInvoice x = new TradeInvoice();
        x.limitId = l.id;
        x.fileName = name;
        x.fileHash = PhotoHash.sha256(bytes);
        x.storagePath = storage.store(appId, bytes, ext);
        x.submittedBy = u.username();
        Map<String, Object> f = new LinkedHashMap<>();
        double conf = 0;
        String engine = "MANUAL";
        Optional<Map<String, Object>> read = ai.tradeInvoice(bytes, file.getContentType(), name);
        if (read.isPresent()) {
            engine = "AI-" + read.get().getOrDefault("engine", "ocr");
            @SuppressWarnings("unchecked") Map<String, Object> raw = (Map<String, Object>) read.get().getOrDefault("fields", Map.of());
            raw.forEach((k, v) -> f.put(k, v instanceof Map<?, ?> mv ? mv.get("value") : v));
            conf = read.get().get("readConfidence") instanceof Number n ? n.doubleValue() : 0;
        }
        x.fieldsJson = Json.write(f);
        x.engine = engine;
        x.readConfidence = BigDecimal.valueOf(conf).setScale(3, RoundingMode.HALF_UP);
        x.invoiceNo = upper(str(f.get("invoiceNo")));
        x.invoiceDate = date(str(f.get("date")));
        x.dueDate = date(str(f.get("dueDate")));
        x.poNo = str(f.get("poNo"));
        x.sellerGstin = upper(str(f.get("sellerGstin")));
        x.buyerGstin = upper(str(f.get("buyerGstin")));
        x.baseAmount = num(f.get("basePrice"));
        x.gstAmount = num(f.get("gst"));
        x.total = num(f.get("total"));
        // the counterparty: the anchor of a programme, or the buyer chosen or found by the GSTIN on the invoice
        String kind = kind(a);
        if (!"RECEIVABLES".equals(kind)) x.counterpartyId = l.anchorId;
        else if (counterpartyId != null) x.counterpartyId = counterpartyId;
        else if (x.buyerGstin != null) x.counterpartyId = parties.findAllByOrderByNameAsc().stream()
                .filter(p -> "BUYER".equals(p.kind) && p.gstin.equalsIgnoreCase(x.buyerGstin)).map(p -> p.id).findFirst().orElse(null);
        invoices.save(x);
        x.refNo = "DD-" + (8000 + x.id);
        evaluate(a, l, x);
        audit.record(u, "trade.invoice_submitted", "TRADE_INVOICE", x.refNo, appId, (x.invoiceNo == null ? "invoice" : x.invoiceNo) + " · " + engine + " · " + x.status);
        return view(appId, u);
    }

    @Transactional
    public Map<String, Object> update(Long invoiceId, InvoiceRequest r, CurrentUser u) {
        require(u, CAPTURE, "correct invoices");
        TradeInvoice x = invoice(invoiceId);
        TradeLimit l = limitOf(x);
        LoanApplication a = app(l.applicationId, u);
        if (!Set.of("SUBMITTED", "CONFIRMED").contains(x.status)) throw ApiException.conflict("LOCKED", "Invoice is " + x.status.toLowerCase());
        if (r.counterpartyId() != null && "RECEIVABLES".equals(kind(a))) x.counterpartyId = r.counterpartyId();
        if (r.invoiceNo() != null) x.invoiceNo = upper(r.invoiceNo());
        if (r.invoiceDate() != null) x.invoiceDate = date(r.invoiceDate());
        if (r.dueDate() != null) x.dueDate = date(r.dueDate());
        if (r.poNo() != null) x.poNo = r.poNo().isBlank() ? null : r.poNo().trim();
        if (r.sellerGstin() != null) x.sellerGstin = upper(r.sellerGstin());
        if (r.buyerGstin() != null) x.buyerGstin = upper(r.buyerGstin());
        if (r.baseAmount() != null) x.baseAmount = r.baseAmount();
        if (r.gstAmount() != null) x.gstAmount = r.gstAmount();
        if (r.total() != null) x.total = r.total();
        // a change after confirmation needs a fresh confirmation
        if ("CONFIRMED".equals(x.status)) { x.status = "SUBMITTED"; x.confirmedAt = null; x.confirmedBy = null; x.confirmationRef = null; }
        evaluate(a, l, x);
        audit.record(u, "trade.invoice_corrected", "TRADE_INVOICE", x.refNo, a.getId(), "Details corrected; checks re-run: " + checksStatus(x));
        return view(a.getId(), u);
    }

    @Transactional
    public Map<String, Object> confirm(Long invoiceId, String reference, CurrentUser u) {
        require(u, OPS, "record a buyer or anchor confirmation");
        TradeInvoice x = invoice(invoiceId);
        TradeLimit l = limitOf(x);
        LoanApplication a = app(l.applicationId, u);
        if (!"SUBMITTED".equals(x.status)) throw ApiException.conflict("NOT_SUBMITTED", "Invoice is " + x.status.toLowerCase());
        if (reference == null || reference.trim().length() < 4) throw ApiException.unprocessable("REFERENCE_REQUIRED", "Enter the confirmation reference (portal acceptance ID or email)");
        TradeRules.Result r = evaluate(a, l, x);
        if (r.checks().stream().anyMatch(c -> "FAIL".equals(c.result())))
            throw ApiException.conflict("CHECKS_FAILED", "Fix or reject the invoice first: " + String.join("; ", r.checks().stream().filter(c -> "FAIL".equals(c.result())).map(c -> c.id() + " " + c.reason()).toList()));
        x.status = "CONFIRMED";
        x.confirmationRef = reference.trim();
        x.confirmedBy = u.username();
        x.confirmedAt = Instant.now();
        evaluate(a, l, x);
        audit.record(u, "trade.invoice_confirmed", "TRADE_INVOICE", x.refNo, a.getId(), "Confirmed · " + x.confirmationRef);
        return view(a.getId(), u);
    }

    @Transactional
    public Map<String, Object> fund(Long invoiceId, CurrentUser u) {
        require(u, OPS, "fund drawdowns");
        TradeInvoice x = invoice(invoiceId);
        TradeLimit l = limitOf(x);
        LoanApplication a = app(l.applicationId, u);
        if (!Set.of("SUBMITTED", "CONFIRMED").contains(x.status)) throw ApiException.conflict("NOT_FUNDABLE", "Invoice is " + x.status.toLowerCase());
        if (u.username().equals(x.submittedBy)) throw ApiException.forbidden("MAKER_CHECKER", "The person who submitted the invoice cannot fund it");
        TradeRules.Result r = evaluate(a, l, x);
        if (!"PASS".equals(r.status()))
            throw ApiException.conflict("CHECKS_" + r.status(), "Invoice checks " + r.status() + ": " + String.join("; ", r.checks().stream().filter(TradeRules.Check::blocks).map(c -> c.id() + " " + c.reason()).limit(4).toList()));
        TradeCounterparty cp = parties.findById(x.counterpartyId).orElseThrow();
        boolean toAnchor = "DEALER".equals(kind(a));
        LoanApplication payee = a;
        if (toAnchor) {
            if (cp.accountNo == null || cp.ifsc == null) throw ApiException.conflict("NO_ACCOUNT", cp.name + "'s bank account is not on record");
            payee = new LoanApplication();
            payee.setApplicantName(cp.accountName == null ? cp.name : cp.accountName);
            payee.setPan(a.getPan());
            payee.setAppNo(a.getAppNo());
            payee.setProductCode(a.getProductCode());
            payee.setBankAccountNo(cp.accountNo);
            payee.setBankIfsc(cp.ifsc);
        }
        double advance = r.advance();
        final LoanApplication to = payee;
        var po = gateway.call(a.getId(), payout.name(), "payout.transfer", () -> payout.pay(to, advance, x.refNo),
                p -> p.success() ? "UTR " + p.utr() : "failed " + p.failureReason(), null);
        if (!po.ok() || !po.value().success()) throw ApiException.conflict("PAYOUT_FAILED", "Payout failed: " + (po.ok() ? po.value().failureReason() : po.error()));
        PayoutResult p = po.value();
        long days = Math.max(1, ChronoUnit.DAYS.between(today(), x.dueDate));
        x.advanceAmount = money(advance);
        x.fundedAmount = money(advance);
        x.interestAmount = money(advance * d(l.ratePa) / 100 * days / 365.0);
        x.fundedBy = u.username();
        x.fundedAt = Instant.now();
        x.utr = p.utr();
        x.paidTo = toAnchor ? cp.name : a.getApplicantName();
        x.status = "FUNDED";
        x.updatedAt = Instant.now();
        invoices.save(x);
        audit.record(u, "trade.invoice_funded", "TRADE_INVOICE", x.refNo, a.getId(), TradeRules.rupees(advance) + " paid to " + x.paidTo + " · UTR " + p.utr()
                + " · interest " + TradeRules.rupees(d(x.interestAmount)) + " to " + x.dueDate);
        return view(a.getId(), u);
    }

    @Transactional
    public Map<String, Object> repay(Long invoiceId, BigDecimal amount, String reference, CurrentUser u) {
        require(u, OPS, "record repayments");
        TradeInvoice x = invoice(invoiceId);
        TradeLimit l = limitOf(x);
        LoanApplication a = app(l.applicationId, u);
        if (!LIVE.contains(x.status)) throw ApiException.conflict("NOT_FUNDED", "Invoice is " + x.status.toLowerCase());
        if (amount == null || amount.signum() <= 0) throw ApiException.unprocessable("AMOUNT_REQUIRED", "Enter the amount received");
        double due = amountDue(x);
        if (amount.doubleValue() > due + 1) throw ApiException.unprocessable("ABOVE_DUE", "Amount is above the " + TradeRules.rupees(due) + " due");
        x.repaidAmount = x.repaidAmount.add(amount).setScale(2, RoundingMode.HALF_UP);
        x.repaymentRef = reference == null || reference.isBlank() ? x.repaymentRef : reference.trim();
        x.repaidAt = Instant.now();
        x.status = amountDue(x) <= 1 ? "REPAID" : "PART_PAID";
        x.updatedAt = Instant.now();
        invoices.save(x);
        audit.record(u, "trade.invoice_repaid", "TRADE_INVOICE", x.refNo, a.getId(), TradeRules.rupees(amount.doubleValue()) + " received" + (x.repaymentRef == null ? "" : " · " + x.repaymentRef) + " · " + x.status);
        return view(a.getId(), u);
    }

    @Transactional
    public Map<String, Object> reject(Long invoiceId, String reason, CurrentUser u) {
        if (!OPS.contains(u.role()) && !EXCEPTIONS.contains(u.role())) throw ApiException.forbidden("ROLE", "Your role cannot reject invoices");
        TradeInvoice x = invoice(invoiceId);
        TradeLimit l = limitOf(x);
        LoanApplication a = app(l.applicationId, u);
        if (!Set.of("SUBMITTED", "CONFIRMED").contains(x.status)) throw ApiException.conflict("LOCKED", "Invoice is " + x.status.toLowerCase());
        if (reason == null || reason.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        x.status = "REJECTED";
        x.rejectedReason = reason.trim();
        x.updatedAt = Instant.now();
        invoices.save(x);
        audit.record(u, "trade.invoice_rejected", "TRADE_INVOICE", x.refNo, a.getId(), reason.trim());
        return view(a.getId(), u);
    }

    @Transactional
    public Map<String, Object> ruling(Long invoiceId, String check, String note, CurrentUser u) {
        require(u, EXCEPTIONS, "approve exceptions");
        TradeInvoice x = invoice(invoiceId);
        TradeLimit l = limitOf(x);
        LoanApplication a = app(l.applicationId, u);
        if (check == null || TradeRules.HARD.contains(check)) throw ApiException.unprocessable("NOT_OVERRIDABLE", check + " cannot be overridden");
        if (note == null || note.trim().length() < 15) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 15 characters");
        TradeRules.Result r = evaluate(a, l, x);
        if (r.checks().stream().noneMatch(c -> c.id().equals(check) && "REFER".equals(c.result()))) throw ApiException.conflict("NOT_REFERRED", check + " is not referred");
        Map<String, Map<String, Object>> m = rulings(x);
        m.put(check, Map.of("by", u.username(), "note", note.trim(), "at", Instant.now().toString()));
        x.rulingsJson = Json.write(m);
        evaluate(a, l, x);
        audit.record(u, "trade.exception", "TRADE_INVOICE", x.refNo, a.getId(), check + ": " + note.trim());
        return view(a.getId(), u);
    }

    static Map<String, Map<String, Object>> rulings(TradeInvoice x) {
        return x.rulingsJson == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Json.read(x.rulingsJson, new TypeReference<Map<String, Map<String, Object>>>() {}));
    }

    TradeRules.Result evaluate(LoanApplication a, TradeLimit l, TradeInvoice x) {
        String kind = kind(a);
        TradeCounterparty cp = x.counterpartyId == null ? null : parties.findById(x.counterpartyId).orElse(null);
        List<TradeInvoice> mine = invoices.findByLimitIdOrderByIdDesc(l.id);
        double buyerOut = 0;
        int worst = 0;
        String worstRef = null;
        for (TradeInvoice o : mine) {
            if (o.id.equals(x.id)) continue;
            if (cp != null && cp.id.equals(o.counterpartyId)) buyerOut += principalOut(o);
            int od = overdueDays(o);
            if (od > worst) { worst = od; worstRef = o.refNo; }
        }
        Double sub = null;
        if (cp != null) for (TradeSublimit s : subs.findByLimitId(l.id)) if (s.counterpartyId.equals(cp.id)) sub = d(s.amount);
        String dup = null;
        if (x.invoiceNo != null && x.sellerGstin != null)
            for (TradeInvoice o : invoices.findBySellerGstinIgnoreCaseAndInvoiceNoIgnoreCase(x.sellerGstin, x.invoiceNo))
                if (!o.id.equals(x.id) && !"REJECTED".equals(o.status)) { dup = o.refNo + where(o); break; }
        if (dup == null && x.fileHash != null)
            for (TradeInvoice o : invoices.findByFileHash(x.fileHash))
                if (!o.id.equals(x.id) && !"REJECTED".equals(o.status)) { dup = o.refNo + where(o) + " (same file)"; break; }
        double programmeUse = 0;
        if (cp != null && "ANCHOR".equals(cp.kind)) {
            for (TradeLimit ol : limits.findByAnchorId(cp.id))
                for (TradeInvoice o : invoices.findByLimitIdOrderByIdDesc(ol.id)) if (!o.id.equals(x.id)) programmeUse += principalOut(o);
        }
        Map<String, Object> read = x.fieldsJson == null ? Map.of() : Json.read(x.fieldsJson, new TypeReference<Map<String, Object>>() {});
        TradeRules.LimitFacts lf = new TradeRules.LimitFacts(kind, l.status, l.validTo, limitAmount(l, a), utilised(l, x.id), l.anchorId, l.borrowerGstin,
                sub, buyerOut, worst, worstRef);
        TradeRules.InvoiceFacts f = new TradeRules.InvoiceFacts(x.invoiceNo, x.invoiceDate, x.dueDate, x.sellerGstin, x.buyerGstin,
                x.baseAmount == null ? null : d(x.baseAmount), x.gstAmount == null ? null : d(x.gstAmount), x.total == null ? null : d(x.total),
                dup, x.confirmedAt != null, read);
        TradeRules.Result r = TradeRules.evaluate(f, lf, cp == null ? null : party(cp, programmeUse), rulings(x), today());
        if (!LIVE.contains(x.status) && !"REPAID".equals(x.status)) x.advanceAmount = money(r.advance());
        x.checksJson = Json.write(r);
        x.updatedAt = Instant.now();
        invoices.save(x);
        return r;
    }

    String where(TradeInvoice o) {
        return limits.findById(o.limitId).flatMap(l -> apps.findById(l.applicationId)).map(oa -> " (" + oa.getAppNo() + ")").orElse("");
    }

    static String checksStatus(TradeInvoice x) {
        if (x.checksJson == null) return "PENDING";
        return String.valueOf(Json.read(x.checksJson, new TypeReference<Map<String, Object>>() {}).get("status"));
    }

    public byte[] content(Long invoiceId, CurrentUser u) {
        TradeInvoice x = invoice(invoiceId);
        app(limitOf(x).applicationId, u);
        return storage.read(x.storagePath);
    }

    // ---------------------------------------------------------------- rows

    Map<String, Object> limitRow(TradeLimit l, LoanApplication a) {
        double limit = limitAmount(l, a), used = utilised(l, -1L);
        List<TradeInvoice> inv = invoices.findByLimitIdOrderByIdDesc(l.id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id);
        m.put("limitNo", l.limitNo);
        m.put("status", l.status);
        m.put("structureOk", l.structureOk);
        m.put("borrowerGstin", l.borrowerGstin);
        m.put("limit", limit);
        m.put("sanctioned", l.sanctionedLimit);
        m.put("utilised", used);
        m.put("available", Math.max(0, limit - used));
        m.put("validFrom", l.validFrom);
        m.put("validTo", l.validTo);
        m.put("ratePa", l.ratePa);
        m.put("frozenReason", l.frozenReason);
        m.put("drawdowns", inv.stream().filter(x -> x.fundedAt != null).count());
        m.put("overdue", inv.stream().filter(x -> overdueDays(x) > 0).count());
        m.put("interestDue", inv.stream().filter(x -> LIVE.contains(x.status)).mapToDouble(x -> Math.max(0, d(x.interestAmount) - d(x.repaidAmount))).sum());
        return m;
    }

    List<Map<String, Object>> subRows(TradeLimit l) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<TradeInvoice> inv = invoices.findByLimitIdOrderByIdDesc(l.id);
        for (TradeSublimit s : subs.findByLimitId(l.id)) {
            TradeCounterparty b = parties.findById(s.counterpartyId).orElse(null);
            if (b == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("counterpartyId", b.id);
            m.put("name", b.name);
            m.put("rating", b.rating);
            m.put("amount", s.amount);
            m.put("used", inv.stream().filter(x -> b.id.equals(x.counterpartyId)).mapToDouble(TradeService::principalOut).sum());
            out.add(m);
        }
        return out;
    }

    Map<String, Object> invoiceRow(TradeInvoice x, TradeLimit l, LoanApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.id);
        m.put("refNo", x.refNo);
        m.put("invoiceNo", x.invoiceNo);
        m.put("invoiceDate", x.invoiceDate);
        m.put("dueDate", x.dueDate);
        m.put("poNo", x.poNo);
        m.put("sellerGstin", x.sellerGstin);
        m.put("buyerGstin", x.buyerGstin);
        m.put("counterpartyId", x.counterpartyId);
        m.put("counterparty", x.counterpartyId == null ? null : parties.findById(x.counterpartyId).map(p -> p.name).orElse(null));
        m.put("baseAmount", x.baseAmount);
        m.put("gstAmount", x.gstAmount);
        m.put("total", x.total);
        m.put("advance", x.advanceAmount);
        m.put("fileName", x.fileName);
        m.put("readConfidence", x.readConfidence);
        m.put("engine", x.engine);
        m.put("status", x.status);
        Map<String, Object> ev = x.checksJson == null ? Map.of() : Json.read(x.checksJson, new TypeReference<Map<String, Object>>() {});
        m.put("checksStatus", ev.get("status"));
        m.put("checks", ev.getOrDefault("checks", List.of()));
        m.put("confirmationRef", x.confirmationRef);
        m.put("confirmedBy", x.confirmedBy);
        m.put("funded", x.fundedAmount);
        m.put("interest", x.interestAmount);
        m.put("fundedBy", x.fundedBy);
        m.put("fundedAt", x.fundedAt);
        m.put("utr", x.utr);
        m.put("paidTo", x.paidTo);
        m.put("repaid", x.repaidAmount);
        m.put("due", amountDue(x));
        m.put("overdueDays", overdueDays(x));
        m.put("rejectedReason", x.rejectedReason);
        m.put("submittedBy", x.submittedBy);
        m.put("submittedAt", x.submittedAt);
        return m;
    }

    Map<String, Object> partyRow(TradeCounterparty p) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (var e : new Object[][]{{"id", p.id}, {"code", p.code}, {"name", p.name}, {"kind", p.kind}, {"programme", p.programme}, {"gstin", p.gstin},
                {"city", p.city}, {"industry", p.industry}, {"rating", p.rating}, {"programmeLimit", p.programmeLimit}, {"maxTenorDays", p.maxTenorDays},
                {"advanceRate", p.advanceRate}, {"graceDays", p.graceDays}, {"recourse", p.recourse}, {"fldgPct", p.fldgPct},
                {"confirmationRequired", p.confirmationRequired}, {"concentrationCap", p.concentrationCap}, {"repaidBy", p.repaidBy},
                {"accountName", p.accountName}, {"ifsc", p.ifsc}, {"empanelled", p.empanelled}, {"status", p.status}, {"notes", p.notes}})
            m.put((String) e[0], e[1]);
        m.put("account", p.accountNo == null ? null : "X".repeat(Math.max(0, p.accountNo.length() - 4)) + p.accountNo.substring(Math.max(0, p.accountNo.length() - 4)));
        double outstanding = 0;
        for (TradeInvoice x : invoices.findByCounterpartyId(p.id)) outstanding += principalOut(x);
        m.put("outstanding", outstanding);
        m.put("limits", "ANCHOR".equals(p.kind) ? programmeUsed(p, -1L) : buyerSublimits(p, -1L));
        return m;
    }

    // ---------------------------------------------------------------- portfolio, masters, tasks

    public Map<String, Object> overview(CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        List<Map<String, Object>> rows = new ArrayList<>(), queue = new ArrayList<>();
        double total = 0, used = 0, overdueAmt = 0;
        for (TradeLimit l : limits.findAllByOrderByIdDesc()) {
            LoanApplication a = apps.findById(l.applicationId).orElse(null);
            if (a == null || (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId()))) continue;
            Map<String, Object> m = limitRow(l, a);
            m.put("applicationId", a.getId());
            m.put("appNo", a.getAppNo());
            m.put("borrower", a.getApplicantName());
            m.put("product", a.getProductCode());
            m.put("kind", kind(a));
            m.put("anchor", l.anchorId == null ? null : parties.findById(l.anchorId).map(p -> p.name).orElse(null));
            m.put("stage", a.getAppState());
            rows.add(m);
            if ("ACTIVE".equals(l.status) || "FROZEN".equals(l.status)) { total += limitAmount(l, a); used += (double) m.get("utilised"); }
            for (TradeInvoice x : invoices.findByLimitIdOrderByIdDesc(l.id)) {
                if (overdueDays(x) > 0) overdueAmt += amountDue(x);
                if (Set.of("REPAID", "REJECTED").contains(x.status)) continue;
                Map<String, Object> q = invoiceRow(x, l, a);
                q.put("applicationId", a.getId());
                q.put("borrower", a.getApplicantName());
                q.put("product", a.getProductCode());
                queue.add(q);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limits", rows);
        out.put("invoices", queue);
        out.put("totalLimits", total);
        out.put("utilised", used);
        out.put("overdueAmount", overdueAmt);
        out.put("counterparties", parties.findAllByOrderByNameAsc().stream().map(this::partyRow).toList());
        return out;
    }

    public List<Map<String, Object>> counterparties() { return parties.findAllByOrderByNameAsc().stream().map(this::partyRow).toList(); }

    @Transactional
    public Map<String, Object> saveParty(Long id, PartyRequest r, CurrentUser u) {
        require(u, MASTERS, "change anchors and buyers");
        TradeCounterparty p = id == null ? new TradeCounterparty() : parties.findById(id).orElseThrow(() -> ApiException.notFound("Counterparty"));
        if (id == null) {
            if (r.code() == null || !r.code().matches("[A-Z0-9-]{3,20}")) throw ApiException.unprocessable("BAD_CODE", "Code: 3 to 20 capitals, digits or -");
            if (parties.findByCode(r.code()).isPresent()) throw ApiException.conflict("DUPLICATE", "Code " + r.code() + " exists");
            p.code = r.code();
            p.createdBy = u.username();
        }
        if (r.name() != null) p.name = r.name().trim();
        if (p.name == null || p.name.isBlank()) throw ApiException.unprocessable("NAME_REQUIRED", "Enter the name");
        if (r.kind() != null) p.kind = r.kind();
        if (!Set.of("ANCHOR", "BUYER").contains(String.valueOf(p.kind))) throw ApiException.unprocessable("BAD_KIND", "Kind is ANCHOR or BUYER");
        if (r.programme() != null) p.programme = r.programme().isBlank() ? null : r.programme();
        if ("ANCHOR".equals(p.kind) && !Set.of("VENDOR", "DEALER").contains(String.valueOf(p.programme))) throw ApiException.unprocessable("BAD_PROGRAMME", "An anchor runs a VENDOR or DEALER programme");
        if ("BUYER".equals(p.kind)) p.programme = null;
        if (r.gstin() != null) p.gstin = r.gstin().replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (p.gstin == null || !p.gstin.matches("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) throw ApiException.unprocessable("BAD_GSTIN", "Enter a valid GSTIN");
        if (r.pan() != null) p.pan = r.pan().isBlank() ? null : r.pan().toUpperCase(Locale.ROOT);
        if (r.city() != null) p.city = r.city();
        if (r.industry() != null) p.industry = r.industry();
        if (r.rating() != null) p.rating = r.rating().isBlank() ? null : r.rating();
        if (r.programmeLimit() != null) p.programmeLimit = r.programmeLimit().signum() <= 0 ? null : r.programmeLimit();
        if (r.maxTenorDays() != null) { if (r.maxTenorDays() < 7 || r.maxTenorDays() > 180) throw ApiException.unprocessable("BAD_TENOR", "Tenor 7 to 180 days"); p.maxTenorDays = r.maxTenorDays(); }
        if (r.advanceRate() != null) { if (r.advanceRate().doubleValue() <= 0 || r.advanceRate().doubleValue() > 1) throw ApiException.unprocessable("BAD_RATE", "Advance rate above 0 and at most 1"); p.advanceRate = r.advanceRate(); }
        if (r.graceDays() != null) p.graceDays = Math.max(0, Math.min(30, r.graceDays()));
        if (r.recourse() != null) p.recourse = r.recourse();
        if (r.fldgPct() != null) p.fldgPct = r.fldgPct().signum() <= 0 ? null : r.fldgPct();
        if (r.confirmationRequired() != null) p.confirmationRequired = r.confirmationRequired();
        if (r.concentrationCap() != null) p.concentrationCap = r.concentrationCap();
        if (r.repaidBy() != null) p.repaidBy = r.repaidBy();
        if (r.accountName() != null) p.accountName = r.accountName().isBlank() ? null : r.accountName();
        if (r.accountNo() != null && !r.accountNo().isBlank()) p.accountNo = r.accountNo().replaceAll("\\s", "");
        if (r.ifsc() != null) {
            String ifsc = r.ifsc().trim().toUpperCase(Locale.ROOT);
            if (!ifsc.isEmpty() && !ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw ApiException.unprocessable("BAD_IFSC", "IFSC is 4 letters, 0, then 6 letters or digits");
            p.ifsc = ifsc.isEmpty() ? null : ifsc;
        }
        if (r.empanelled() != null) p.empanelled = r.empanelled();
        if (r.status() != null) p.status = r.status();
        if (r.notes() != null) p.notes = r.notes();
        p.updatedAt = Instant.now();
        parties.save(p);
        audit.record(u, id == null ? "trade.counterparty_created" : "trade.counterparty_updated", "TRADE_COUNTERPARTY", p.code, null, p.name + " · " + p.kind + " · " + p.status);
        return partyRow(p);
    }

    /** For My work: invoices to confirm or fund (operations), exceptions to decide (credit), drawdowns overdue. */
    public List<Map<String, Object>> tasks(CurrentUser u) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean ops = OPS.contains(u.role()), credit = EXCEPTIONS.contains(u.role());
        if (!ops && !credit) return out;
        Set<Long> vis = scope.visibleBranches(u);
        for (TradeLimit l : limits.findAllByOrderByIdDesc()) {
            if (!Set.of("ACTIVE", "FROZEN").contains(l.status)) continue;
            LoanApplication a = apps.findById(l.applicationId).orElse(null);
            if (a == null || (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId()))) continue;
            for (TradeInvoice x : invoices.findByLimitIdOrderByIdDesc(l.id)) {
                String st = checksStatus(x), task = null;
                if (ops && "SUBMITTED".equals(x.status) && "PENDING".equals(st)) task = "Get confirmation for " + x.refNo + " (" + Objects.toString(x.invoiceNo, "invoice") + ")";
                else if (ops && Set.of("SUBMITTED", "CONFIRMED").contains(x.status) && "PASS".equals(st) && !u.username().equals(x.submittedBy)) task = "Fund " + x.refNo + ": " + TradeRules.rupees(d(x.advanceAmount));
                else if (credit && "REFER".equals(st) && Set.of("SUBMITTED", "CONFIRMED").contains(x.status)) task = "Decide the referred checks on " + x.refNo;
                else if (ops && overdueDays(x) > 0) task = "Overdue " + overdueDays(x) + " days: " + x.refNo + " · " + TradeRules.rupees(amountDue(x));
                if (task == null) continue;
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("application", a);
                t.put("task", task);
                t.put("ageHours", Duration.between(x.updatedAt, Instant.now()).toHours());
                out.add(t);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- parsing

    static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString().trim(); }
    static String upper(String s) { return s == null ? null : s.replaceAll("\\s", "").toUpperCase(Locale.ROOT); }
    static BigDecimal num(Object o) {
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        if (o == null) return null;
        try { return new BigDecimal(o.toString().replace(",", "").trim()).setScale(2, RoundingMode.HALF_UP); } catch (NumberFormatException e) { return null; }
    }
    static LocalDate date(String s) {
        if (s == null) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); } catch (Exception e) { return null; }
    }
}
