package com.rhythm.los.collateral;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LiveLoanRepository;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.document.StorageService;
import com.rhythm.los.field.FieldPhoto;
import com.rhythm.los.field.FieldPhotoRepository;
import com.rhythm.los.field.PhotoHash;
import com.rhythm.los.flow.WorkflowService;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.org.Role;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.product.Product;
import com.rhythm.los.product.ProductRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collateral AI inside the credit flow: declare collateral, read its documents, check its photos, record valuations,
 * evaluate rules CR-01 to CR-11 with the risk vector and LTV, route reviews to legal, valuer and field queues, and
 * give the unified credit + collateral decision. AI flags; people clear (every review needs a reason) and only the
 * approved valuation sets value.
 */
@Service
public class CollateralService {
    public static final Set<String> TYPES = Set.of("RESIDENTIAL", "SHOP", "PLOT", "VEHICLE", "GOLD");
    public static final Set<String> DOC_TYPES = Set.of("SALE_DEED", "MOTHER_DEED", "EC", "KHATA", "PROPERTY_TAX", "APPROVED_PLAN", "OC", "LAND_RECORD",
            "CONVERSION_ORDER", "POA", "PARTITION_DEED", "RELEASE_DEED", "MORTGAGE_DEED", "VALUATION_REPORT", "INSURANCE", "RC", "INVOICE", "PERMIT", "FITNESS", "NOC");
    static final Set<String> LEGAL_FLAG_DOCS = Set.of("POA", "PARTITION_DEED", "RELEASE_DEED");

    public record Declare(String type, String description, String ownerName, String address, String pincode, Double lat, Double lng, String surveyNo,
                          String municipalId, Double areaSqft, String constructionStage, String occupancy, String condition, String registrationNo,
                          String makeModel, Integer manufactureYear, Double newPrice, String insurer, String insurancePolicyNo, Double insuranceCover,
                          String insuranceExpiry, Boolean insuranceAssigned) {}
    public record ValuationRequest(String valuer, String reportDate, Double marketValue, Double realisableValue, Double distressValue, String method) {}
    public record GoldItem(String description, Double grossWeight, Double stoneWeight, Integer purityKarat) {}
    public record Confirm(Map<String, Object> fields, List<Map<String, Object>> transactions) {}
    public record Resolve(String outcome, String note) {}

    private final CollateralRepository collaterals;
    private final CollateralDocumentRepository documents;
    private final CollateralMediaRepository media;
    private final CollateralValuationRepository valuations;
    private final CollateralEvaluationRepository evaluations;
    private final CollateralReviewRepository reviews;
    private final CollateralPolicyRepository policies;
    private final LocalityRateRepository localityRates;
    private final GoldRateRepository goldRates;
    private final LoanApplicationRepository apps;
    private final ProductRepository products;
    private final DecisionRecordRepository decisions;
    private final FieldPhotoRepository fieldPhotos;
    private final LiveLoanRepository liveLoans;
    private final StorageService storage;
    private final CollateralAi ai;
    private final WorkflowService workflow;
    private final PolicyService policy;
    private final DataScope scope;
    private final AuditService audit;

    public CollateralService(CollateralRepository collaterals, CollateralDocumentRepository documents, CollateralMediaRepository media,
                             CollateralValuationRepository valuations, CollateralEvaluationRepository evaluations, CollateralReviewRepository reviews,
                             CollateralPolicyRepository policies, LocalityRateRepository localityRates, GoldRateRepository goldRates,
                             LoanApplicationRepository apps, ProductRepository products, DecisionRecordRepository decisions, FieldPhotoRepository fieldPhotos,
                             LiveLoanRepository liveLoans, StorageService storage, CollateralAi ai, WorkflowService workflow, PolicyService policy,
                             DataScope scope, AuditService audit) {
        this.collaterals = collaterals;
        this.documents = documents;
        this.media = media;
        this.valuations = valuations;
        this.evaluations = evaluations;
        this.reviews = reviews;
        this.policies = policies;
        this.localityRates = localityRates;
        this.goldRates = goldRates;
        this.apps = apps;
        this.products = products;
        this.decisions = decisions;
        this.fieldPhotos = fieldPhotos;
        this.liveLoans = liveLoans;
        this.storage = storage;
        this.ai = ai;
        this.workflow = workflow;
        this.policy = policy;
        this.scope = scope;
        this.audit = audit;
    }

    static BigDecimal money(Double v) { return v == null ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }

    // ---------------------------------------------------------------- access

    LoanApplication app(Long appId, CurrentUser u) {
        LoanApplication a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Application"));
        Set<Long> vis = scope.visibleBranches(u);
        if (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) throw ApiException.notFound("Application");
        return a;
    }

    public Collateral get(Long id, CurrentUser u) {
        Collateral c = collaterals.findById(id).orElseThrow(() -> ApiException.notFound("Collateral"));
        app(c.getApplicationId(), u);
        return c;
    }

    CollateralPolicy pol(String type) { return policies.findById(type).orElseThrow(() -> ApiException.notFound("Collateral policy " + type)); }

    static void open(LoanApplication a) {
        if (Set.of("REJECTED", "WITHDRAWN", "DISBURSED").contains(a.getAppState())) throw ApiException.conflict("CLOSED", "The application is " + a.getAppState().toLowerCase());
    }

    // ---------------------------------------------------------------- declare and update

    @Transactional
    public Collateral declare(Long appId, Declare r, CurrentUser by) {
        LoanApplication a = app(appId, by);
        open(a);
        Product p = products.findByCode(a.getProductCode()).orElseThrow(() -> ApiException.notFound("Product"));
        if (r.type() == null || !TYPES.contains(r.type())) throw ApiException.unprocessable("BAD_TYPE", "Type must be one of " + TYPES);
        if (!p.collateralTypeList().contains(r.type()))
            throw ApiException.unprocessable("TYPE_NOT_ALLOWED", p.getName() + " takes " + (p.collateralTypeList().isEmpty() ? "no collateral" : String.join(", ", p.collateralTypeList())) + " as collateral");
        Collateral c = new Collateral();
        c.setApplicationId(appId);
        c.setType(r.type());
        c.setCreatedBy(by.username());
        apply(c, r);
        collaterals.save(c);
        c.setColNo("COL-" + (5000 + c.getId()));
        collaterals.save(c);
        audit.record(by, "collateral.declared", "COLLATERAL", c.getColNo(), appId, r.type() + (r.address() == null ? "" : " · " + r.address()));
        refreshApplication(a);
        return c;
    }

    @Transactional
    public Collateral update(Long id, Declare r, CurrentUser by) {
        Collateral c = get(id, by);
        open(app(c.getApplicationId(), by));
        apply(c, r);
        collaterals.save(c);
        audit.record(by, "collateral.updated", "COLLATERAL", c.getColNo(), c.getApplicationId(), null);
        evaluate(id, by);
        return c;
    }

