package com.rhythm.los.pledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LiveLoanRepository;
import com.rhythm.los.collateral.CollateralAi;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.document.StorageService;
import com.rhythm.los.field.PhotoHash;
import com.rhythm.los.flow.WorkflowService;
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
import java.util.*;

/**
 * Loans against securities and fixed deposits: the pledge (read from the CAS statement or the deposit receipt by AI), the
 * drawing power, the lien before disbursement, the daily price file, margin calls and their cure or invocation.
 */
@Service
public class PledgeService {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<String> CLOSED = Set.of("REJECTED", "WITHDRAWN");
    static final Set<Role> CAPTURE = Set.of(Role.ADMIN, Role.SALES, Role.OPERATIONS, Role.CREDIT_OFFICER, Role.CREDIT_MANAGER);
    static final Set<Role> OPS = Set.of(Role.ADMIN, Role.OPERATIONS);
    static final Set<Role> EXCEPTIONS = Set.of(Role.ADMIN, Role.CREDIT_MANAGER, Role.CRO);
    static final Set<Role> MARKET = Set.of(Role.ADMIN, Role.OPERATIONS, Role.CRO);
    static final Set<Role> INVOKE = Set.of(Role.ADMIN, Role.CRO);
    static final int CURE_DAYS = 7;

    private final PledgeRepository pledges;
    private final PledgeHoldingRepository holdings;
    private final PledgeDocumentRepository documents;
    private final SecurityMasterRepository securities;
    private final MarginCallRepository calls;
    private final LoanApplicationRepository apps;
    private final ProductRepository products;
    private final LiveLoanRepository loans;
    private final StorageService storage;
    private final CollateralAi ai;
    private final WorkflowService workflow;
    private final DataScope scope;
    private final AuditService audit;

    public PledgeService(PledgeRepository pledges, PledgeHoldingRepository holdings, PledgeDocumentRepository documents, SecurityMasterRepository securities,
                         MarginCallRepository calls, LoanApplicationRepository apps, ProductRepository products, LiveLoanRepository loans,
                         StorageService storage, CollateralAi ai, WorkflowService workflow, DataScope scope, AuditService audit) {
        this.pledges = pledges;
        this.holdings = holdings;
        this.documents = documents;
        this.securities = securities;
        this.calls = calls;
        this.apps = apps;
        this.products = products;
        this.loans = loans;
        this.storage = storage;
        this.ai = ai;
        this.workflow = workflow;
        this.scope = scope;
        this.audit = audit;
    }

    public record HoldingRequest(String isin, BigDecimal quantity) {}
    public record PledgeRequest(String holderName, String depository, String dematId, String issuer, String depositNo, BigDecimal principal, BigDecimal ratePa,
                                String startDate, String maturityDate, List<HoldingRequest> holdings) {}
    public record SecurityRequest(String isin, String name, String category, Boolean approved, BigDecimal maxLtv, BigDecimal price) {}

    // ---------------------------------------------------------------- access

    LoanApplication app(Long appId, CurrentUser u) {
        LoanApplication a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Application"));
        Set<Long> vis = scope.visibleBranches(u);
        if (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) throw ApiException.notFound("Application");
        return a;
    }

    public String kind(LoanApplication a) { return products.findByCode(a.getProductCode()).map(Product::getPledgeKind).filter(k -> !k.isBlank()).orElse(null); }
    public boolean isPledge(LoanApplication a) { return kind(a) != null; }

    static void require(CurrentUser u, Set<Role> roles, String what) {
        if (!roles.contains(u.role())) throw ApiException.forbidden("ROLE", "Your role cannot " + what);
    }
    static double d(BigDecimal b) { return b == null ? 0 : b.doubleValue(); }
    static BigDecimal money(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }
    static LocalDate today() { return LocalDate.now(IST); }

    Pledge pledgeOf(LoanApplication a, CurrentUser u) {
        return pledges.findByApplicationId(a.getId()).orElseGet(() -> {
            Pledge p = new Pledge(); p.applicationId = a.getId(); p.kind = kind(a); p.createdBy = u.username(); return pledges.save(p);
        });
    }

    double outstanding(LoanApplication a) {
        return loans.findByApplicationId(a.getId()).map(LiveLoan::getOutstanding).map(BigDecimal::doubleValue).orElse(a.getLoanAmount().doubleValue());
    }

    // ---------------------------------------------------------------- evaluation

