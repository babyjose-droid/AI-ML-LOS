package com.rhythm.los.asset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.collateral.CollateralAi;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecordRepository;
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
 * Asset finance: the asset on each application (dealer, quotation, invoice, down payment, insurance), the asset rules,
 * the sanction and disbursement gates, payout to the dealer, and the documents due after disbursement (RC with
 * hypothecation, installation certificate, delivery note).
 */
@Service
public class AssetService {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<String> CLOSED = Set.of("REJECTED", "WITHDRAWN");
    static final Set<String> PRE_DOCS = Set.of("QUOTATION", "INVOICE", "DOWN_PAYMENT_RECEIPT", "INSURANCE", "PHOTO");
    static final Set<String> POST_DOCS = Set.of("RC_HP", "INSURANCE_HP", "INSTALLATION_CERT", "NET_METER", "DELIVERY_NOTE");
    static final Set<Role> CAPTURE = Set.of(Role.ADMIN, Role.SALES, Role.OPERATIONS, Role.CREDIT_OFFICER, Role.CREDIT_MANAGER);
    static final Set<Role> EXCEPTIONS = Set.of(Role.ADMIN, Role.CREDIT_MANAGER, Role.CRO);
    static final Set<Role> MASTERS = Set.of(Role.ADMIN, Role.OPERATIONS, Role.CRO);

    private final AssetRepository assets;
    private final DealerRepository dealers;
    private final AssetPolicyRepository policies;
    private final AssetDocumentRepository documents;
    private final LoanApplicationRepository apps;
    private final ProductRepository products;
    private final DecisionRecordRepository decisions;
    private final StorageService storage;
    private final CollateralAi ai;
    private final WorkflowService workflow;
    private final DataScope scope;
    private final AuditService audit;