    void apply(Collateral c, Declare r) {
        if (r.description() != null) c.setDescription(r.description());
        if (r.ownerName() != null) c.setOwnerName(r.ownerName());
        if (r.address() != null) c.setAddress(r.address());
        if (r.pincode() != null) {
            if (!r.pincode().matches("[1-9][0-9]{5}")) throw ApiException.unprocessable("BAD_PIN", "PIN code must be 6 digits");
            c.setPincode(r.pincode());
        }
        if (r.lat() != null || r.lng() != null) {
            if (r.lat() == null || r.lng() == null || r.lat() < 6 || r.lat() > 38 || r.lng() < 68 || r.lng() > 98) throw ApiException.unprocessable("BAD_GEO", "Location must be in India");
            c.setLat(r.lat());
            c.setLng(r.lng());
        }
        if (r.surveyNo() != null) c.setSurveyNo(r.surveyNo());
        if (r.municipalId() != null) c.setMunicipalId(r.municipalId());
        if (r.areaSqft() != null) c.setAreaSqft(money(r.areaSqft()));
        if (r.constructionStage() != null) c.setConstructionStage(oneOf(r.constructionStage(), "COMPLETE", "UNDER_CONSTRUCTION", "VACANT_LAND"));
        if (r.occupancy() != null) c.setOccupancy(oneOf(r.occupancy(), "SELF", "RENTED", "VACANT"));
        if (r.condition() != null) c.setCondition(oneOf(r.condition(), "GOOD", "AVERAGE", "POOR"));
        if (r.registrationNo() != null) c.setRegistrationNo(r.registrationNo().toUpperCase(Locale.ROOT).replaceAll("\\s", ""));
        if (r.makeModel() != null) c.setMakeModel(r.makeModel());
        if (r.manufactureYear() != null) c.setManufactureYear(r.manufactureYear());
        if (r.newPrice() != null) c.setNewPrice(money(r.newPrice()));
        if (r.insurer() != null) c.setInsurer(r.insurer());
        if (r.insurancePolicyNo() != null) c.setInsurancePolicyNo(r.insurancePolicyNo());
        if (r.insuranceCover() != null) c.setInsuranceCover(money(r.insuranceCover()));
        if (r.insuranceExpiry() != null) c.setInsuranceExpiry(LocalDate.parse(r.insuranceExpiry()));
        if (r.insuranceAssigned() != null) c.setInsuranceAssigned(r.insuranceAssigned());
    }

    static String oneOf(String v, String... allowed) {
        if (!Arrays.asList(allowed).contains(v)) throw ApiException.unprocessable("BAD_VALUE", "Use one of " + Arrays.toString(allowed));
        return v;
    }

    // ---------------------------------------------------------------- documents