    List<PledgeRules.Holding> holdingFacts(Pledge p) {
        List<PledgeRules.Holding> out = new ArrayList<>();
        for (PledgeHolding h : holdings.findByPledgeIdOrderByIdAsc(p.id)) {
            SecurityMaster s = securities.findById(h.isin).orElse(null);
            out.add(new PledgeRules.Holding(h.isin, s != null ? s.name : h.name, d(h.quantity), s == null ? null : d(s.price), s == null ? 0 : d(s.maxLtv),
                    s != null && s.approved, s == null ? null : s.category, s == null ? null : s.priceDate));
        }
        return out;
    }

    PledgeRules.Result evaluate(LoanApplication a, Pledge p, double loan) {
        PledgeRules.Facts f = new PledgeRules.Facts(p.kind, p.holderName, a.getApplicantName(), holdingFacts(p), p.depository, p.dematId, p.issuer, p.depositNo,
                p.principal == null ? null : d(p.principal), p.ratePa == null ? null : d(p.ratePa), p.startDate, p.maturityDate, p.lienState, loan, a.getTenureMonths());
        PledgeRules.Result r = PledgeRules.evaluate(f, rulings(p), today());
        boolean was = "PASS".equals(p.status) || Boolean.TRUE.equals(readyFlag(p));
        p.value = money(r.value());
        p.drawingPower = money(r.drawingPower());
        p.status = r.status();
        p.checksJson = Json.write(r);
        p.updatedAt = Instant.now();
        pledges.save(p);
        if (r.readyForSanction()) workflow.recordAutomatic(a, "pledge", "PASS", "Pledge checks pass; drawing power " + PledgeRules.rupees(r.drawingPower()));
        else if (was && !"DISBURSED".equals(a.getAppState())) workflow.recordAutomatic(a, "pledge", "FAIL", "Pledge no longer meets the checks");
        return r;
    }

    static Boolean readyFlag(Pledge p) {
        if (p.checksJson == null) return false;
        Object v = Json.read(p.checksJson, new TypeReference<Map<String, Object>>() {}).get("readyForSanction");
        return v instanceof Boolean b ? b : false;
    }