    public AssetService(AssetRepository assets, DealerRepository dealers, AssetPolicyRepository policies, AssetDocumentRepository documents,
                        LoanApplicationRepository apps, ProductRepository products, DecisionRecordRepository decisions, StorageService storage,
                        CollateralAi ai, WorkflowService workflow, DataScope scope, AuditService audit) {
        this.assets = assets;
        this.dealers = dealers;
        this.policies = policies;
        this.documents = documents;
        this.apps = apps;
        this.products = products;
        this.decisions = decisions;
        this.storage = storage;
        this.ai = ai;
        this.workflow = workflow;
        this.scope = scope;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- requests

    public record AssetRequest(String assetClass, Long dealerId, String make, String model, String variant, Integer manufactureYear, Integer units,
                               String serialNo, String engineNo, String registrationNo, String quoteNo, String quoteDate, BigDecimal quoteAmount,
                               String invoiceNo, String invoiceDate, BigDecimal basePrice, BigDecimal gstAmount, BigDecimal otherCharges, BigDecimal invoiceTotal,
                               String invoiceGstin, BigDecimal downPayment, String downPaymentRef, String insurer, String insurancePolicyNo,
                               BigDecimal insuredValue, String insuranceExpiry, Boolean insuranceHypothecated, BigDecimal subsidyAmount) {}

    public record DealerRequest(String code, String name, String kind, String categories, String oem, String gstin, String pan, String city, String pincode,
                                String accountName, String accountNo, String ifsc, Boolean empanelled, String status, BigDecimal maxExposure, String notes) {}

    public record PolicyRequest(BigDecimal maxLtv, BigDecimal minDownPct, BigDecimal depRate, Integer maxAgeYears, Boolean insuranceRequired,
                                BigDecimal quoteVariance, BigDecimal fleetLimit, String postDocs, Integer postDays) {}

    // ---------------------------------------------------------------- access

    LoanApplication app(Long appId, CurrentUser u) {
        LoanApplication a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Application"));
        Set<Long> vis = scope.visibleBranches(u);
        if (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) throw ApiException.notFound("Application");
        return a;
    }

    Product product(LoanApplication a) { return products.findByCode(a.getProductCode()).orElseThrow(() -> ApiException.notFound("Product")); }

    public boolean isAssetProduct(LoanApplication a) { return products.findByCode(a.getProductCode()).map(p -> !p.assetClassList().isEmpty()).orElse(false); }

    static void require(CurrentUser u, Set<Role> roles, String what) {
        if (!roles.contains(u.role())) throw ApiException.forbidden("ROLE", "Your role cannot " + what);
    }

    // ---------------------------------------------------------------- the asset on an application

    public Map<String, Object> view(Long appId, CurrentUser u) {
        LoanApplication a = app(appId, u);
        Product p = product(a);
        Asset x = assets.findByApplicationId(appId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", p.getCode());
        m.put("classes", p.assetClassList().stream().map(c -> policies.findById(c).orElse(null)).filter(Objects::nonNull).toList());
        m.put("dealers", dealers.findAllByOrderByNameAsc().stream().filter(d -> p.assetClassList().stream().anyMatch(c -> List.of(d.categories.split(",")).contains(c)))
                .map(this::dealerRow).toList());
        m.put("asset", x);
        m.put("dealer", x == null || x.dealerId == null ? null : dealers.findById(x.dealerId).map(this::dealerRow).orElse(null));
        m.put("documents", x == null ? List.of() : documents.findByAssetIdOrderByIdAsc(x.id));
        m.put("evaluation", x == null || x.evaluationJson == null ? null : Json.read(x.evaluationJson, Map.class));
        m.put("postDocs", x == null ? List.of() : postDocs(x));
        m.put("fleet", fleet(a, x));
        m.put("canEdit", CAPTURE.contains(u.role()) && !"DISBURSED".equals(a.getAppState()) && !CLOSED.contains(a.getAppState()));
        m.put("canVerify", Set.of(Role.ADMIN, Role.OPERATIONS).contains(u.role()));
        m.put("canRule", EXCEPTIONS.contains(u.role()));
        m.put("disbursed", "DISBURSED".equals(a.getAppState()));
        return m;
    }

    @Transactional
    public Map<String, Object> save(Long appId, AssetRequest r, CurrentUser u) {
        require(u, CAPTURE, "record the asset");
        LoanApplication a = app(appId, u);
        if (CLOSED.contains(a.getAppState()) || "DISBURSED".equals(a.getAppState())) throw ApiException.conflict("CLOSED", "The application is " + a.getAppState().toLowerCase());
        Product p = product(a);
        if (p.assetClassList().isEmpty()) throw ApiException.unprocessable("NOT_ASSET_PRODUCT", p.getName() + " does not finance an asset");
        Asset x = assets.findByApplicationId(appId).orElseGet(() -> { Asset n = new Asset(); n.applicationId = appId; n.createdBy = u.username(); return n; });
        String cls = r.assetClass() != null ? r.assetClass() : x.assetClass != null ? x.assetClass : p.assetClassList().get(0);
        if (!p.assetClassList().contains(cls)) throw ApiException.unprocessable("CLASS_NOT_ALLOWED", p.getName() + " finances " + String.join(", ", p.assetClassList()));
        x.assetClass = cls;
        if (r.dealerId() != null) {
            Dealer d = dealers.findById(r.dealerId()).orElseThrow(() -> ApiException.notFound("Dealer"));
            x.dealerId = d.id;
        }
        if (r.make() != null) x.make = blank(r.make());
        if (r.model() != null) x.model = blank(r.model());
        if (r.variant() != null) x.variant = blank(r.variant());
        if (r.manufactureYear() != null) x.manufactureYear = r.manufactureYear();
        if (r.units() != null) { if (r.units() < 1 || r.units() > 100) throw ApiException.unprocessable("BAD_UNITS", "Units must be 1 to 100"); x.units = r.units(); }
        if (r.serialNo() != null) x.serialNo = upper(r.serialNo());
        if (r.engineNo() != null) x.engineNo = upper(r.engineNo());
        if (r.registrationNo() != null) x.registrationNo = upper(r.registrationNo());
        if (r.quoteNo() != null) x.quoteNo = blank(r.quoteNo());
        if (r.quoteDate() != null) x.quoteDate = date(r.quoteDate());
        if (r.quoteAmount() != null) x.quoteAmount = money(r.quoteAmount());
        if (r.invoiceNo() != null) x.invoiceNo = blank(r.invoiceNo());
        if (r.invoiceDate() != null) x.invoiceDate = date(r.invoiceDate());
        if (r.basePrice() != null) x.basePrice = money(r.basePrice());
        if (r.gstAmount() != null) x.gstAmount = money(r.gstAmount());
        if (r.otherCharges() != null) x.otherCharges = money(r.otherCharges());
        if (r.invoiceTotal() != null) x.invoiceTotal = money(r.invoiceTotal());
        if (r.invoiceGstin() != null) x.invoiceGstin = upper(r.invoiceGstin());
        if (r.downPayment() != null) {
            BigDecimal dp = money(r.downPayment());
            if (x.downPayment == null || dp == null || x.downPayment.compareTo(dp) != 0) { x.downPaymentVerifiedBy = null; x.downPaymentVerifiedAt = null; }
            x.downPayment = dp;
        }
        if (r.downPaymentRef() != null) x.downPaymentRef = blank(r.downPaymentRef());
        if (r.insurer() != null) x.insurer = blank(r.insurer());
        if (r.insurancePolicyNo() != null) x.insurancePolicyNo = blank(r.insurancePolicyNo());
        if (r.insuredValue() != null) x.insuredValue = money(r.insuredValue());
        if (r.insuranceExpiry() != null) x.insuranceExpiry = date(r.insuranceExpiry());
        if (r.insuranceHypothecated() != null) x.insuranceHypothecated = r.insuranceHypothecated();
        if (r.subsidyAmount() != null) x.subsidyAmount = money(r.subsidyAmount());
        boolean created = x.id == null;
        x.updatedAt = Instant.now();
        assets.save(x);
        if (x.assetNo == null) { x.assetNo = "AST-" + (7000 + x.id); assets.save(x); }
        audit.record(u, created ? "asset.recorded" : "asset.updated", "ASSET", x.assetNo, appId, cls + (x.make == null ? "" : " · " + x.make + " " + Objects.toString(x.model, "")));
        evaluate(a, x);
        return view(appId, u);
    }

    @Transactional
    public Map<String, Object> upload(Long appId, MultipartFile file, String docType, CurrentUser u) throws Exception {
        LoanApplication a = app(appId, u);
        Asset x = assets.findByApplicationId(appId).orElseThrow(() -> ApiException.conflict("NO_ASSET", "Record the asset and the dealer first"));
        boolean post = POST_DOCS.contains(docType);
        if (!post && !PRE_DOCS.contains(docType)) throw ApiException.unprocessable("BAD_DOC_TYPE", "Document type must be one of " + new TreeSet<>(PRE_DOCS) + " or, after disbursement, " + new TreeSet<>(POST_DOCS));
        if (post && !"DISBURSED".equals(a.getAppState())) throw ApiException.conflict("NOT_DISBURSED", "Documents due after disbursement are uploaded once the loan is disbursed");
        if (!post) require(u, CAPTURE, "upload asset documents");
        else require(u, Set.of(Role.ADMIN, Role.SALES, Role.OPERATIONS), "record documents due after disbursement");
        if (file == null || file.isEmpty()) throw ApiException.unprocessable("EMPTY_FILE", "Choose a file");
        if (file.getSize() > 25L * 1024 * 1024) throw ApiException.unprocessable("TOO_LARGE", "Files must be under 25 MB");
        String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!Set.of(".pdf", ".jpg", ".jpeg", ".png").contains(ext)) throw ApiException.unprocessable("BAD_FILE", "Upload a PDF, JPG or PNG");
        byte[] bytes = file.getBytes();
        AssetDocument d = new AssetDocument();
        d.assetId = x.id;
        d.docType = docType;
        d.fileName = name;
        d.fileHash = PhotoHash.sha256(bytes);
        d.storagePath = storage.store(appId, bytes, ext);
        d.uploadedBy = u.username();
        Map<String, Object> fields = new LinkedHashMap<>();
        double conf = 0;
        String engine = "MANUAL";
        if (Set.of("QUOTATION", "INVOICE", "DOWN_PAYMENT_RECEIPT", "INSURANCE", "RC_HP", "INSURANCE_HP").contains(docType)) {
            Optional<Map<String, Object>> read = ai.assetDocument(bytes, file.getContentType(), name, docType);
            if (read.isPresent()) {
                engine = "AI-" + read.get().getOrDefault("engine", "ocr");
                @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) read.get().getOrDefault("fields", Map.of());
                f.forEach((k, v) -> fields.put(k, v instanceof Map<?, ?> mv ? mv.get("value") : v));
                conf = read.get().get("readConfidence") instanceof Number n ? n.doubleValue() : 0;
            }
        }
        d.fieldsJson = Json.write(fields);
        d.engine = engine;
        d.readConfidence = BigDecimal.valueOf(conf).setScale(3, RoundingMode.HALF_UP);
        documents.save(d);
        prefill(x, docType, fields);
        if (post) receivePostDoc(a, x, docType, d, u);
        x.updatedAt = Instant.now();
        assets.save(x);
        audit.record(u, "asset.document_uploaded", "ASSET", x.assetNo, appId, docType + " · " + engine + (fields.isEmpty() ? "" : " · read " + fields.keySet()));
        if (!post) evaluate(a, x);
        return view(appId, u);
    }