    @Transactional
    public CollateralDocument uploadDocument(Long id, MultipartFile file, String docType, String fieldsJson, String transactionsJson, CurrentUser by) throws Exception {
        Collateral c = get(id, by);
        open(app(c.getApplicationId(), by));
        if (docType == null || !DOC_TYPES.contains(docType)) throw ApiException.unprocessable("BAD_DOC_TYPE", "Document type must be one of " + new TreeSet<>(DOC_TYPES));
        if (file == null || file.isEmpty()) throw ApiException.unprocessable("EMPTY_FILE", "Choose a file");
        if (file.getSize() > 25L * 1024 * 1024) throw ApiException.unprocessable("TOO_LARGE", "Files must be under 25 MB");
        byte[] bytes = file.getBytes();
        String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!Set.of(".pdf", ".jpg", ".jpeg", ".png").contains(ext)) throw ApiException.unprocessable("BAD_FILE", "Upload a PDF, JPG or PNG");
        CollateralDocument d = new CollateralDocument();
        d.setCollateralId(id);
        d.setDocType(docType);
        d.setFileName(name);
        d.setFileHash(PhotoHash.sha256(bytes));
        d.setStoragePath(storage.store(c.getApplicationId(), bytes, ext));
        d.setVersion((int) documents.findByCollateralIdOrderByIdAsc(id).stream().filter(x -> x.getDocType().equals(docType)).count() + 1);
        d.setUploadedBy(by.username());
        Map<String, Object> fields = new LinkedHashMap<>(), conf = new LinkedHashMap<>();
        List<Map<String, Object>> tx = new ArrayList<>();
        double readConf = 0;
        String engine = "MANUAL";
        Optional<Map<String, Object>> read = ai.document(bytes, file.getContentType(), name, docType);
        if (read.isPresent()) {
            engine = "AI-" + read.get().getOrDefault("engine", "ocr");
            @SuppressWarnings("unchecked") Map<String, Map<String, Object>> f = (Map<String, Map<String, Object>>) read.get().getOrDefault("fields", Map.of());
            f.forEach((k, v) -> { fields.put(k, v.get("value")); conf.put(k, v.get("confidence")); });
            @SuppressWarnings("unchecked") List<Map<String, Object>> t = (List<Map<String, Object>>) read.get().get("transactions");
            if (t != null) tx.addAll(t);
            readConf = read.get().get("readConfidence") instanceof Number n ? n.doubleValue() : 0;
            if (read.get().get("pages") instanceof Number n) d.setPages(n.intValue());
        }
        // fields typed by the uploader (data entry, or the AI service unavailable) are confirmed by that person
        boolean typed = false;
        if (fieldsJson != null && !fieldsJson.isBlank()) {
            Map<String, Object> f = Json.read(fieldsJson, new TypeReference<Map<String, Object>>() {});
            f.forEach((k, v) -> { fields.put(k, v); conf.put(k, 1.0); });
            typed = !f.isEmpty();
        }
        if (transactionsJson != null && !transactionsJson.isBlank()) {
            tx.clear();
            tx.addAll(Json.read(transactionsJson, new TypeReference<List<Map<String, Object>>>() {}));
            typed = true;
        }
        if (typed) {
            readConf = 1.0;
            d.setConfirmedBy(by.username());
            d.setConfirmedAt(Instant.now());
            engine = read.isPresent() ? engine + "+confirmed" : "MANUAL";
        }
        d.setEngine(engine);
        d.setReadConfidence(BigDecimal.valueOf(readConf).setScale(3, RoundingMode.HALF_UP));
        d.setExtractedJson(Json.write(Map.of("fields", fields, "confidence", conf, "transactions", tx)));
        if (fields.get("registrationNo") != null) d.setDocNumber(fields.get("registrationNo").toString());
        else if (fields.get("policyNo") != null) d.setDocNumber(fields.get("policyNo").toString());
        if (fields.get("office") != null) d.setIssuer(fields.get("office").toString());
        else if (fields.get("insurer") != null) d.setIssuer(fields.get("insurer").toString());
        Object dt = Optional.ofNullable(fields.get("registrationDate")).orElse(Optional.ofNullable(fields.get("valuationDate")).orElse(fields.get("periodTo")));
        d.setDocDate(CollateralEngine.date(dt));
        documents.save(d);
        // the insurance and RC details also go on the collateral
        if ("INSURANCE".equals(docType)) {
            if (fields.get("insurer") != null) c.setInsurer(fields.get("insurer").toString());
            if (fields.get("policyNo") != null) c.setInsurancePolicyNo(fields.get("policyNo").toString());
            if (fields.get("sumInsured") != null) c.setInsuranceCover(BigDecimal.valueOf(CollateralEngine.num(fields.get("sumInsured"))));
            LocalDate exp = CollateralEngine.date(fields.get("validTill"));
            if (exp != null) c.setInsuranceExpiry(exp);
            Object as = fields.get("assignedTo");
            if (as != null && !as.toString().isBlank() && !"none".equalsIgnoreCase(as.toString())) c.setInsuranceAssigned(true);
            collaterals.save(c);
        }
        audit.record(by, "collateral.document_uploaded", "COLLATERAL", c.getColNo(), c.getApplicationId(), docType + " v" + d.getVersion() + " · " + engine + " · confidence " + d.getReadConfidence());
        evaluate(id, by);
        return d;
    }

    @Transactional
    public CollateralDocument confirm(Long docId, Confirm r, CurrentUser by) {
        CollateralDocument d = documents.findById(docId).orElseThrow(() -> ApiException.notFound("Document"));
        Collateral c = get(d.getCollateralId(), by);
        Map<String, Object> ex = Json.read(d.getExtractedJson(), new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked") Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>) ex.getOrDefault("fields", Map.of()));
        @SuppressWarnings("unchecked") Map<String, Object> conf = new LinkedHashMap<>((Map<String, Object>) ex.getOrDefault("confidence", Map.of()));
        if (r.fields() != null) r.fields().forEach((k, v) -> { fields.put(k, v); conf.put(k, 1.0); });
        conf.replaceAll((k, v) -> 1.0);
        Object tx = r.transactions() != null ? r.transactions() : ex.getOrDefault("transactions", List.of());
        d.setExtractedJson(Json.write(Map.of("fields", fields, "confidence", conf, "transactions", tx)));
        d.setReadConfidence(BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP));
        d.setConfirmedBy(by.username());
        d.setConfirmedAt(Instant.now());
        documents.save(d);
        audit.record(by, "collateral.document_confirmed", "COLLATERAL", c.getColNo(), c.getApplicationId(), d.getDocType() + " fields confirmed by a person");
        evaluate(c.getId(), by);
        return d;
    }

    /** A reviewer confirms that a document or photo was manipulated (CR-02: collateral FAIL). */
    @Transactional
    public Map<String, Object> markManipulated(String kind, Long itemId, String note, CurrentUser by) {
        if (!Set.of(Role.LEGAL_REVIEWER, Role.CREDIT_MANAGER, Role.CRO, Role.FRAUD_ANALYST, Role.ADMIN).contains(by.role()))
            throw ApiException.forbidden("ROLE", "A legal reviewer, credit manager, CRO or fraud analyst confirms manipulation");
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give the reason (at least 10 characters)");
        Long colId;
        if ("document".equals(kind)) {
            CollateralDocument d = documents.findById(itemId).orElseThrow(() -> ApiException.notFound("Document"));
            d.setManipulated(true);
            documents.save(d);
            colId = d.getCollateralId();
        } else {
            CollateralMedia m = media.findById(itemId).orElseThrow(() -> ApiException.notFound("Photo"));
            m.setManipulated(true);
            media.save(m);
            colId = m.getCollateralId();
        }
        Collateral c = get(colId, by);
        audit.record(by, "collateral.manipulation_confirmed", "COLLATERAL", c.getColNo(), c.getApplicationId(), kind + " " + itemId + ": " + note);
        evaluate(colId, by);
        return Map.of("collateralId", colId);
    }

    public byte[] documentContent(Long docId, CurrentUser u) {
        CollateralDocument d = documents.findById(docId).orElseThrow(() -> ApiException.notFound("Document"));
        get(d.getCollateralId(), u);
        return storage.read(d.getStoragePath());
    }

    public byte[] mediaContent(Long mediaId, CurrentUser u) {
        CollateralMedia m = media.findById(mediaId).orElseThrow(() -> ApiException.notFound("Photo"));
        get(m.getCollateralId(), u);
        return storage.read(m.getStoragePath());
    }

    // ---------------------------------------------------------------- photos

    @Transactional
    public CollateralMedia uploadMedia(Long id, MultipartFile file, String label, Double lat, Double lng, String capturedAt, CurrentUser by) throws Exception {
        Collateral c = get(id, by);
        open(app(c.getApplicationId(), by));
        if ("GOLD".equals(c.getType())) throw ApiException.unprocessable("NOT_FOR_GOLD", "Gold is appraised at the branch; no site photos");
        if (label == null || !label.matches("[A-Z_]{2,30}")) throw ApiException.unprocessable("BAD_LABEL", "Label the photo, for example EXTERIOR_FRONT");
        if (file == null || file.isEmpty() || file.getSize() > 15L * 1024 * 1024) throw ApiException.unprocessable("BAD_FILE", "Photos must be under 15 MB");
        byte[] bytes = file.getBytes();
        CollateralMedia m = new CollateralMedia();
        m.setCollateralId(id);
        m.setLabel(label);
        m.setFileName(file.getOriginalFilename());
        m.setSha256(PhotoHash.sha256(bytes));
        try { m.setDhash(PhotoHash.dhash(bytes)); } catch (RuntimeException e) { m.setDhash(null); }
        m.setStoragePath(storage.store(c.getApplicationId(), bytes, ".jpg"));
        m.setUploadedBy(by.username());
        Optional<Map<String, Object>> an = ai.media(bytes, file.getContentType(), file.getOriginalFilename());
        Double plat = lat, plng = lng;
        Instant at = capturedAt == null || capturedAt.isBlank() ? null : OffsetDateTime.parse(capturedAt).toInstant();
        if (an.isPresent()) {
            m.setAnalysisJson(Json.write(an.get()));
            m.setQuality(Objects.toString(an.get().get("quality"), "UNKNOWN"));
            if (an.get().get("tamperScore") instanceof Number n) m.setTamperScore(BigDecimal.valueOf(n.doubleValue()).setScale(3, RoundingMode.HALF_UP));
            @SuppressWarnings("unchecked") Map<String, Object> ex = (Map<String, Object>) an.get().getOrDefault("exif", Map.of());
            if (plat == null && ex.get("lat") instanceof Number n) plat = n.doubleValue();
            if (plng == null && ex.get("lng") instanceof Number n) plng = n.doubleValue();
            if (at == null && ex.get("takenAt") != null) {
                try { at = LocalDateTime.parse(ex.get("takenAt").toString()).atZone(ZoneId.of("Asia/Kolkata")).toInstant(); } catch (Exception ignored) { }
            }
        } else m.setQuality("UNKNOWN");
        m.setLat(plat);
        m.setLng(plng);
        m.setCapturedAt(at);
        if (plat != null && plng != null && c.getLat() != null && c.getLng() != null) m.setDistanceM((double) Math.round(PhotoHash.metres(plat, plng, c.getLat(), c.getLng())));
        m.setReuseMatch(reuse(c, m));
        media.save(m);
        audit.record(by, "collateral.photo_uploaded", "COLLATERAL", c.getColNo(), c.getApplicationId(), label + (m.getDistanceM() == null ? " · no GPS" : " · " + m.getDistanceM().longValue() + " m")
                + (m.getReuseMatch() == null ? "" : " · REUSED " + m.getReuseMatch()));
        evaluate(id, by);
        return m;
    }

    /** The same photo (exact or near-identical) on another application's collateral or field visit (CR-10). */
    String reuse(Collateral c, CollateralMedia m) {
        for (CollateralMedia x : media.findBySha256(m.getSha256())) {
            Collateral o = collaterals.findById(x.getCollateralId()).orElse(null);
            if (o != null && !o.getApplicationId().equals(c.getApplicationId())) return o.getColNo();
        }
        if (m.getDhash() != null) {
            for (CollateralMedia x : media.findByDhashIsNotNull()) {
                if (PhotoHash.distance(x.getDhash(), m.getDhash()) > 6) continue;
                Collateral o = collaterals.findById(x.getCollateralId()).orElse(null);
                if (o != null && !o.getApplicationId().equals(c.getApplicationId())) return o.getColNo();
            }
            for (FieldPhoto f : fieldPhotos.findByApplicationIdNot(c.getApplicationId()))
                if (f.getDhash() != null && PhotoHash.distance(f.getDhash(), m.getDhash()) <= 6)
                    return apps.findById(f.getApplicationId()).map(LoanApplication::getAppNo).orElse("another application") + " (field visit)";
        }
        return null;
    }

    // ---------------------------------------------------------------- valuation

    @Transactional
    public CollateralValuation addValuation(Long id, ValuationRequest r, CurrentUser by) {
        if (!Set.of(Role.VALUER, Role.ADMIN).contains(by.role())) throw ApiException.forbidden("VALUER_ONLY", "Only the valuation desk records empanelled valuations");
        Collateral c = get(id, by);
        open(app(c.getApplicationId(), by));
        if ("GOLD".equals(c.getType())) throw ApiException.unprocessable("USE_APPRAISAL", "Gold is valued by appraisal");
        if (r.marketValue() == null || r.marketValue() <= 0) throw ApiException.unprocessable("BAD_VALUE", "Market value is required");
        if (r.realisableValue() != null && r.realisableValue() > r.marketValue() || r.distressValue() != null && r.distressValue() > r.marketValue())
            throw ApiException.unprocessable("BAD_VALUE", "Realisable and distress values cannot exceed the market value");
        if (r.valuer() == null || r.valuer().isBlank()) throw ApiException.unprocessable("VALUER_REQUIRED", "Name the empanelled valuer");
        LocalDate d = r.reportDate() == null ? LocalDate.now() : LocalDate.parse(r.reportDate());
        if (d.isAfter(LocalDate.now())) throw ApiException.unprocessable("BAD_DATE", "The report date cannot be in the future");
        CollateralValuation v = new CollateralValuation();
        v.setCollateralId(id);
        v.setSource("EMPANELLED");
        v.setValuer(r.valuer().trim());
        v.setReportDate(d);
        v.setMarketValue(money(r.marketValue()));
        v.setRealisableValue(money(r.realisableValue()));
        v.setDistressValue(money(r.distressValue()));
        v.setMethod(r.method());
        v.setRecordedBy(by.username());
        valuations.save(v);
        audit.record(by, "collateral.valuation_recorded", "COLLATERAL", c.getColNo(), c.getApplicationId(), r.valuer() + " · ₹" + v.getMarketValue().toPlainString() + " on " + d);
        evaluate(id, by);
        return v;
    }

    /** 30-day average of the 22 carat rate (RBI: the lower of the 30-day average and the previous day's rate). */
    public double goldRate() {
        List<GoldRate> rs = goldRates.findByRateDateAfterOrderByRateDateDesc(LocalDate.now().minusDays(31));
        if (rs.isEmpty()) throw ApiException.conflict("NO_GOLD_RATE", "Enter today's gold rate first (Masters → Gold rate)");
        double avg = rs.stream().mapToDouble(r -> r.getRate22k().doubleValue()).average().orElse(0);
        return Math.min(avg, rs.get(0).getRate22k().doubleValue());
    }

    @Transactional
    public CollateralValuation goldAppraisal(Long id, List<GoldItem> items, CurrentUser by) {
        if (!Set.of(Role.CREDIT_OFFICER, Role.OPERATIONS, Role.CREDIT_MANAGER, Role.ADMIN).contains(by.role()))
            throw ApiException.forbidden("ROLE", "A credit officer or operations appraises gold");
        Collateral c = get(id, by);
        open(app(c.getApplicationId(), by));
        if (!"GOLD".equals(c.getType())) throw ApiException.unprocessable("NOT_GOLD", "Appraisal is for gold collateral");
        if (items == null || items.isEmpty()) throw ApiException.unprocessable("NO_ITEMS", "List the items");
        double rate22 = goldRate();
        double value = 0, net = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GoldItem i : items) {
            if (i.grossWeight() == null || i.grossWeight() <= 0 || i.purityKarat() == null || i.purityKarat() < 14 || i.purityKarat() > 24)
                throw ApiException.unprocessable("BAD_ITEM", "Each item needs a gross weight and a purity of 14 to 24 carat");
            double stone = i.stoneWeight() == null ? 0 : i.stoneWeight();
            if (stone < 0 || stone >= i.grossWeight()) throw ApiException.unprocessable("BAD_ITEM", "Stone weight must be less than the gross weight");
            double n = i.grossWeight() - stone;
            // value at the 22 carat rate scaled by purity (RBI: value the gold content only, never the stones or making)
            double v = n * rate22 * i.purityKarat() / 22.0;
            net += n;
            value += v;
            rows.add(Map.of("description", Objects.toString(i.description(), "item"), "grossWeight", i.grossWeight(), "stoneWeight", stone, "netWeight", Math.round(n * 100) / 100.0,
                    "purityKarat", i.purityKarat(), "value", Math.round(v)));
        }
        CollateralValuation v = new CollateralValuation();
        v.setCollateralId(id);
        v.setSource("APPRAISAL");
        v.setValuer(by.name());
        v.setReportDate(LocalDate.now());
        v.setMarketValue(money((double) Math.round(value)));
        v.setMethod("Net weight × 22 carat rate ₹" + Math.round(rate22) + "/g × purity/22");
        v.setDetailsJson(Json.write(Map.of("items", rows, "netWeight", Math.round(net * 100) / 100.0, "rate22k", rate22)));
        v.setRecordedBy(by.username());
        valuations.save(v);
        // a newer appraisal replaces the earlier ones
        for (CollateralValuation old : valuations.findByCollateralIdOrderByIdAsc(id))
            if (!old.getId().equals(v.getId()) && "APPRAISAL".equals(old.getSource()) && old.isApproved()) { old.setApproved(false); valuations.save(old); }
        audit.record(by, "collateral.gold_appraised", "COLLATERAL", c.getColNo(), c.getApplicationId(), Math.round(net * 100) / 100.0 + " g net · ₹" + Math.round(value));
        evaluate(id, by);
        return v;
    }

    /** AI value cross-check: locality rate × area for property, depreciated new price for vehicles. Shown only, never used as value. */
    Optional<double[]> aiValue(Collateral c) {
        if (CollateralEngine.PROPERTY.contains(c.getType())) {
            if (c.getAreaSqft() == null || c.getPincode() == null) return Optional.empty();
            List<LocalityRate> rs = localityRates.findByTypeOrderByIdAsc(c.getType()).stream().filter(r -> c.getPincode().startsWith(r.getPinPrefix())).toList();
            if (rs.isEmpty()) return Optional.empty();
            double rate = rs.stream().mapToDouble(r -> r.getRatePerSqft().doubleValue()).average().orElse(0);
            double f = "POOR".equals(c.getCondition()) ? 0.85 : "AVERAGE".equals(c.getCondition()) ? 0.95 : 1.0;
            double conf = c.getPincode().length() == 6 && rs.get(0).getPinPrefix().length() >= 3 ? 0.6 : 0.4;
            return Optional.of(new double[]{c.getAreaSqft().doubleValue() * rate * f, conf});
        }
        if ("VEHICLE".equals(c.getType()) && c.getNewPrice() != null && c.getManufactureYear() != null) {
            int age = Math.max(0, LocalDate.now().getYear() - c.getManufactureYear());
            // IRDAI depreciation schedule for insured declared value, extended beyond 5 years
            double dep = age < 1 ? 0.15 : age < 2 ? 0.20 : age < 3 ? 0.30 : age < 4 ? 0.40 : age < 5 ? 0.50 : Math.min(0.85, 0.50 + 0.07 * (age - 4));
            return Optional.of(new double[]{c.getNewPrice().doubleValue() * (1 - dep), 0.5});
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- evaluation

    CollateralEngine.Policy enginePolicy(CollateralPolicy p) {
        return new CollateralEngine.Policy(p.getType(), p.getEcYears(), p.getTitleDepthYears(), p.getHaircut().doubleValue(), p.getMaxLtv().doubleValue(),
                p.getValuationMaxAgeMonths(), p.getSecondValuationAbove() == null ? null : p.getSecondValuationAbove().doubleValue(), p.getVarianceLimit().doubleValue(),
                p.getMinImages(), p.getGpsRadiusM(), p.getCoverageMin().doubleValue(), split(p.getRequiredDocs()), split(p.getRequiredPhotos()));
    }

    static List<String> split(String s) { return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList(); }

    /** Amount asked against this collateral: the application amount shared by declared value when there is more than one. */
    double requestedFor(Collateral c, LoanApplication a) {
        List<Collateral> all = collaterals.findByApplicationIdOrderByIdAsc(a.getId());
        if (all.size() <= 1) return a.getLoanAmount().doubleValue();
        double mine = valueOf(c), total = all.stream().mapToDouble(this::valueOf).sum();
        return total <= 0 ? a.getLoanAmount().doubleValue() / all.size() : a.getLoanAmount().doubleValue() * mine / total;
    }

    double valueOf(Collateral c) {
        return valuations.findByCollateralIdOrderByIdAsc(c.getId()).stream().filter(v -> v.isApproved() && !"AI".equals(v.getSource()))
                .mapToDouble(v -> v.getMarketValue().doubleValue()).min().orElse(0);
    }

    CollateralEngine.Inputs inputs(Collateral c, LoanApplication a) {
        List<CollateralEngine.Doc> docs = new ArrayList<>();
        // the latest version of each document type counts
        Map<String, CollateralDocument> latest = new LinkedHashMap<>();
        for (CollateralDocument d : documents.findByCollateralIdOrderByIdAsc(c.getId())) latest.put(d.getDocType(), d);
        for (CollateralDocument d : latest.values()) {
            Map<String, Object> ex = d.getExtractedJson() == null ? Map.of() : Json.read(d.getExtractedJson(), new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) ex.getOrDefault("fields", Map.of());
            @SuppressWarnings("unchecked") List<Map<String, Object>> tx = (List<Map<String, Object>>) ex.getOrDefault("transactions", List.of());
            double conf = d.getConfirmedBy() != null ? 1.0 : d.getReadConfidence() == null ? 0 : d.getReadConfidence().doubleValue();
            docs.add(new CollateralEngine.Doc(d.getId(), d.getDocType(), f, tx, conf, d.isManipulated()));
        }
        List<CollateralEngine.Media> ms = media.findByCollateralIdOrderByIdAsc(c.getId()).stream()
                .map(m -> new CollateralEngine.Media(m.getId(), m.getLabel(), m.getDistanceM(), m.getQuality(), m.getReuseMatch(),
                        m.getTamperScore() == null ? 0 : m.getTamperScore().doubleValue(), m.isManipulated())).toList();
        List<CollateralEngine.Val> vs = valuations.findByCollateralIdOrderByIdAsc(c.getId()).stream()
                .map(v -> new CollateralEngine.Val(v.getId(), v.getSource(), v.getReportDate(), v.getMarketValue().doubleValue(),
                        v.getRealisableValue() == null ? null : v.getRealisableValue().doubleValue(), v.getDistressValue() == null ? null : v.getDistressValue().doubleValue(),
                        v.isApproved(), v.getValuer())).toList();
        List<CollateralEngine.Ruling> rulings = reviews.findByCollateralIdOrderByIdAsc(c.getId()).stream()
                .filter(r -> Set.of("CLEARED", "UPHELD").contains(r.getStatus())).map(r -> new CollateralEngine.Ruling(r.getRuleId(), r.getReason(), r.getStatus())).toList();
        Optional<double[]> aiv = aiValue(c);
        return new CollateralEngine.Inputs(enginePolicy(pol(c.getType())), requestedFor(c, a),
                new CollateralEngine.Facts(c.getCondition(), c.getConstructionStage(), c.getOwnerName(), a.getApplicantName(), c.getSurveyNo(), c.getMunicipalId()),
                docs, ms, vs, aiv.map(x -> x[0]).orElse(null), aiv.map(x -> x[1]).orElse(0.0), rulings, LocalDate.now());
    }

    @Transactional
    public Map<String, Object> evaluate(Long id, CurrentUser by) {
        Collateral c = get(id, by);
        LoanApplication a = app(c.getApplicationId(), by);
        CollateralEngine.Inputs in = inputs(c, a);
        CollateralEngine.Result r = CollateralEngine.evaluate(in);
        String summary = summary(c, a, in, r);
        CollateralEvaluation e = new CollateralEvaluation();
        e.setCollateralId(id);
        e.setStatus(r.status());
        e.setRiskScore(r.riskScore());
        e.setRiskJson(Json.write(r.risk()));
        e.setRulesJson(Json.write(r.rules()));
        e.setLtvJson(Json.write(r.ltv()));
        e.setEcJson(Json.write(r.ec()));
        e.setTitleJson(Json.write(r.title()));
        e.setVisionJson(Json.write(r.vision()));
        e.setValuationJson(Json.write(Map.of("valuation", r.valuation(), "conditions", r.conditions())));
        e.setSummary(summary);
        e.setPolicyVersion(policy.current().map(p -> p.getVersion()).orElse(null));
        e.setModelVersion(CollateralEngine.MODEL_VERSION);
        e.setEvaluatedBy(by.username());
        evaluations.save(e);
        String before = c.getStatus();
        c.setStatus(r.status());
        c.setRiskScore(r.riskScore());
        c.setMaxExposure(BigDecimal.valueOf(((Number) r.ltv().get("maxExposure")).doubleValue()).setScale(2, RoundingMode.HALF_UP));
        c.setLtvCap(Boolean.TRUE.equals(r.ltv().get("ltvCap")));
        c.setLastEvaluatedAt(Instant.now());
        collaterals.save(c);
        syncReviews(c, r);
        if (!r.status().equals(before))
            audit.record(by, "collateral.evaluated", "COLLATERAL", c.getColNo(), c.getApplicationId(), before + " → " + r.status() + " · risk " + r.riskScore()
                    + " · " + r.rules().stream().filter(h -> !h.passed()).map(CollateralEngine.Hit::id).toList());
        refreshApplication(a);
        return view(c, by);
    }

    /** Opens a review for each new flag; closes open reviews whose flag has gone (the evidence changed). */
    void syncReviews(Collateral c, CollateralEngine.Result r) {
        List<CollateralReview> existing = reviews.findByCollateralIdOrderByIdAsc(c.getId());
        Set<String> needed = new HashSet<>();
        for (CollateralEngine.ReviewNeed n : r.reviews()) {
            needed.add(n.ruleId() + "|" + n.reason());
            boolean have = existing.stream().anyMatch(x -> "OPEN".equals(x.getStatus()) && x.getRuleId().equals(n.ruleId()) && x.getReason().equals(n.reason()));
            if (have) continue;
            CollateralReview v = new CollateralReview();
            v.setCollateralId(c.getId());
            v.setRuleId(n.ruleId());
            v.setType(n.type());
            v.setRole(n.role());
            v.setReason(n.reason().length() > 500 ? n.reason().substring(0, 497) + "..." : n.reason());
            v.setSlaHours(n.slaHours());
            v.setDueAt(Instant.now().plus(Duration.ofHours(n.slaHours())));
            reviews.save(v);
            needed.add(n.ruleId() + "|" + v.getReason());
        }
        for (CollateralReview x : existing) {
            if ("OPEN".equals(x.getStatus()) && !needed.contains(x.getRuleId() + "|" + x.getReason())) {
                x.setStatus("SUPERSEDED");
                x.setClosedAt(Instant.now());
                x.setNote("The flag cleared after new evidence");
                reviews.save(x);
            }
        }
    }

    /** Application-level collateral state, the workflow's collateral stage and vehicle/gold valuation stages. */
    void refreshApplication(LoanApplication a) {
        Product p = products.findByCode(a.getProductCode()).orElse(null);
        List<Collateral> cs = collaterals.findByApplicationIdOrderByIdAsc(a.getId());
        String st;
        if (cs.isEmpty()) st = p != null && p.isCollateralRequired() ? "PENDING" : "NOT_REQUIRED";
        else if (cs.stream().anyMatch(c -> "FAIL".equals(c.getStatus()))) st = "FAIL";
        else if (cs.stream().anyMatch(c -> "REFER".equals(c.getStatus()))) st = "REFER";
        else if (cs.stream().anyMatch(c -> "PENDING".equals(c.getStatus()))) st = "PENDING";
        else st = "PASS";
        if (!st.equals(a.getCollateralState())) {
            a.setCollateralState(st);
            apps.save(a);
        }
        if ("PASS".equals(st) || "FAIL".equals(st)) {
            String note = "Collateral " + st + " (" + cs.stream().map(Collateral::getColNo).collect(Collectors.joining(", ")) + ")";
            workflow.recordAutomatic(a, "col", st, note);
            if (cs.stream().allMatch(c -> "VEHICLE".equals(c.getType()))) workflow.recordAutomatic(a, "val", st, note);
            if (cs.stream().allMatch(c -> "GOLD".equals(c.getType()))) workflow.recordAutomatic(a, "gold", st, note);
        }
    }

    // ---------------------------------------------------------------- reviews

    @Transactional
    public Map<String, Object> resolve(Long reviewId, Resolve r, CurrentUser by) {
        CollateralReview v = reviews.findById(reviewId).orElseThrow(() -> ApiException.notFound("Review"));
        Collateral c = get(v.getCollateralId(), by);
        if (!"OPEN".equals(v.getStatus())) throw ApiException.conflict("CLOSED", "This review is already " + v.getStatus().toLowerCase());
        boolean allowed = by.role().name().equals(v.getRole()) || Set.of(Role.ADMIN, Role.CRO).contains(by.role())
                || ("CREDIT_OFFICER".equals(v.getRole()) && by.role() == Role.CREDIT_MANAGER);
        if (!allowed) throw ApiException.forbidden("NOT_YOUR_QUEUE", "This review is for " + v.getRole().replace('_', ' ').toLowerCase());
        if (r.outcome() == null || !Set.of("CLEARED", "UPHELD").contains(r.outcome())) throw ApiException.unprocessable("BAD_OUTCOME", "Outcome must be CLEARED or UPHELD");
        if (r.note() == null || r.note().trim().length() < 15) throw ApiException.unprocessable("REASON_REQUIRED", "Every review needs a reason of at least 15 characters");
        v.setStatus(r.outcome());
        v.setReviewer(by.username());
        v.setNote(r.note().trim());
        v.setClosedAt(Instant.now());
        reviews.save(v);
        audit.record(by, "collateral.review_" + r.outcome().toLowerCase(), "COLLATERAL", c.getColNo(), c.getApplicationId(), v.getRuleId() + " · " + v.getType() + " · " + r.note().trim());
        return evaluate(c.getId(), by);
    }

    public List<Map<String, Object>> queue(CurrentUser u, String status) {
        List<CollateralReview> rs = "ALL".equals(status) ? reviews.findAll().stream().sorted(Comparator.comparing(CollateralReview::getId).reversed()).limit(300).toList()
                : reviews.findByStatusOrderByDueAtAsc("OPEN");
        boolean all = Set.of(Role.ADMIN, Role.CRO, Role.CREDIT_MANAGER, Role.COMPLIANCE).contains(u.role());
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollateralReview r : rs) {
            if (!all && !u.role().name().equals(r.getRole())) continue;
            Collateral c = collaterals.findById(r.getCollateralId()).orElse(null);
            if (c == null) continue;
            LoanApplication a;
            try { a = app(c.getApplicationId(), u); } catch (ApiException e) { continue; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("review", r);
            m.put("overdue", "OPEN".equals(r.getStatus()) && r.getDueAt().isBefore(Instant.now()));
            m.put("collateralId", c.getId());
            m.put("colNo", c.getColNo());
            m.put("type", c.getType());
            m.put("appNo", a.getAppNo());
            m.put("applicationId", a.getId());
            m.put("applicant", a.getApplicantName());
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- views

    double completeness(Collateral c) {
        List<String> req = split(pol(c.getType()).getRequiredDocs());
        if ("GOLD".equals(c.getType())) return valuations.findByCollateralIdOrderByIdAsc(c.getId()).stream().anyMatch(CollateralValuation::isApproved) ? 1 : 0;
        Set<String> have = documents.findByCollateralIdOrderByIdAsc(c.getId()).stream().map(CollateralDocument::getDocType).collect(Collectors.toSet());
        return req.isEmpty() ? 1 : Math.round(100.0 * req.stream().filter(have::contains).count() / req.size()) / 100.0;
    }

    Map<String, Object> row(Collateral c, LoanApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("colNo", c.getColNo());
        m.put("type", c.getType());
        m.put("description", c.getDescription());
        m.put("applicationId", a.getId());
        m.put("appNo", a.getAppNo());
        m.put("applicant", a.getApplicantName());
        m.put("productCode", a.getProductCode());
        double value = valueOf(c);
        m.put("value", Math.round(value));
        m.put("requested", Math.round(requestedFor(c, a)));
        m.put("ltv", value > 0 ? Math.round(10000 * requestedFor(c, a) / value) / 10000.0 : null);
        m.put("maxExposure", c.getMaxExposure());
        m.put("ltvCap", c.isLtvCap());
        m.put("riskScore", c.getRiskScore());
        m.put("completeness", completeness(c));
        m.put("status", c.getStatus());
        m.put("openReviews", reviews.findByCollateralIdOrderByIdAsc(c.getId()).stream().filter(r -> "OPEN".equals(r.getStatus())).count());
        return m;
    }

    public Map<String, Object> overview(CurrentUser u, String status, String type) {
        Set<Long> vis = scope.visibleBranches(u);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Collateral c : collaterals.findAllByOrderByIdDesc()) {
            LoanApplication a = apps.findById(c.getApplicationId()).orElse(null);
            if (a == null || vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) continue;
            if (status != null && !status.isBlank() && !status.equals(c.getStatus())) continue;
            if (type != null && !type.isBlank() && !type.equals(c.getType())) continue;
            rows.add(row(c, a));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collaterals", rows);
        m.put("byStatus", rows.stream().collect(Collectors.groupingBy(r -> (String) r.get("status"), TreeMap::new, Collectors.counting())));
        m.put("totalValue", rows.stream().mapToLong(r -> (Long) r.get("value")).sum());
        m.put("openReviews", rows.stream().mapToLong(r -> (Long) r.get("openReviews")).sum());
        m.put("aiConfigured", ai.configured());
        return m;
    }

    public List<Map<String, Object>> forApplication(Long appId, CurrentUser u) {
        LoanApplication a = app(appId, u);
        return collaterals.findByApplicationIdOrderByIdAsc(appId).stream().map(c -> row(c, a)).toList();
    }

    /** Collateral 360 (SCR-148) and the data behind the case tabs (SCR-141 to SCR-146). */
    public Map<String, Object> view(Collateral c, CurrentUser u) {
        LoanApplication a = app(c.getApplicationId(), u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collateral", c);
        m.put("summary", row(c, a));
        m.put("policy", pol(c.getType()));
        List<Map<String, Object>> ds = new ArrayList<>();
        for (CollateralDocument d : documents.findByCollateralIdOrderByIdAsc(c.getId())) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("document", d);
            x.put("extracted", d.getExtractedJson() == null ? Map.of() : Json.read(d.getExtractedJson(), new TypeReference<Map<String, Object>>() {}));
            x.put("legalFlag", LEGAL_FLAG_DOCS.contains(d.getDocType()));
            ds.add(x);
        }
        m.put("documents", ds);
        m.put("media", media.findByCollateralIdOrderByIdAsc(c.getId()));
        m.put("valuations", valuations.findByCollateralIdOrderByIdAsc(c.getId()));
        m.put("reviews", reviews.findByCollateralIdOrderByIdAsc(c.getId()));
        evaluations.findFirstByCollateralIdOrderByIdDesc(c.getId()).ifPresent(e -> m.put("evaluation", evaluationView(e)));
        m.put("unified", unified(a, u));
        return m;
    }

    static Map<String, Object> evaluationView(CollateralEvaluation e) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", e.getId());
        x.put("status", e.getStatus());
        x.put("riskScore", e.getRiskScore());
        x.put("risk", Json.read(e.getRiskJson(), new TypeReference<List<Object>>() {}));
        x.put("rules", Json.read(e.getRulesJson(), new TypeReference<List<Object>>() {}));
        x.put("ltv", Json.read(e.getLtvJson(), new TypeReference<Map<String, Object>>() {}));
        x.put("ec", e.getEcJson() == null ? Map.of() : Json.read(e.getEcJson(), new TypeReference<Map<String, Object>>() {}));
        x.put("title", e.getTitleJson() == null ? List.of() : Json.read(e.getTitleJson(), new TypeReference<List<Object>>() {}));
        x.put("vision", e.getVisionJson() == null ? Map.of() : Json.read(e.getVisionJson(), new TypeReference<Map<String, Object>>() {}));
        Map<String, Object> v = e.getValuationJson() == null ? Map.of() : Json.read(e.getValuationJson(), new TypeReference<Map<String, Object>>() {});
        x.put("valuation", v.get("valuation"));
        x.put("conditions", v.get("conditions"));
        x.put("summary", e.getSummary());
        x.put("policyVersion", e.getPolicyVersion());
        x.put("modelVersion", e.getModelVersion());
        x.put("evaluatedAt", e.getEvaluatedAt());
        x.put("evaluatedBy", e.getEvaluatedBy());
        return x;
    }

    public Map<String, Object> detail(Long id, CurrentUser u) { return view(get(id, u), u); }

    /** Case summary with evidence IDs (SCR-148). Facts only; no opinion on title or value. */
    static String summary(Collateral c, LoanApplication a, CollateralEngine.Inputs in, CollateralEngine.Result r) {
        StringBuilder b = new StringBuilder();
        b.append(c.getColNo()).append(" · ").append(c.getType().toLowerCase().replace('_', ' ')).append(c.getAddress() == null ? "" : " at " + c.getAddress()).append(". ");
        b.append("Status ").append(r.status()).append(", risk ").append(r.riskScore()).append("/100. ");
        Object vu = r.ltv().get("valueUsed");
        if (vu instanceof Number n && n.doubleValue() > 0)
            b.append("Value used ₹").append(n.longValue()).append(", eligible after ").append(Math.round(in.pol().haircut() * 100)).append("% haircut ₹").append(r.ltv().get("eligibleValue"))
                    .append(", maximum exposure ₹").append(r.ltv().get("maxExposure")).append(" at ").append(Math.round(((Number) r.ltv().get("maxLtv")).doubleValue() * 100)).append("% LTV")
                    .append(Boolean.TRUE.equals(r.ltv().get("ltvCap")) ? " (LTV CAP: requested ₹" + r.ltv().get("requested") + ")" : "").append(". ");
        if (r.ec().get("latestOwner") != null) b.append("EC [DOC-").append(r.ec().get("docId")).append("] shows the latest owner as ").append(r.ec().get("latestOwner")).append(". ");
        for (CollateralEngine.Hit h : r.rules())
            if (!h.passed() || h.ruling() != null)
                b.append(h.id()).append(h.ruling() == null ? " flagged" : " " + h.ruling().toLowerCase()).append(": ").append(h.reason())
                        .append(h.evidence().isEmpty() ? "" : " [" + String.join(", ", h.evidence()) + "]").append(". ");
        if (r.rules().stream().allMatch(CollateralEngine.Hit::passed)) b.append("All collateral rules met. ");
        b.append("AI flags evidence only; title is certified by the lender's advocate and value by the empanelled valuer.");
        return b.toString();
    }

    /** The unified credit + collateral decision for an application (FSD 7, POST /decision collateral block). */
    public Map<String, Object> unified(LoanApplication a, CurrentUser u) {
        List<Collateral> cs = collaterals.findByApplicationIdOrderByIdAsc(a.getId());
        String credit = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).map(DecisionRecord::getDecision).orElse(null);
        List<String> conditions = new ArrayList<>();
        Double maxExposure = null;
        for (Collateral c : cs) {
            evaluations.findFirstByCollateralIdOrderByIdDesc(c.getId()).ifPresent(e -> {
                Map<String, Object> v = Json.read(e.getValuationJson(), new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked") List<String> cc = (List<String>) v.getOrDefault("conditions", List.of());
                for (String x : cc) if (!conditions.contains(x)) conditions.add(x);
            });
            if (c.getMaxExposure() != null) maxExposure = (maxExposure == null ? 0 : maxExposure) + c.getMaxExposure().doubleValue();
        }
        Map<String, Object> m = CollateralEngine.unified(credit, cs.isEmpty() ? ("NOT_REQUIRED".equals(a.getCollateralState()) ? "PASS" : null) : a.getCollateralState(),
                conditions, maxExposure, a.getLoanAmount().doubleValue());
        m.put("collaterals", cs.stream().map(c -> Map.of("colNo", c.getColNo(), "type", c.getType(), "status", c.getStatus(),
                "riskScore", c.getRiskScore() == null ? 0 : c.getRiskScore(), "maxExposure", c.getMaxExposure() == null ? 0 : c.getMaxExposure())).toList());
        return m;
    }

    public Map<String, Object> unifiedFor(Long appId, CurrentUser u) { return unified(app(appId, u), u); }

    public record SanctionCheck(boolean blocked, String reason, Double maxExposure, List<String> conditions) {}

    /** What sanction must respect: collateral passed, the exposure cap and the collateral conditions (COL-BR-13). */
    public SanctionCheck sanctionCheck(LoanApplication a) {
        Product p = products.findByCode(a.getProductCode()).orElse(null);
        List<Collateral> cs = collaterals.findByApplicationIdOrderByIdAsc(a.getId());
        if ((p == null || !p.isCollateralRequired()) && cs.isEmpty()) return new SanctionCheck(false, null, null, List.of());
        if (cs.isEmpty()) return new SanctionCheck(true, "Declare the collateral and run Collateral AI before sanction", null, List.of());
        if (!"PASS".equals(a.getCollateralState()))
            return new SanctionCheck(true, "Collateral is " + a.getCollateralState() + ": clear the open collateral reviews first", null, List.of());
        Map<String, Object> u = unified(a, CurrentUser.SYSTEM);
        @SuppressWarnings("unchecked") List<String> cond = (List<String>) u.getOrDefault("conditions", List.of());
        double max = cs.stream().mapToDouble(c -> c.getMaxExposure() == null ? 0 : c.getMaxExposure().doubleValue()).sum();
        return new SanctionCheck(false, null, max, cond);
    }

    /** Evidence → feature → rule → decision → reviewer (SCR-149). */
    public Map<String, Object> trail(Long id, CurrentUser u) {
        Collateral c = get(id, u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("colNo", c.getColNo());
        List<Map<String, Object>> evidence = new ArrayList<>();
        documents.findByCollateralIdOrderByIdAsc(id).forEach(d -> evidence.add(Map.of("id", "DOC-" + d.getId(), "kind", d.getDocType(), "hash", d.getFileHash(), "engine", Objects.toString(d.getEngine(), ""),
                "by", d.getUploadedBy(), "at", d.getUploadedAt().toString(), "confirmedBy", Objects.toString(d.getConfirmedBy(), ""))));
        media.findByCollateralIdOrderByIdAsc(id).forEach(x -> evidence.add(Map.of("id", "IMG-" + x.getId(), "kind", x.getLabel(), "hash", x.getSha256(), "engine", Objects.toString(x.getQuality(), ""),
                "by", x.getUploadedBy(), "at", x.getUploadedAt().toString(), "confirmedBy", "")));
        valuations.findByCollateralIdOrderByIdAsc(id).forEach(v -> evidence.add(Map.of("id", "VAL-" + v.getId(), "kind", v.getSource(), "hash", "", "engine", Objects.toString(v.getMethod(), ""),
                "by", v.getRecordedBy(), "at", v.getRecordedAt().toString(), "confirmedBy", Objects.toString(v.getValuer(), ""))));
        m.put("evidence", evidence);
        m.put("evaluations", evaluations.findByCollateralIdOrderByIdDesc(id).stream().limit(20).map(CollateralService::evaluationView).toList());
        m.put("reviews", reviews.findByCollateralIdOrderByIdAsc(id));
        return m;
    }

    // ---------------------------------------------------------------- monitoring (COL-BR-11, 12)

    public List<Map<String, Object>> alerts(CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Collateral c : collaterals.findAllByOrderByIdDesc()) {
            LoanApplication a = apps.findById(c.getApplicationId()).orElse(null);
            if (a == null || !"DISBURSED".equals(a.getAppState())) continue;
            if (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) continue;
            CollateralPolicy p = pol(c.getType());
            LocalDate last = valuations.findByCollateralIdOrderByIdAsc(c.getId()).stream().filter(CollateralValuation::isApproved).map(CollateralValuation::getReportDate)
                    .max(LocalDate::compareTo).orElse(null);
            if (last != null && ChronoUnit.MONTHS.between(last, today) >= p.getRevalueMonths())
                out.add(alert(c, a, "REVALUATION_DUE", "Last valued " + last + "; policy revalues every " + p.getRevalueMonths() + " months"));
            if (c.getInsuranceExpiry() != null && !c.getInsuranceExpiry().isAfter(today.plusDays(30)) && !"GOLD".equals(c.getType()))
                out.add(alert(c, a, c.getInsuranceExpiry().isBefore(today) ? "INSURANCE_EXPIRED" : "INSURANCE_EXPIRING", "Insurance expires " + c.getInsuranceExpiry()));
            double value = valueOf(c);
            Optional<LiveLoan> l = liveLoans.findByApplicationId(a.getId());
            if (l.isPresent() && value > 0) {
                double ltv = l.get().getOutstanding().doubleValue() / value;
                double max = "GOLD".equals(c.getType()) ? CollateralEngine.goldMaxLtv(l.get().getSanctionedAmount().doubleValue()) : p.getMaxLtv().doubleValue();
                if (ltv > max) out.add(alert(c, a, "LTV_BREACH", "Outstanding is " + Math.round(ltv * 100) + "% of the value (limit " + Math.round(max * 100) + "%)"));
            }
        }
        return out;
    }

    static Map<String, Object> alert(Collateral c, LoanApplication a, String kind, String text) {
        return Map.of("collateralId", c.getId(), "colNo", c.getColNo(), "appNo", a.getAppNo(), "type", c.getType(), "alert", kind, "detail", text);
    }

    // ---------------------------------------------------------------- policy and masters

    public List<CollateralPolicy> policies() { return policies.findAll().stream().sorted(Comparator.comparing(CollateralPolicy::getType)).toList(); }

    @Transactional
    public CollateralPolicy updatePolicy(String type, Map<String, Object> r, CurrentUser by) {
        CollateralPolicy p = pol(type);
        Object note = r.get("note");
        if (note == null || note.toString().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Record the approval or reason (at least 10 characters)");
        double haircut = r.get("haircut") == null ? p.getHaircut().doubleValue() : CollateralEngine.num(r.get("haircut"));
        double maxLtv = r.get("maxLtv") == null ? p.getMaxLtv().doubleValue() : CollateralEngine.num(r.get("maxLtv"));
        if (haircut < 0 || haircut > 0.6 || maxLtv <= 0 || maxLtv > ("GOLD".equals(type) ? 0.85 : 0.9))
            throw ApiException.unprocessable("OUT_OF_RANGE", "Haircut 0–60%; maximum LTV up to 90% (85% for gold, the RBI cap)");
        String before = Json.write(p);
        p.setHaircut(BigDecimal.valueOf(haircut));
        p.setMaxLtv(BigDecimal.valueOf(maxLtv));
        if (r.get("ecYears") != null) p.setEcYears((int) CollateralEngine.num(r.get("ecYears")));
        if (r.get("valuationMaxAgeMonths") != null) p.setValuationMaxAgeMonths((int) CollateralEngine.num(r.get("valuationMaxAgeMonths")));
        if (r.get("varianceLimit") != null) p.setVarianceLimit(BigDecimal.valueOf(CollateralEngine.num(r.get("varianceLimit"))));
        if (r.get("gpsRadiusM") != null) p.setGpsRadiusM((int) CollateralEngine.num(r.get("gpsRadiusM")));
        if (r.get("requiredDocs") != null) p.setRequiredDocs(r.get("requiredDocs").toString());
        p.setUpdatedBy(by.username());
        p.setUpdatedAt(Instant.now());
        policies.save(p);
        audit.record(by, "collateral.policy_updated", "COLLATERAL_POLICY", type, null, note + " · before " + before);
        return p;
    }

    @Transactional
    public GoldRate setGoldRate(String date, Double rate22k, CurrentUser by) {
        if (rate22k == null || rate22k < 1000 || rate22k > 100000) throw ApiException.unprocessable("BAD_RATE", "22 carat rate per gram looks wrong");
        LocalDate d = date == null ? LocalDate.now() : LocalDate.parse(date);
        GoldRate g = goldRates.findByRateDate(d).orElseGet(GoldRate::new);
        g.setRateDate(d);
        g.setRate22k(BigDecimal.valueOf(rate22k).setScale(2, RoundingMode.HALF_UP));
        g.setSource("ENTERED");
        g.setEnteredBy(by.username());
        goldRates.save(g);
        audit.record(by, "collateral.gold_rate", "GOLD_RATE", d.toString(), null, "₹" + rate22k + "/g (22 carat)");
        return g;
    }

    public Map<String, Object> rates() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gold", goldRates.findByRateDateAfterOrderByRateDateDesc(LocalDate.now().minusDays(31)));
        double g;
        try { g = goldRate(); } catch (ApiException e) { g = 0; }
        m.put("goldRateUsed", Math.round(g));
        m.put("locality", localityRates.findAll());
        return m;
    }
}