    static Map<String, Map<String, Object>> rulings(Pledge p) {
        return p.rulingsJson == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Json.read(p.rulingsJson, new TypeReference<Map<String, Map<String, Object>>>() {}));
    }

    double loanFor(LoanApplication a) { return "DISBURSED".equals(a.getAppState()) ? outstanding(a) : a.getLoanAmount().doubleValue(); }

    // ---------------------------------------------------------------- the pledge on an application

    public Map<String, Object> view(Long appId, CurrentUser u) {
        LoanApplication a = app(appId, u);
        String kind = kind(a);
        if (kind == null) throw ApiException.unprocessable("NOT_PLEDGE_PRODUCT", "This product has no pledge");
        Pledge p = pledges.findByApplicationId(appId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("applicant", a.getApplicantName());
        m.put("loan", a.getLoanAmount());
        boolean disbursed = "DISBURSED".equals(a.getAppState());
        m.put("disbursed", disbursed);
        m.put("outstanding", disbursed ? outstanding(a) : null);
        m.put("pledge", p);
        Map<String, Object> ev = p == null || p.checksJson == null ? Map.of() : Json.read(p.checksJson, new TypeReference<Map<String, Object>>() {});
        m.put("checks", ev.getOrDefault("checks", List.of()));
        m.put("readyForSanction", ev.getOrDefault("readyForSanction", false));
        m.put("readyForDisbursement", ev.getOrDefault("readyForDisbursement", false));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (p != null) for (PledgeRules.Holding h : holdingFacts(p)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("isin", h.isin()); r.put("name", h.name()); r.put("quantity", h.quantity()); r.put("price", h.price()); r.put("priceDate", h.priceDate());
            r.put("category", h.category()); r.put("approved", h.approved()); r.put("maxLtv", h.maxLtv());
            double v = h.price() == null ? 0 : h.quantity() * h.price();
            r.put("value", v);
            r.put("drawingPower", h.approved() ? v * h.maxLtv() : 0);
            rows.add(r);
        }
        m.put("holdings", rows);
        m.put("documents", p == null ? List.of() : documents.findByPledgeIdOrderByIdAsc(p.id).stream().map(x -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", x.id); r.put("docType", x.docType); r.put("fileName", x.fileName); r.put("readConfidence", x.readConfidence); r.put("engine", x.engine);
            r.put("uploadedBy", x.uploadedBy); r.put("uploadedAt", x.uploadedAt);
            return r;
        }).toList());
        m.put("marginCalls", p == null ? List.of() : calls.findByPledgeIdOrderByIdDesc(p.id));
        m.put("securities", securities.findAllByOrderByNameAsc().stream().filter(s -> s.approved).toList());
        boolean open = !CLOSED.contains(a.getAppState());
        m.put("canEdit", open && !disbursed && CAPTURE.contains(u.role()));
        m.put("canLien", open && OPS.contains(u.role()));
        m.put("canCure", OPS.contains(u.role()));
        m.put("canInvoke", INVOKE.contains(u.role()));
        m.put("canRule", EXCEPTIONS.contains(u.role()));
        return m;
    }

    @Transactional
    public Map<String, Object> save(Long appId, PledgeRequest r, CurrentUser u) {
        require(u, CAPTURE, "record the pledge");
        LoanApplication a = app(appId, u);
        if (kind(a) == null) throw ApiException.unprocessable("NOT_PLEDGE_PRODUCT", "This product has no pledge");
        if ("DISBURSED".equals(a.getAppState()) || CLOSED.contains(a.getAppState())) throw ApiException.conflict("LOCKED", "The application is " + a.getAppState().toLowerCase());
        Pledge p = pledgeOf(a, u);
        if ("MARKED".equals(p.lienState) && r.holdings() != null) throw ApiException.conflict("LIEN_MARKED", "The lien is marked; release it before changing the pledge");
        if (r.holderName() != null) p.holderName = blank(r.holderName());
        if (r.depository() != null) p.depository = blank(r.depository());
        if (r.dematId() != null) p.dematId = blank(r.dematId());
        if (r.issuer() != null) p.issuer = blank(r.issuer());
        if (r.depositNo() != null) p.depositNo = blank(r.depositNo());
        if (r.principal() != null) p.principal = r.principal();
        if (r.ratePa() != null) p.ratePa = r.ratePa();
        if (r.startDate() != null) p.startDate = date(r.startDate());
        if (r.maturityDate() != null) p.maturityDate = date(r.maturityDate());
        if (r.holdings() != null && "SECURITIES".equals(p.kind)) {
            holdings.deleteAll(holdings.findByPledgeIdOrderByIdAsc(p.id));
            holdings.flush();
            for (HoldingRequest h : r.holdings()) addHolding(p, h.isin(), h.quantity() == null ? 0 : h.quantity().doubleValue(), null, null);
        }
        pledges.save(p);
        evaluate(a, p, loanFor(a));
        audit.record(u, "pledge.saved", "PLEDGE", a.getAppNo(), appId, p.kind + " · " + holdings.findByPledgeIdOrderByIdAsc(p.id).size() + " holding(s)");
        return view(appId, u);
    }

    void addHolding(Pledge p, String isin, double qty, String name, Long forCall) {
        if (isin == null || qty <= 0) return;
        String code = isin.trim().toUpperCase(Locale.ROOT);
        PledgeHolding h = holdings.findByPledgeIdOrderByIdAsc(p.id).stream().filter(x -> x.isin.equals(code)).findFirst().orElseGet(() -> {
            PledgeHolding n = new PledgeHolding(); n.pledgeId = p.id; n.isin = code; n.quantity = BigDecimal.ZERO; return n;
        });
        h.quantity = h.quantity.add(BigDecimal.valueOf(qty)).setScale(4, RoundingMode.HALF_UP);
        if (name != null && h.name == null) h.name = name;
        if (forCall != null) h.addedForCall = forCall;
        holdings.save(h);
    }

    @Transactional
    public Map<String, Object> upload(Long appId, MultipartFile file, String docType, CurrentUser u) throws Exception {
        LoanApplication a = app(appId, u);
        String kind = kind(a);
        if (kind == null) throw ApiException.unprocessable("NOT_PLEDGE_PRODUCT", "This product has no pledge");
        Set<String> allowed = "SECURITIES".equals(kind) ? Set.of("CAS_STATEMENT", "LIEN_CONFIRMATION") : Set.of("FD_RECEIPT", "LIEN_CONFIRMATION");
        if (docType == null || !allowed.contains(docType)) throw ApiException.unprocessable("BAD_DOC_TYPE", "Upload one of " + new TreeSet<>(allowed));
        if ("LIEN_CONFIRMATION".equals(docType)) require(u, OPS, "record the lien");
        else require(u, CAPTURE, "upload pledge documents");
        if (file == null || file.isEmpty()) throw ApiException.unprocessable("EMPTY_FILE", "Choose a file");
        String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!Set.of(".pdf", ".jpg", ".jpeg", ".png").contains(ext)) throw ApiException.unprocessable("BAD_FILE", "Upload a PDF, JPG or PNG");
        byte[] bytes = file.getBytes();
        Pledge p = pledgeOf(a, u);
        PledgeDocument x = new PledgeDocument();
        x.pledgeId = p.id;
        x.docType = docType;
        x.fileName = name;
        x.fileHash = PhotoHash.sha256(bytes);
        x.storagePath = storage.store(appId, bytes, ext);
        x.uploadedBy = u.username();
        Map<String, Object> f = new LinkedHashMap<>();
        double conf = 0;
        String engine = "MANUAL";
        Optional<Map<String, Object>> read = ai.pledgeDocument(bytes, file.getContentType(), name, docType);
        if (read.isPresent()) {
            engine = "AI-" + read.get().getOrDefault("engine", "ocr");
            @SuppressWarnings("unchecked") Map<String, Object> raw = (Map<String, Object>) read.get().getOrDefault("fields", Map.of());
            raw.forEach((k, v) -> f.put(k, v instanceof Map<?, ?> mv ? mv.get("value") : v));
            conf = read.get().get("readConfidence") instanceof Number n ? n.doubleValue() : 0;
        }
        x.fieldsJson = Json.write(f);
        x.engine = engine;
        x.readConfidence = BigDecimal.valueOf(conf).setScale(3, RoundingMode.HALF_UP);
        documents.save(x);
        boolean locked = "DISBURSED".equals(a.getAppState()) || "MARKED".equals(p.lienState);
        switch (docType) {
            case "CAS_STATEMENT" -> {
                if (p.holderName == null) p.holderName = str(f.get("holder"));
                if (p.depository == null) p.depository = str(f.get("depository"));
                if (p.dematId == null) p.dematId = str(f.get("dematId"));
                if (!locked && f.get("holdings") instanceof List<?> rows) {
                    holdings.deleteAll(holdings.findByPledgeIdOrderByIdAsc(p.id));
                    holdings.flush();
                    for (Object o : rows) if (o instanceof Map<?, ?> row)
                        addHolding(p, str(row.get("isin")), row.get("quantity") instanceof Number n ? n.doubleValue() : 0, str(row.get("name")), null);
                }
            }
            case "FD_RECEIPT" -> {
                if (p.issuer == null) p.issuer = str(f.get("issuer"));
                if (p.depositNo == null) p.depositNo = str(f.get("depositNo"));
                if (p.holderName == null) p.holderName = str(f.get("holder"));
                if (p.principal == null && f.get("principal") instanceof Number n) p.principal = money(n.doubleValue());
                if (p.ratePa == null && f.get("rate") instanceof Number n) p.ratePa = BigDecimal.valueOf(n.doubleValue());
                if (p.startDate == null) p.startDate = date(str(f.get("startDate")));
                if (p.maturityDate == null) p.maturityDate = date(str(f.get("maturityDate")));
            }
            case "LIEN_CONFIRMATION" -> {
                String ref = str(f.get("lienRef"));
                if (ref == null) throw ApiException.unprocessable("NO_LIEN_REF", "The lien reference could not be read; enter it by hand");
                markLien(a, p, ref, u);
            }
            default -> { }
        }
        pledges.save(p);
        evaluate(a, p, loanFor(a));
        audit.record(u, "pledge.document_uploaded", "PLEDGE", a.getAppNo(), appId, docType + " · " + engine + (f.isEmpty() ? "" : " · read " + f.keySet()));
        return view(appId, u);
    }

    @Transactional
    public Map<String, Object> lien(Long appId, String ref, CurrentUser u) {
        require(u, OPS, "record the lien");
        LoanApplication a = app(appId, u);
        Pledge p = pledges.findByApplicationId(appId).orElseThrow(() -> ApiException.notFound("Pledge"));
        markLien(a, p, ref, u);
        pledges.save(p);
        evaluate(a, p, loanFor(a));
        return view(appId, u);
    }

    void markLien(LoanApplication a, Pledge p, String ref, CurrentUser u) {
        if (ref == null || ref.trim().length() < 4) throw ApiException.unprocessable("REF_REQUIRED", "Enter the pledge sequence number or the issuer's lien reference");
        if (!Boolean.TRUE.equals(readyFlag(p)) && !Boolean.TRUE.equals(evaluate(a, p, loanFor(a)).readyForSanction()))
            throw ApiException.conflict("PLEDGE_NOT_READY", "Clear the pledge checks before marking the lien");
        p.lienState = "MARKED";
        p.lienRef = ref.trim();
        p.lienMarkedBy = u.username();
        p.lienMarkedAt = Instant.now();
        audit.record(u, "pledge.lien_marked", "PLEDGE", a.getAppNo(), a.getId(), ("SECURITIES".equals(p.kind) ? Objects.toString(p.depository, "Depository") + " pledge " : Objects.toString(p.issuer, "Issuer") + " lien ") + p.lienRef);
    }

    @Transactional
    public Map<String, Object> ruling(Long appId, String check, String note, CurrentUser u) {
        require(u, EXCEPTIONS, "approve exceptions");
        LoanApplication a = app(appId, u);
        Pledge p = pledges.findByApplicationId(appId).orElseThrow(() -> ApiException.notFound("Pledge"));
        if (check == null || PledgeRules.HARD.contains(check)) throw ApiException.unprocessable("NOT_OVERRIDABLE", check + " cannot be overridden");
        if (note == null || note.trim().length() < 15) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 15 characters");
        Map<String, Map<String, Object>> m = rulings(p);
        m.put(check, Map.of("by", u.username(), "note", note.trim(), "at", Instant.now().toString()));
        p.rulingsJson = Json.write(m);
        evaluate(a, p, loanFor(a));
        audit.record(u, "pledge.exception", "PLEDGE", a.getAppNo(), appId, check + ": " + note.trim());
        return view(appId, u);
    }

    // ---------------------------------------------------------------- gates

    public void requireReadyForSanction(LoanApplication a, BigDecimal amount) {
        if (!isPledge(a)) return;
        Pledge p = pledges.findByApplicationId(a.getId()).orElseThrow(() -> ApiException.conflict("PLEDGE_NOT_SET", "Record the pledge (Pledge tab) before sanction"));
        PledgeRules.Result r = evaluate(a, p, amount == null ? a.getLoanAmount().doubleValue() : amount.doubleValue());
        if (!r.readyForSanction())
            throw ApiException.conflict("PLEDGE_NOT_READY", "Pledge " + r.status() + ": " + String.join("; ", r.checks().stream()
                    .filter(c -> !"PASS".equals(c.result()) && c.ruling() == null && !Set.of("PL-06", "FD-05").contains(c.id())).map(c -> c.id() + " " + c.reason()).limit(4).toList()));
    }

    public List<String> disbursementBlockers(LoanApplication a) {
        if (!isPledge(a)) return List.of();
        Pledge p = pledges.findByApplicationId(a.getId()).orElse(null);
        if (p == null) return List.of("Pledge not recorded");
        PledgeRules.Result r = evaluate(a, p, a.getLoanAmount().doubleValue());
        return r.readyForDisbursement() ? List.of() : r.checks().stream().filter(c -> !"PASS".equals(c.result()) && c.ruling() == null).map(c -> c.id() + " " + c.reason()).toList();
    }

    // ---------------------------------------------------------------- monitoring: prices, margin calls

    public List<SecurityMaster> securities() { return securities.findAllByOrderByNameAsc(); }

    @Transactional
    public Map<String, Object> saveSecurity(SecurityRequest r, CurrentUser u) {
        require(u, Set.of(Role.ADMIN, Role.CRO), "change the approved securities");
        if (r.isin() == null || !r.isin().toUpperCase(Locale.ROOT).matches("IN[A-Z0-9]{9}\\d")) throw ApiException.unprocessable("BAD_ISIN", "ISIN is IN + 9 letters or digits + a check digit");
        SecurityMaster s = securities.findById(r.isin().toUpperCase(Locale.ROOT)).orElseGet(() -> { SecurityMaster n = new SecurityMaster(); n.isin = r.isin().toUpperCase(Locale.ROOT); n.priceDate = today(); return n; });
        if (r.name() != null) s.name = r.name().trim();
        if (r.category() != null) s.category = r.category();
        if (r.approved() != null) s.approved = r.approved();
        if (r.maxLtv() != null) {
            double cap = Set.of("EQUITY", "EQUITY_MF").contains(s.category) ? 0.50 : 0.90;
            if (r.maxLtv().doubleValue() < 0 || r.maxLtv().doubleValue() > cap) throw ApiException.unprocessable("LTV_ABOVE_CAP", "LTV for " + s.category + " is at most " + Math.round(cap * 100) + "%");
            s.maxLtv = r.maxLtv();
        }
        if (r.price() != null) { s.price = r.price(); s.priceDate = today(); }
        if (s.name == null || s.category == null || s.maxLtv == null || s.price == null) throw ApiException.unprocessable("INCOMPLETE", "Name, category, LTV and price are needed");
        s.updatedBy = u.username();
        s.updatedAt = Instant.now();
        securities.save(s);
        audit.record(u, "pledge.security_saved", "SECURITY", s.isin, null, s.name + " · " + s.category + " · LTV " + s.maxLtv + (s.approved ? "" : " · not approved"));
        return Map.of("security", s, "revaluation", revalue(u));
    }

    /** The daily price file: "ISIN,price" per line. Every pledge is revalued and margin calls raised or cured. */
    @Transactional
    public Map<String, Object> loadPrices(String csv, CurrentUser u) {
        require(u, MARKET, "load prices");
        if (csv == null || csv.isBlank()) throw ApiException.unprocessable("EMPTY", "Paste the price file (ISIN,price per line)");
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (String line : csv.split("\\r?\\n")) {
            String[] parts = line.trim().split("[,;\\t]");
            if (parts.length < 2 || parts[0].isBlank() || parts[0].equalsIgnoreCase("isin")) continue;
            Optional<SecurityMaster> s = securities.findById(parts[0].trim().toUpperCase(Locale.ROOT));
            if (s.isEmpty()) { errors.add(parts[0].trim() + " is not in the security list"); continue; }
            try {
                double price = Double.parseDouble(parts[1].trim().replace(",", ""));
                if (price <= 0) throw new NumberFormatException();
                s.get().price = BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP);
                s.get().priceDate = today();
                s.get().updatedBy = u.username();
                s.get().updatedAt = Instant.now();
                securities.save(s.get());
                updated++;
            } catch (NumberFormatException e) { errors.add(parts[0].trim() + ": bad price " + parts[1].trim()); }
        }
        audit.record(u, "pledge.prices_loaded", "SECURITY", "PRICES", null, updated + " price(s) as of " + today());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("errors", errors);
        out.put("revaluation", revalue(u));
        return out;
    }

    /** Revalue every disbursed pledge of securities; raise a margin call on a shortfall, close calls that are cured. */
    @Transactional
    public Map<String, Object> revalue(CurrentUser u) {
        int raised = 0, cured = 0;
        for (Pledge p : pledges.findAllByOrderByIdDesc()) {
            if (!"SECURITIES".equals(p.kind) || !"MARKED".equals(p.lienState)) continue;
            LoanApplication a = apps.findById(p.applicationId).orElse(null);
            if (a == null || !"DISBURSED".equals(a.getAppState())) continue;
            double out = outstanding(a);
            PledgeRules.Result r = evaluate(a, p, out);
            double shortfall = PledgeRules.margin(out, r.drawingPower());
            Optional<MarginCall> open = calls.findByPledgeIdOrderByIdDesc(p.id).stream().filter(c -> "OPEN".equals(c.status)).findFirst();
            if (shortfall > 0 && open.isEmpty()) {
                MarginCall c = new MarginCall();
                c.pledgeId = p.id;
                c.applicationId = a.getId();
                c.outstanding = money(out);
                c.drawingPower = money(r.drawingPower());
                c.shortfall = money(shortfall);
                c.dueBy = today().plusDays(CURE_DAYS);
                calls.save(c);
                raised++;
                audit.record(u.username(), u.role().name(), "pledge.margin_call", "MARGIN_CALL", a.getAppNo(), a.getId(),
                        "Shortfall " + PledgeRules.rupees(shortfall) + ": outstanding " + PledgeRules.rupees(out) + " against drawing power " + PledgeRules.rupees(r.drawingPower()) + "; due " + c.dueBy);
            } else if (shortfall <= 0 && open.isPresent()) {
                MarginCall c = open.get();
                c.status = "CURED";
                c.closedBy = u.username();
                c.closedAt = Instant.now();
                if (c.cureNote == null) c.cureNote = "Drawing power back above the loan";
                calls.save(c);
                cured++;
                audit.record(u, "pledge.margin_cured", "MARGIN_CALL", a.getAppNo(), a.getId(), c.cureNote);
            } else if (shortfall > 0) {
                MarginCall c = open.get();
                c.outstanding = money(out);
                c.drawingPower = money(r.drawingPower());
                c.shortfall = money(shortfall);
                calls.save(c);
            }
        }
        return Map.of("raised", raised, "cured", cured);
    }

    /** Cure by pledging more securities. */
    @Transactional
    public Map<String, Object> pledgeMore(Long appId, String isin, BigDecimal qty, String ref, CurrentUser u) {
        require(u, OPS, "add to the pledge");
        LoanApplication a = app(appId, u);
        Pledge p = pledges.findByApplicationId(appId).orElseThrow(() -> ApiException.notFound("Pledge"));
        if (!"SECURITIES".equals(p.kind)) throw ApiException.unprocessable("NOT_SECURITIES", "Only securities can be topped up");
        SecurityMaster s = securities.findById(isin == null ? "" : isin.trim().toUpperCase(Locale.ROOT)).orElseThrow(() -> ApiException.unprocessable("UNKNOWN_ISIN", "Not on the security list"));
        if (!s.approved) throw ApiException.unprocessable("NOT_APPROVED", s.name + " is not an approved security");
        if (qty == null || qty.signum() <= 0) throw ApiException.unprocessable("BAD_QTY", "Enter the quantity");
        if (ref == null || ref.trim().length() < 4) throw ApiException.unprocessable("REF_REQUIRED", "Enter the depository pledge reference for the new securities");
        Long call = calls.findByPledgeIdOrderByIdDesc(p.id).stream().filter(c -> "OPEN".equals(c.status)).map(c -> c.id).findFirst().orElse(null);
        addHolding(p, s.isin, qty.doubleValue(), s.name, call);
        calls.findByPledgeIdOrderByIdDesc(p.id).stream().filter(c -> "OPEN".equals(c.status)).findFirst().ifPresent(c -> {
            c.cureNote = "Pledged " + qty.stripTrailingZeros().toPlainString() + " " + s.name + " (" + ref.trim() + ")";
            calls.save(c);
        });
        audit.record(u, "pledge.topped_up", "PLEDGE", a.getAppNo(), appId, qty.stripTrailingZeros().toPlainString() + " × " + s.name + " · " + ref.trim());
        revalue(u);
        return view(appId, u);
    }

    /** Cure by a part-payment: the loan book outstanding comes down. */
    @Transactional
    public Map<String, Object> partPayment(Long appId, BigDecimal amount, String ref, CurrentUser u) {
        require(u, OPS, "record a part-payment");
        LoanApplication a = app(appId, u);
        Pledge p = pledges.findByApplicationId(appId).orElseThrow(() -> ApiException.notFound("Pledge"));
        LiveLoan l = loans.findByApplicationId(appId).orElseThrow(() -> ApiException.conflict("NOT_BOOKED", "The loan is not on the book yet"));
        if (amount == null || amount.signum() <= 0 || amount.compareTo(l.getOutstanding()) > 0) throw ApiException.unprocessable("BAD_AMOUNT", "Amount must be above 0 and at most the outstanding");
        l.setOutstanding(l.getOutstanding().subtract(amount).setScale(2, RoundingMode.HALF_UP));
        loans.save(l);
        calls.findByPledgeIdOrderByIdDesc(p.id).stream().filter(c -> "OPEN".equals(c.status)).findFirst().ifPresent(c -> {
            c.cureNote = "Part-payment " + PledgeRules.rupees(amount.doubleValue()) + (ref == null || ref.isBlank() ? "" : " (" + ref.trim() + ")");
            calls.save(c);
        });
        audit.record(u, "pledge.part_payment", "PLEDGE", a.getAppNo(), appId, PledgeRules.rupees(amount.doubleValue()) + (ref == null ? "" : " · " + ref));
        revalue(u);
        return view(appId, u);
    }

    /** Not cured by the due date: the lender sells pledged securities for the shortfall (RBI: after notice to the borrower). */
    @Transactional
    public Map<String, Object> invoke(Long callId, String note, CurrentUser u) {
        require(u, INVOKE, "invoke a pledge");
        MarginCall c = calls.findById(callId).orElseThrow(() -> ApiException.notFound("Margin call"));
        LoanApplication a = app(c.applicationId, u);
        if (!"OPEN".equals(c.status)) throw ApiException.conflict("NOT_OPEN", "The margin call is " + c.status.toLowerCase());
        if (!today().isAfter(c.dueBy)) throw ApiException.conflict("NOT_DUE", "The borrower has until " + c.dueBy + " to cure the shortfall");
        if (note == null || note.trim().length() < 15) throw ApiException.unprocessable("REASON_REQUIRED", "Record the notice given and the sale (at least 15 characters)");
        LiveLoan l = loans.findByApplicationId(a.getId()).orElseThrow();
        double sale = Math.min(d(c.shortfall) / 0.5, d(l.getOutstanding()));
        l.setOutstanding(money(Math.max(0, d(l.getOutstanding()) - sale)));
        loans.save(l);
        c.status = "INVOKED";
        c.cureNote = note.trim() + " · sold securities worth " + PledgeRules.rupees(sale) + " applied to the loan";
        c.closedBy = u.username();
        c.closedAt = Instant.now();
        calls.save(c);
        audit.record(u, "pledge.invoked", "MARGIN_CALL", a.getAppNo(), a.getId(), c.cureNote);
        revalue(u);
        return view(a.getId(), u);
    }

    public Map<String, Object> monitor(CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        List<Map<String, Object>> rows = new ArrayList<>();
        double total = 0, dp = 0, out = 0;
        for (Pledge p : pledges.findAllByOrderByIdDesc()) {
            LoanApplication a = apps.findById(p.applicationId).orElse(null);
            if (a == null || (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId()))) continue;
            boolean disbursed = "DISBURSED".equals(a.getAppState());
            double o = disbursed ? outstanding(a) : 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("applicationId", a.getId()); m.put("appNo", a.getAppNo()); m.put("borrower", a.getApplicantName()); m.put("product", a.getProductCode());
            m.put("kind", p.kind); m.put("value", p.value); m.put("drawingPower", p.drawingPower); m.put("outstanding", disbursed ? o : null);
            m.put("ltv", disbursed && d(p.value) > 0 ? o / d(p.value) : null);
            m.put("lien", p.lienState); m.put("stage", a.getAppState());
            m.put("openCall", calls.findByPledgeIdOrderByIdDesc(p.id).stream().filter(c -> "OPEN".equals(c.status)).findFirst().orElse(null));
            m.put("maturity", p.maturityDate);
            rows.add(m);
            if (disbursed) { total += d(p.value); dp += d(p.drawingPower); out += o; }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("pledges", rows);
        res.put("value", total);
        res.put("drawingPower", dp);
        res.put("outstanding", out);
        res.put("calls", calls.findAllByOrderByIdDesc().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("call", c);
            apps.findById(c.applicationId).ifPresent(a -> { m.put("appNo", a.getAppNo()); m.put("borrower", a.getApplicantName()); });
            return m;
        }).toList());
        res.put("securities", securities.findAllByOrderByNameAsc());
        return res;
    }

    /** For My work: liens to mark before disbursement, open margin calls (operations), calls past due (CRO). */
    public List<Map<String, Object>> tasks(CurrentUser u) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean ops = OPS.contains(u.role()), cro = INVOKE.contains(u.role());
        if (!ops && !cro) return out;
        Set<Long> vis = scope.visibleBranches(u);
        for (Pledge p : pledges.findAllByOrderByIdDesc()) {
            LoanApplication a = apps.findById(p.applicationId).orElse(null);
            if (a == null || (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId()))) continue;
            String task = null;
            Optional<MarginCall> open = calls.findByPledgeIdOrderByIdDesc(p.id).stream().filter(c -> "OPEN".equals(c.status)).findFirst();
            if (open.isPresent()) {
                MarginCall c = open.get();
                if (cro && today().isAfter(c.dueBy)) task = "Margin call past due: invoke the pledge (shortfall " + PledgeRules.rupees(d(c.shortfall)) + ")";
                else if (ops) task = "Margin call: shortfall " + PledgeRules.rupees(d(c.shortfall)) + ", due " + c.dueBy;
            } else if (ops && !"MARKED".equals(p.lienState) && Set.of("SANCTIONED", "KFS_ACCEPTED").contains(a.getSanctionState()))
                task = "Mark the " + ("SECURITIES".equals(p.kind) ? "pledge with the depository" : "lien with the deposit's issuer") + " before disbursement";
            if (task == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("application", a);
            m.put("task", task);
            m.put("ageHours", Duration.between(p.updatedAt, Instant.now()).toHours());
            out.add(m);
        }
        return out;
    }

    public byte[] content(Long docId, CurrentUser u) {
        PledgeDocument x = documents.findById(docId).orElseThrow(() -> ApiException.notFound("Document"));
        Pledge p = pledges.findById(x.pledgeId).orElseThrow();
        app(p.applicationId, u);
        return storage.read(x.storagePath);
    }

    static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString().trim(); }
    static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); } catch (Exception e) { return null; }
    }
}