    /** Fills empty asset fields from what was read; never overwrites what staff entered (differences show in AF-13). */
    static void prefill(Asset x, String type, Map<String, Object> f) {
        switch (type) {
            case "QUOTATION" -> {
                if (x.quoteNo == null) x.quoteNo = str(f.get("quoteNo"), f.get("invoiceNo"));
                if (x.quoteAmount == null) x.quoteAmount = num(f.get("total"));
                if (x.quoteDate == null) x.quoteDate = date(str(f.get("date")));
                if (x.make == null) x.make = str(f.get("make"));
                if (x.model == null) x.model = str(f.get("model"));
            }
            case "INVOICE" -> {
                if (x.invoiceNo == null) x.invoiceNo = str(f.get("invoiceNo"));
                if (x.invoiceDate == null) x.invoiceDate = date(str(f.get("date")));
                if (x.basePrice == null) x.basePrice = num(f.get("basePrice"));
                if (x.gstAmount == null) x.gstAmount = num(f.get("gst"));
                if (x.otherCharges == null) x.otherCharges = num(f.get("otherCharges"));
                if (x.invoiceTotal == null) x.invoiceTotal = num(f.get("total"));
                if (x.invoiceGstin == null) x.invoiceGstin = upper(str(f.get("gstin")));
                if (x.serialNo == null) x.serialNo = upper(str(f.get("serialNo")));
                if (x.engineNo == null) x.engineNo = upper(str(f.get("engineNo")));
                if (x.make == null) x.make = str(f.get("make"));
                if (x.model == null) x.model = str(f.get("model"));
            }
            case "DOWN_PAYMENT_RECEIPT" -> {
                if (x.downPayment == null) x.downPayment = num(f.get("amount"));
                if (x.downPaymentRef == null) x.downPaymentRef = str(f.get("receiptNo"));
            }
            case "INSURANCE" -> {
                if (x.insurer == null) x.insurer = str(f.get("insurer"));
                if (x.insurancePolicyNo == null) x.insurancePolicyNo = str(f.get("policyNo"));
                if (x.insuredValue == null) x.insuredValue = num(f.get("insuredValue"));
                if (x.insuranceExpiry == null) x.insuranceExpiry = date(str(f.get("validTill")));
                if (Boolean.TRUE.equals(f.get("hypothecated"))) x.insuranceHypothecated = true;
            }
            case "RC_HP" -> { if (str(f.get("registrationNo")) != null) x.registrationNo = upper(str(f.get("registrationNo"))); }
            default -> { }
        }
    }

    @Transactional
    public Map<String, Object> verifyDownPayment(Long appId, String reference, CurrentUser u) {
        require(u, Set.of(Role.ADMIN, Role.OPERATIONS), "verify the down payment");
        LoanApplication a = app(appId, u);
        Asset x = assets.findByApplicationId(appId).orElseThrow(() -> ApiException.conflict("NO_ASSET", "Record the asset first"));
        if (x.downPayment == null || x.downPayment.signum() <= 0) throw ApiException.unprocessable("NO_DOWN_PAYMENT", "Enter the down payment first");
        boolean receipt = documents.findByAssetIdOrderByIdAsc(x.id).stream().anyMatch(d -> "DOWN_PAYMENT_RECEIPT".equals(d.docType));
        if (!receipt) throw ApiException.unprocessable("NO_RECEIPT", "Upload the dealer's down-payment receipt first");
        if (reference != null && !reference.isBlank()) x.downPaymentRef = reference.trim();
        x.downPaymentVerifiedBy = u.username();
        x.downPaymentVerifiedAt = Instant.now();
        assets.save(x);
        audit.record(u, "asset.down_payment_verified", "ASSET", x.assetNo, appId, "₹" + x.downPayment.toPlainString() + " · " + Objects.toString(x.downPaymentRef, ""));
        evaluate(a, x);
        return view(appId, u);
    }

    @Transactional
    public Map<String, Object> ruling(Long appId, String check, String note, CurrentUser u) {
        require(u, EXCEPTIONS, "approve asset exceptions");
        LoanApplication a = app(appId, u);
        Asset x = assets.findByApplicationId(appId).orElseThrow(() -> ApiException.conflict("NO_ASSET", "Record the asset first"));
        if (check == null || !AssetRules.LABEL.containsKey(check)) throw ApiException.unprocessable("BAD_CHECK", "Unknown check " + check);
        if (AssetRules.HARD.contains(check)) throw ApiException.conflict("NOT_OVERRIDABLE", check + " cannot be overridden: fix the dealer, the invoice or the asset");
        if (note == null || note.trim().length() < 15) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 15 characters");
        Map<String, Map<String, Object>> r = rulings(x);
        r.put(check, Map.<String, Object>of("by", u.username(), "note", note.trim(), "at", Instant.now().toString()));
        x.rulingsJson = Json.write(r);
        assets.save(x);
        audit.record(u, "asset.exception_approved", "ASSET", x.assetNo, appId, check + " · " + note.trim());
        evaluate(a, x);
        return view(appId, u);
    }

    @Transactional
    public Map<String, Object> reevaluate(Long appId, CurrentUser u) {
        LoanApplication a = app(appId, u);
        assets.findByApplicationId(appId).ifPresent(x -> evaluate(a, x));
        return view(appId, u);
    }

    static Map<String, Map<String, Object>> rulings(Asset x) {
        return x.rulingsJson == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Json.read(x.rulingsJson, new TypeReference<Map<String, Map<String, Object>>>() {}));
    }

    // ---------------------------------------------------------------- the rules

    AssetRules.Result evaluate(LoanApplication a, Asset x) {
        AssetPolicy pol = policies.findById(x.assetClass).orElseThrow(() -> ApiException.notFound("Asset policy " + x.assetClass));
        Dealer d = x.dealerId == null ? null : dealers.findById(x.dealerId).orElse(null);
        List<AssetDocument> docs = documents.findByAssetIdOrderByIdAsc(x.id);
        Map<String, Object> read = new LinkedHashMap<>();
        docs.stream().filter(z -> "INVOICE".equals(z.docType)).reduce((p, q) -> q).ifPresent(z -> {
            if (z.fieldsJson != null) read.putAll(Json.read(z.fieldsJson, new TypeReference<Map<String, Object>>() {}));
        });
        String dupInvoice = null, dupSerial = null;
        for (Asset o : assets.findAll()) {
            if (o.id.equals(x.id)) continue;
            LoanApplication oa = apps.findById(o.applicationId).orElse(null);
            if (oa == null || CLOSED.contains(oa.getAppState())) continue;
            if (x.invoiceNo != null && x.invoiceNo.equalsIgnoreCase(Objects.toString(o.invoiceNo, "")) && Objects.equals(x.dealerId, o.dealerId)) dupInvoice = oa.getAppNo();
            if (x.serialNo != null && x.serialNo.equalsIgnoreCase(Objects.toString(o.serialNo, ""))) dupSerial = oa.getAppNo();
        }
        double rate = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).map(r -> r.getRate() == null ? null : r.getRate().doubleValue())
                .orElse(product(a).getRateMax().doubleValue());
        AssetRules.Facts f = new AssetRules.Facts(x.assetClass, x.manufactureYear, x.serialNo, x.registrationNo, x.units,
                dbl(x.quoteAmount), docs.stream().anyMatch(z -> "QUOTATION".equals(z.docType)), x.invoiceNo, dbl(x.basePrice), dbl(x.gstAmount), dbl(x.otherCharges),
                dbl(x.invoiceTotal), x.invoiceGstin, docs.stream().anyMatch(z -> "INVOICE".equals(z.docType)), dbl(x.downPayment), x.downPaymentVerifiedAt != null,
                dbl(x.insuredValue), x.insuranceExpiry, x.insuranceHypothecated, dbl(x.subsidyAmount), a.getLoanAmount().doubleValue(), a.getTenureMonths(), rate,
                otherExposure(a), dupInvoice, dupSerial, read);
        AssetRules.Result r = AssetRules.evaluate(f, pol.rules(), d == null ? null : dealerFacts(d, a.getId()), rulings(x), LocalDate.now(IST));
        String before = x.status;
        x.status = r.status();
        x.maxLoan = BigDecimal.valueOf(r.maxLoan()).setScale(2, RoundingMode.HALF_UP);
        x.evaluationJson = Json.write(r);
        assets.save(x);
        if (!Objects.equals(before, r.status())) audit.record("system", "SYSTEM", "asset.evaluated", "ASSET", x.assetNo, a.getId(),
                "Asset rules " + r.status() + (r.checks().stream().anyMatch(AssetRules.Check::blocksSanction) ? " · " + r.checks().stream().filter(AssetRules.Check::blocksSanction).map(AssetRules.Check::id).toList() : ""));
        if ("PASS".equals(r.status()) || "FAIL".equals(r.status()))
            workflow.recordAutomatic(a, "asset", r.status(), "Asset rules " + r.status() + " (" + x.assetNo + ")");
        return r;
    }

    AssetRules.DealerFacts dealerFacts(Dealer d, Long exceptApp) {
        double exposure = 0;
        for (Asset o : assets.findByDealerId(d.id)) {
            if (o.applicationId.equals(exceptApp)) continue;
            LoanApplication oa = apps.findById(o.applicationId).orElse(null);
            if (oa != null && !CLOSED.contains(oa.getAppState())) exposure += oa.getLoanAmount().doubleValue();
        }
        return new AssetRules.DealerFacts(d.id, d.name, d.status, d.empanelled, d.categories, d.gstin, d.maxExposure == null ? null : d.maxExposure.doubleValue(), exposure);
    }

    /** The borrower's other asset-finance loans (same PAN), open or disbursed: the fleet exposure. */
    double otherExposure(LoanApplication a) {
        double s = 0;
        for (Map<String, Object> m : fleetLoans(a)) s += ((Number) m.get("amount")).doubleValue();
        return s;
    }

    List<Map<String, Object>> fleetLoans(LoanApplication a) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Asset o : assets.findAll()) {
            if (o.applicationId.equals(a.getId())) continue;
            LoanApplication oa = apps.findById(o.applicationId).orElse(null);
            if (oa == null || CLOSED.contains(oa.getAppState()) || oa.getPan() == null || !oa.getPan().equalsIgnoreCase(a.getPan())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("appNo", oa.getAppNo());
            m.put("assetNo", o.assetNo);
            m.put("asset", (Objects.toString(o.make, "") + " " + Objects.toString(o.model, "")).trim());
            m.put("serialNo", o.serialNo);
            m.put("registrationNo", o.registrationNo);
            m.put("amount", oa.getLoanAmount().doubleValue());
            m.put("state", oa.getAppState());
            out.add(m);
        }
        return out;
    }

    Map<String, Object> fleet(LoanApplication a, Asset x) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> loans = fleetLoans(a);
        m.put("loans", loans);
        m.put("otherExposure", loans.stream().mapToDouble(z -> ((Number) z.get("amount")).doubleValue()).sum());
        m.put("limit", x == null ? null : policies.findById(x.assetClass).map(p -> p.fleetLimit).orElse(null));
        return m;
    }

    // ---------------------------------------------------------------- gates

    /** Blocks sanction until the asset passes, and above the asset's maximum loan unless credit approved AF-08 as an exception. */
    public void requireReadyForSanction(LoanApplication a, BigDecimal amount) {
        if (!isAssetProduct(a)) return;
        Asset x = assets.findByApplicationId(a.getId()).orElseThrow(() -> ApiException.conflict("ASSET_NOT_RECORDED", "Record the asset and the dealer (Asset tab) before sanction"));
        AssetRules.Result r = evaluate(a, x);
        if (!"PASS".equals(r.status()))
            throw ApiException.conflict("ASSET_NOT_CLEARED", "Asset rules " + r.status() + ": " + String.join("; ", r.checks().stream()
                    .filter(c -> c.blocksSanction() || "PENDING".equals(r.status())).map(c -> c.id() + " " + c.reason()).limit(4).toList()));
        boolean ltvException = rulings(x).containsKey("AF-08");
        if (amount != null && r.maxLoan() > 0 && amount.doubleValue() > r.maxLoan() + 0.5 && !ltvException)
            throw ApiException.unprocessable("ABOVE_ASSET_LTV", "Sanction amount is above the asset's maximum loan " + AssetRules.rupees(r.maxLoan()));
    }

    /** What still stops disbursement of an asset loan: the asset rules and everything needed before payout. */
    public List<String> disbursementBlockers(LoanApplication a) {
        if (!isAssetProduct(a)) return List.of();
        Asset x = assets.findByApplicationId(a.getId()).orElse(null);
        if (x == null) return List.of("Asset not recorded");
        AssetRules.Result r = evaluate(a, x);
        List<String> out = new ArrayList<>();
        if (!"PASS".equals(r.status())) out.add("Asset rules " + r.status());
        out.addAll(r.beforeDisbursement());
        Dealer d = x.dealerId == null ? null : dealers.findById(x.dealerId).orElse(null);
        if (d != null && (d.accountNo == null || d.ifsc == null)) out.add("Dealer bank account not on record");
        return out;
    }

    /** Asset loans are paid to the dealer or supplier: their account for the penny drop and the payout. Empty for other products. */
    public Optional<LoanApplication> payee(LoanApplication a) {
        if (!isAssetProduct(a)) return Optional.empty();
        return assets.findByApplicationId(a.getId()).flatMap(x -> Optional.ofNullable(x.dealerId)).flatMap(dealers::findById).map(d -> {
            LoanApplication s = new LoanApplication();
            s.setApplicantName(d.accountName == null ? d.name : d.accountName);
            s.setPan(a.getPan());
            s.setAppNo(a.getAppNo());
            s.setProductCode(a.getProductCode());
            s.setBankAccountNo(d.accountNo);
            s.setBankIfsc(d.ifsc);
            return s;
        });
    }

    @Transactional
    public void onDisbursed(LoanApplication a) {
        assets.findByApplicationId(a.getId()).ifPresent(x -> {
            AssetPolicy pol = policies.findById(x.assetClass).orElse(null);
            if (pol == null) return;
            LocalDate due = LocalDate.now(IST).plusDays(pol.postDays);
            List<Map<String, Object>> list = new ArrayList<>();
            for (String doc : pol.rules().postDocs()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", doc);
                m.put("label", AssetRules.postDocLabel(doc));
                m.put("dueBy", due.toString());
                list.add(m);
            }
            x.postDocsJson = Json.write(list);
            x.lienState = pol.registrationRequired || pol.insuranceRequired ? "PENDING" : "NONE";
            x.disbursedTo = x.dealerId == null ? null : dealers.findById(x.dealerId).map(d -> d.name).orElse(null);
            assets.save(x);
        });
    }

    List<Map<String, Object>> postDocs(Asset x) {
        if (x.postDocsJson == null) return List.of();
        List<Map<String, Object>> list = Json.read(x.postDocsJson, new TypeReference<List<Map<String, Object>>>() {});
        LocalDate today = LocalDate.now(IST);
        for (Map<String, Object> m : list) {
            boolean got = m.get("receivedAt") != null;
            LocalDate due = date(str(m.get("dueBy")));
            m.put("status", got ? "RECEIVED" : due != null && due.isBefore(today) ? "OVERDUE" : "DUE");
        }
        return list;
    }

    void receivePostDoc(LoanApplication a, Asset x, String type, AssetDocument d, CurrentUser u) {
        List<Map<String, Object>> list = x.postDocsJson == null ? new ArrayList<>() : new ArrayList<>(Json.read(x.postDocsJson, new TypeReference<List<Map<String, Object>>>() {}));
        boolean found = false;
        for (Map<String, Object> m : list) if (type.equals(m.get("type"))) { m.put("receivedAt", Instant.now().toString()); m.put("by", u.username()); m.put("docId", d.id); found = true; }
        if (!found) throw ApiException.unprocessable("NOT_DUE", AssetRules.postDocLabel(type) + " is not due for this asset");
        x.postDocsJson = Json.write(list);
        boolean hpDone = list.stream().filter(m -> String.valueOf(m.get("type")).endsWith("_HP")).allMatch(m -> m.get("receivedAt") != null);
        if (hpDone && "PENDING".equals(x.lienState)) {
            x.lienState = "MARKED";
            audit.record(u, "asset.lien_marked", "ASSET", x.assetNo, a.getId(), "Hypothecation in the lender's favour on record");
        }
    }

    /** Documents due after disbursement, for My work (operations and sales). */
    public List<Map<String, Object>> dueTasks(CurrentUser u) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Set.of(Role.ADMIN, Role.OPERATIONS, Role.SALES).contains(u.role())) return out;
        Set<Long> vis = scope.visibleBranches(u);
        for (Asset x : assets.findAllByOrderByIdDesc()) {
            if (x.postDocsJson == null) continue;
            LoanApplication a = apps.findById(x.applicationId).orElse(null);
            if (a == null || (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId()))) continue;
            for (Map<String, Object> m : postDocs(x)) {
                if ("RECEIVED".equals(m.get("status"))) continue;
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("application", a);
                t.put("task", ("OVERDUE".equals(m.get("status")) ? "Overdue: " : "Collect: ") + m.get("label") + " (due " + m.get("dueBy") + ")");
                t.put("ageHours", Duration.between(a.getUpdatedAt(), Instant.now()).toHours());
                out.add(t);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- portfolio view

    public Map<String, Object> overview(CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, double[]> byClass = new TreeMap<>();
        int due = 0, overdue = 0;
        for (Asset x : assets.findAllByOrderByIdDesc()) {
            LoanApplication a = apps.findById(x.applicationId).orElse(null);
            if (a == null || (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId()))) continue;
            Dealer d = x.dealerId == null ? null : dealers.findById(x.dealerId).orElse(null);
            List<Map<String, Object>> pd = postDocs(x);
            long open = pd.stream().filter(m -> !"RECEIVED".equals(m.get("status"))).count();
            long late = pd.stream().filter(m -> "OVERDUE".equals(m.get("status"))).count();
            due += (int) open;
            overdue += (int) late;
            double[] c = byClass.computeIfAbsent(x.assetClass, k -> new double[3]);
            c[0]++;
            c[1] += a.getLoanAmount().doubleValue();
            if ("DISBURSED".equals(a.getAppState())) c[2] += a.getLoanAmount().doubleValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("applicationId", a.getId());
            m.put("appNo", a.getAppNo());
            m.put("applicant", a.getApplicantName());
            m.put("product", a.getProductCode());
            m.put("assetNo", x.assetNo);
            m.put("assetClass", x.assetClass);
            m.put("asset", (Objects.toString(x.make, "") + " " + Objects.toString(x.model, "")).trim());
            m.put("dealer", d == null ? null : d.name);
            m.put("amount", a.getLoanAmount().doubleValue());
            m.put("cost", x.invoiceTotal != null ? x.invoiceTotal.doubleValue() : x.quoteAmount == null ? null : x.quoteAmount.doubleValue());
            m.put("status", x.status);
            m.put("stage", a.getAppState());
            m.put("lien", x.lienState);
            m.put("postDocsOpen", open);
            m.put("postDocsOverdue", late);
            rows.add(m);
        }
        List<Map<String, Object>> classes = new ArrayList<>();
        byClass.forEach((k, v) -> classes.add(Map.<String, Object>of("assetClass", k, "label", policies.findById(k).map(p -> p.label).orElse(k), "loans", (int) v[0], "amount", v[1], "disbursed", v[2])));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assets", rows);
        out.put("byClass", classes);
        out.put("postDocsOpen", due);
        out.put("postDocsOverdue", overdue);
        out.put("dealers", dealers.findAllByOrderByNameAsc().stream().map(this::dealerRow).toList());
        return out;
    }

    // ---------------------------------------------------------------- dealers and asset classes (masters)

    Map<String, Object> dealerRow(Dealer d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("code", d.code);
        m.put("name", d.name);
        m.put("kind", d.kind);
        m.put("categories", d.categories);
        m.put("oem", d.oem);
        m.put("gstin", d.gstin);
        m.put("city", d.city);
        m.put("account", d.accountNo == null ? null : "X".repeat(Math.max(0, d.accountNo.length() - 4)) + d.accountNo.substring(Math.max(0, d.accountNo.length() - 4)));
        m.put("ifsc", d.ifsc);
        m.put("empanelled", d.empanelled);
        m.put("status", d.status);
        m.put("maxExposure", d.maxExposure);
        m.put("exposure", dealerFacts(d, -1L).currentExposure());
        m.put("loans", assets.findByDealerId(d.id).size());
        return m;
    }

    public List<Map<String, Object>> dealers() { return dealers.findAllByOrderByNameAsc().stream().map(this::dealerRow).toList(); }

    @Transactional
    public Map<String, Object> saveDealer(Long id, DealerRequest r, CurrentUser u) {
        require(u, MASTERS, "change dealers");
        Dealer d = id == null ? new Dealer() : dealers.findById(id).orElseThrow(() -> ApiException.notFound("Dealer"));
        if (id == null) {
            if (r.code() == null || !r.code().matches("[A-Z0-9-]{3,20}")) throw ApiException.unprocessable("BAD_CODE", "Code: 3 to 20 capitals, digits or -");
            if (dealers.findByCode(r.code()).isPresent()) throw ApiException.conflict("DUPLICATE", "Dealer code " + r.code() + " exists");
            d.code = r.code();
            d.createdBy = u.username();
        }
        if (r.name() != null) d.name = r.name().trim();
        if (d.name == null || d.name.isBlank()) throw ApiException.unprocessable("NAME_REQUIRED", "Enter the dealer's name");
        if (r.kind() != null) d.kind = r.kind();
        if (d.kind == null) d.kind = "DEALER";
        if (r.categories() != null) {
            for (String c : r.categories().split(",")) if (!c.isBlank() && policies.findById(c.trim()).isEmpty()) throw ApiException.unprocessable("BAD_CATEGORY", "Unknown asset class " + c);
            d.categories = r.categories().replace(" ", "");
        }
        if (d.categories == null || d.categories.isBlank()) throw ApiException.unprocessable("CATEGORY_REQUIRED", "Choose what the dealer sells");
        if (r.gstin() != null) {
            String g = r.gstin().trim().toUpperCase(Locale.ROOT);
            if (!g.isEmpty() && !g.matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) throw ApiException.unprocessable("BAD_GSTIN", "GSTIN " + g + " is not valid");
            d.gstin = g.isEmpty() ? null : g;
        }
        if (r.ifsc() != null) {
            String f = r.ifsc().trim().toUpperCase(Locale.ROOT);
            if (!f.isEmpty() && !f.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw ApiException.unprocessable("BAD_IFSC", "IFSC " + f + " is not valid");
            d.ifsc = f.isEmpty() ? null : f;
        }
        if (r.oem() != null) d.oem = blank(r.oem());
        if (r.pan() != null) d.pan = upper(r.pan());
        if (r.city() != null) d.city = blank(r.city());
        if (r.pincode() != null) d.pincode = blank(r.pincode());
        if (r.accountName() != null) d.accountName = blank(r.accountName());
        if (r.accountNo() != null) d.accountNo = blank(r.accountNo());
        if (r.empanelled() != null) d.empanelled = r.empanelled();
        if (r.status() != null) {
            if (!Set.of("ACTIVE", "SUSPENDED", "BLACKLISTED").contains(r.status())) throw ApiException.unprocessable("BAD_STATUS", "Status: ACTIVE, SUSPENDED or BLACKLISTED");
            d.status = r.status();
        }
        if (r.maxExposure() != null) d.maxExposure = r.maxExposure().signum() <= 0 ? null : r.maxExposure();
        if (r.notes() != null) d.notes = blank(r.notes());
        d.updatedAt = Instant.now();
        dealers.save(d);
        audit.record(u, id == null ? "dealer.created" : "dealer.updated", "DEALER", d.code, null, d.name + " · " + d.status + (d.empanelled ? " · empanelled" : " · not empanelled"));
        return dealerRow(d);
    }

    public List<AssetPolicy> policies() { return policies.findAll().stream().sorted(Comparator.comparing(p -> p.assetClass)).toList(); }

    @Transactional
    public AssetPolicy savePolicy(String cls, PolicyRequest r, CurrentUser u) {
        require(u, Set.of(Role.ADMIN, Role.CRO), "change asset-class rules");
        AssetPolicy p = policies.findById(cls).orElseThrow(() -> ApiException.notFound("Asset class " + cls));
        if (r.maxLtv() != null) { if (r.maxLtv().doubleValue() <= 0 || r.maxLtv().doubleValue() > 1) throw ApiException.unprocessable("BAD_LTV", "LTV is a share between 0 and 1"); p.maxLtv = r.maxLtv(); }
        if (r.minDownPct() != null) { if (r.minDownPct().doubleValue() < 0 || r.minDownPct().doubleValue() >= 1) throw ApiException.unprocessable("BAD_DOWN", "Down payment is a share between 0 and 1"); p.minDownPct = r.minDownPct(); }
        if (r.depRate() != null) p.depRate = r.depRate();
        if (r.maxAgeYears() != null) p.maxAgeYears = r.maxAgeYears();
        if (r.insuranceRequired() != null) p.insuranceRequired = r.insuranceRequired();
        if (r.quoteVariance() != null) p.quoteVariance = r.quoteVariance();
        if (r.fleetLimit() != null) p.fleetLimit = r.fleetLimit().signum() <= 0 ? null : r.fleetLimit();
        if (r.postDocs() != null) p.postDocs = r.postDocs();
        if (r.postDays() != null) p.postDays = r.postDays();
        p.updatedBy = u.username();
        p.updatedAt = Instant.now();
        policies.save(p);
        audit.record(u, "asset_policy.updated", "ASSET_POLICY", cls, null, "LTV " + p.maxLtv + " · down " + p.minDownPct + " · depreciation " + p.depRate);
        return p;
    }

    public byte[] content(Long docId, CurrentUser u) {
        AssetDocument d = documents.findById(docId).orElseThrow(() -> ApiException.notFound("Document"));
        Asset x = assets.findById(d.assetId).orElseThrow(() -> ApiException.notFound("Asset"));
        app(x.applicationId, u);
        return storage.read(d.storagePath);
    }

    /** For loan documents: what is financed and who is paid. */
    public Map<String, Object> docData(Long appId) {
        Map<String, Object> m = new LinkedHashMap<>();
        assets.findByApplicationId(appId).ifPresent(x -> {
            m.put("no", x.assetNo);
            m.put("class", policies.findById(x.assetClass).map(p -> p.label).orElse(x.assetClass));
            m.put("make", x.make);
            m.put("model", x.model);
            m.put("variant", x.variant);
            m.put("year", x.manufactureYear);
            m.put("serialNo", x.serialNo);
            m.put("engineNo", x.engineNo);
            m.put("registrationNo", x.registrationNo);
            m.put("invoiceNo", x.invoiceNo);
            m.put("invoiceDate", x.invoiceDate == null ? null : x.invoiceDate.toString());
            m.put("cost", x.invoiceTotal != null ? x.invoiceTotal : x.quoteAmount);
            m.put("downPayment", x.downPayment);
            m.put("dealer", x.dealerId == null ? null : dealers.findById(x.dealerId).map(d -> d.name).orElse(null));
        });
        return m;
    }

    // ---------------------------------------------------------------- helpers

    static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    static String upper(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT); }
    static BigDecimal money(BigDecimal v) {
        if (v == null) return null;
        if (v.signum() < 0) throw ApiException.unprocessable("NEGATIVE", "Amounts cannot be negative");
        return v.signum() == 0 ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
    static Double dbl(BigDecimal v) { return v == null ? null : v.doubleValue(); }
    static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }
    static String str(Object... vs) {
        for (Object v : vs) if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        return null;
    }
    static BigDecimal num(Object v) {
        if (v == null) return null;
        try {
            double d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).replaceAll("[^0-9.]", ""));
            return d > 0 ? BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP) : null;
        } catch (NumberFormatException e) { return null; }
    }
}
