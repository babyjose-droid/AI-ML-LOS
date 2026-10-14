package com.rhythm.los.field;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionService;
import com.rhythm.los.document.AppDocument;
import com.rhythm.los.document.DocumentReader;
import com.rhythm.los.document.DocumentService;
import com.rhythm.los.document.StorageService;
import com.rhythm.los.flow.StageAccess;
import com.rhythm.los.integration.IntegrationGateway;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.security.CurrentUser;
import com.rhythm.los.workflow.ProcessService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Field visits from the field officer app (FSD 11.4): captured on the phone, offline if needed, and synced with the
 * check-in position and time, the product checklist, references, remarks and geo-tagged photos. On sync the LOS checks
 * the check-in is within 150 m of the business location, each photo's time and place against the check-in, photo reuse
 * across all applications, and asks the AI service for the scene, shelf fill and the owner's face against the KYC photo.
 */
@Service
public class FieldVisitService {
    static final double RADIUS_M = 150;
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<String> KINDS = Set.of("front", "inside", "stock", "owner", "animals", "vehicle", "odometer", "residence", "other");
    static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/heic");
    static final long MAX_BYTES = 10L * 1024 * 1024;

    public record PhotoMeta(String kind, String takenAt, Double lat, Double lng) {}
    public record Reference(String name, String phone, String says) {}
    public record VisitIn(String clientId, String checkinAt, Double checkinLat, Double checkinLng, Double checkinAccuracy, Boolean capturedOffline,
                          Map<String, Object> answers, List<Reference> references, String remarks, List<PhotoMeta> photos) {}
    public record Check(String check, String status, String detail) {}

    private final FieldVisitRepository visits;
    private final FieldPhotoRepository photos;
    private final ApplicationService apps;
    private final LoanApplicationRepository appRepo;
    private final StageAccess access;
    private final DataScope scope;
    private final StorageService storage;
    private final DocumentService documents;
    private final DocumentReader reader;
    private final IntegrationGateway gateway;
    private final ProcessService process;
    private final DecisionService decisions;
    private final AuditService audit;

    public FieldVisitService(FieldVisitRepository visits, FieldPhotoRepository photos, ApplicationService apps, LoanApplicationRepository appRepo,
                             StageAccess access, DataScope scope, StorageService storage, DocumentService documents, DocumentReader reader,
                             IntegrationGateway gateway, ProcessService process, DecisionService decisions, AuditService audit) {
        this.visits = visits;
        this.photos = photos;
        this.apps = apps;
        this.appRepo = appRepo;
        this.access = access;
        this.scope = scope;
        this.storage = storage;
        this.documents = documents;
        this.reader = reader;
        this.gateway = gateway;
        this.process = process;
        this.decisions = decisions;
        this.audit = audit;
    }

    /** Field readings (stock value, shop size, animals...) feed the industry scorecards on the next decision. */
    @PostConstruct
    void feedScorecards() { decisions.setFieldData(this::numbers); }

    Map<String, Double> numbers(LoanApplication a) {
        if (a.getId() == null) return Map.of();
        return visits.findByApplicationIdOrderByIdDesc(a.getId()).stream().findFirst().map(v -> {
            Map<String, Double> m = new HashMap<>();
            Map<String, Object> ans = Json.read(v.getChecklistJson(), new TypeReference<Map<String, Object>>() {});
            ans.forEach((k, x) -> {
                if (x instanceof Number n) m.put(k, n.doubleValue());
                else if (x instanceof Boolean b) m.put(k, b ? 1.0 : 0.0);
            });
            return m;
        }).orElse(Map.of());
    }

    /** Visits waiting for this officer: field visit required, the workflow gives the field stage to their role, in their branches. */
    public List<Map<String, Object>> assigned(CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LoanApplication a : appRepo.findAllByOrderByIdDesc()) {
            if (!"REQUIRED".equals(a.getFieldState()) || !ApplicationService.isOpen(a) || !DataScope.canSee(vis, a) || !access.can(a, "field", u)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("applicationId", a.getId());
            m.put("appNo", a.getAppNo());
            m.put("applicant", a.getApplicantName());
            m.put("mobile", a.getMobile());
            m.put("product", a.getProductCode());
            m.put("amount", a.getLoanAmount());
            m.put("business", a.getBusinessName());
            m.put("address", String.join(", ", Arrays.stream(new String[]{a.getAddress(), a.getCity(), a.getPincode()}).filter(x -> x != null && !x.isBlank()).toList()));
            m.put("addressLat", a.getAddressLat());
            m.put("addressLng", a.getAddressLng());
            m.put("checklist", FieldChecklists.forApplication(a.getIndustry(), a.getSegment()));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> forApplication(Long appId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FieldVisit v : visits.findByApplicationIdOrderByIdDesc(appId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("visit", v);
            m.put("answers", Json.read(v.getChecklistJson(), Map.class));
            m.put("references", v.getReferencesJson() == null ? List.of() : Json.read(v.getReferencesJson(), List.class));
            m.put("checks", v.getPhotoChecksJson() == null ? List.of() : Json.read(v.getPhotoChecksJson(), List.class));
            List<Map<String, Object>> ps = new ArrayList<>();
            for (FieldPhoto p : photos.findByVisitIdOrderByIdAsc(v.getId())) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("photo", p);
                pm.put("checks", p.getChecksJson() == null ? List.of() : Json.read(p.getChecksJson(), List.class));
                ps.add(pm);
            }
            m.put("photos", ps);
            out.add(m);
        }
        return out;
    }

    public FieldPhoto photo(Long id, CurrentUser u) {
        FieldPhoto p = photos.findById(id).orElseThrow(() -> ApiException.notFound("Photo"));
        LoanApplication a = apps.get(p.getApplicationId());
        if (!DataScope.canSee(scope.visibleBranches(u), a)) throw ApiException.notFound("Photo");
        return p;
    }

    public byte[] content(FieldPhoto p) { return storage.read(p.getFilePath()); }

    static Instant parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    @Transactional
    public Map<String, Object> submit(Long appId, VisitIn in, List<MultipartFile> files, CurrentUser by) {
        if (in == null || in.clientId() == null || !in.clientId().matches("[A-Za-z0-9-]{8,64}"))
            throw ApiException.unprocessable("BAD_CLIENT_ID", "The visit needs the id the app gave it");
        Optional<FieldVisit> again = visits.findByClientId(in.clientId());
        if (again.isPresent()) {
            if (!again.get().getApplicationId().equals(appId)) throw ApiException.conflict("CLIENT_ID_USED", "This visit id belongs to another application");
            return Map.of("visitId", again.get().getId(), "duplicate", true, "outcome", again.get().getOutcome());
        }
        LoanApplication a = apps.get(appId);
        if (!DataScope.canSee(scope.visibleBranches(by), a)) throw ApiException.notFound("Application");
        if (!access.can(a, "field", by)) throw ApiException.forbidden("NOT_YOUR_STAGE", "The field visit for this application is not assigned to your role");
        if (!"REQUIRED".equals(a.getFieldState())) throw ApiException.conflict("FIELD_NOT_REQUIRED", "No field visit is pending (" + a.getFieldState() + ")");
        Instant checkin = parseTime(in.checkinAt());
        Instant now = Instant.now();
        if (checkin == null) throw ApiException.unprocessable("BAD_CHECKIN", "Check-in time is missing");
        if (checkin.isAfter(now.plus(Duration.ofMinutes(10)))) throw ApiException.unprocessable("BAD_CHECKIN", "Check-in time is in the future");
        if (checkin.isBefore(now.minus(Duration.ofDays(4)))) throw ApiException.unprocessable("STALE_VISIT", "The visit was captured more than 3 days ago; visit again");
        if (in.remarks() == null || in.remarks().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Write officer remarks of at least 10 characters");

        FieldChecklists.Checklist cl = FieldChecklists.forApplication(a.getIndustry(), a.getSegment());
        Map<String, Object> answers = new LinkedHashMap<>();
        Map<String, Object> raw = in.answers() == null ? Map.of() : in.answers();
        for (FieldChecklists.Item it : cl.items()) {
            Object v = raw.get(it.key());
            if (v == null || (v instanceof String s && s.isBlank())) {
                if (it.required()) throw ApiException.unprocessable("CHECKLIST_INCOMPLETE", "Answer: " + it.label());
                continue;
            }
            switch (it.type().split(":")[0]) {
                case "YESNO" -> {
                    if (!(v instanceof Boolean)) throw ApiException.unprocessable("BAD_ANSWER", it.label() + ": yes or no");
                    answers.put(it.key(), v);
                }
                case "NUMBER" -> {
                    if (!(v instanceof Number n) || n.doubleValue() < 0) throw ApiException.unprocessable("BAD_ANSWER", it.label() + ": a number, 0 or more");
                    answers.put(it.key(), n.doubleValue());
                }
                default -> {
                    List<String> ok = List.of(it.type().substring(it.type().indexOf(':') + 1).split("\\|"));
                    if (!ok.contains(String.valueOf(v))) throw ApiException.unprocessable("BAD_ANSWER", it.label() + ": one of " + ok);
                    answers.put(it.key(), String.valueOf(v));
                }
            }
        }
        List<PhotoMeta> metas = in.photos() == null ? List.of() : in.photos();
        List<MultipartFile> fs = files == null ? List.of() : files;
        if (metas.size() != fs.size()) throw ApiException.unprocessable("PHOTO_MISMATCH", "Each photo needs its details (" + metas.size() + " vs " + fs.size() + " files)");
        if (fs.size() > 12) throw ApiException.unprocessable("TOO_MANY_PHOTOS", "At most 12 photos per visit");
        Set<String> kinds = new HashSet<>();
        for (int i = 0; i < fs.size(); i++) {
            PhotoMeta m = metas.get(i);
            if (m.kind() == null || !KINDS.contains(m.kind())) throw ApiException.unprocessable("BAD_KIND", "Unknown photo kind " + m.kind());
            MultipartFile f = fs.get(i);
            if (f.isEmpty() || f.getSize() > MAX_BYTES) throw ApiException.unprocessable("BAD_PHOTO", "Each photo must be under 10 MB");
            if (f.getContentType() == null || !TYPES.contains(f.getContentType())) throw ApiException.unprocessable("BAD_PHOTO", "Photos must be JPEG, PNG, WebP or HEIC");
            kinds.add(m.kind());
        }
        List<String> missingPhotos = cl.photos().stream().filter(k -> !kinds.contains(k)).toList();
        if (!missingPhotos.isEmpty()) throw ApiException.unprocessable("PHOTOS_MISSING", "Take these photos: " + String.join(", ", missingPhotos));

        FieldVisit v = new FieldVisit();
        v.setApplicationId(appId);
        v.setClientId(in.clientId());
        v.setOfficer(by.username());
        v.setCheckinAt(checkin);
        v.setCheckinLat(in.checkinLat());
        v.setCheckinLng(in.checkinLng());
        v.setCheckinAccuracyM(in.checkinAccuracy());
        v.setCapturedOffline(Boolean.TRUE.equals(in.capturedOffline()));
        v.setChecklistJson(Json.write(answers));
        v.setReferencesJson(in.references() == null || in.references().isEmpty() ? null : Json.write(in.references().stream().limit(5).toList()));
        v.setRemarks(in.remarks().trim().length() > 2000 ? in.remarks().trim().substring(0, 2000) : in.remarks().trim());
        List<Check> visitChecks = new ArrayList<>();
        if (in.checkinLat() == null || in.checkinLng() == null) {
            v.setLocationCheck("NO_GPS");
            visitChecks.add(new Check("Check-in location", "FAIL", "No GPS position with the check-in"));
        } else if (a.getAddressLat() == null || a.getAddressLng() == null) {
            v.setLocationCheck("NO_ADDRESS");
            visitChecks.add(new Check("Check-in location", "WARN", "The application has no saved business location to compare with"));
        } else {
            double d = PhotoHash.metres(in.checkinLat(), in.checkinLng(), a.getAddressLat(), a.getAddressLng());
            v.setDistanceM(Math.round(d * 10) / 10.0);
            double allowed = RADIUS_M + Math.min(50, in.checkinAccuracy() == null ? 0 : in.checkinAccuracy());
            v.setLocationCheck(d <= allowed ? "OK" : "FAR");
            visitChecks.add(new Check("Check-in location", d <= allowed ? "OK" : "FAIL", Math.round(d) + " m from the business location (limit " + Math.round(RADIUS_M) + " m)"));
        }
        v.setOutcome("PENDING");
        visits.save(v);

        // store photos and run the checks the LOS can do itself
        List<FieldPhoto> saved = new ArrayList<>();
        Map<Long, List<Check>> checks = new LinkedHashMap<>();
        List<FieldPhoto> others = photos.findByApplicationIdNot(appId);
        boolean reused = false;
        for (int i = 0; i < fs.size(); i++) {
            PhotoMeta m = metas.get(i);
            MultipartFile f = fs.get(i);
            byte[] bytes;
            try { bytes = f.getBytes(); } catch (Exception e) { throw ApiException.unprocessable("BAD_PHOTO", "Could not read a photo"); }
            FieldPhoto p = new FieldPhoto();
            p.setVisitId(v.getId());
            p.setApplicationId(appId);
            p.setKind(m.kind());
            p.setContentType(f.getContentType());
            p.setSizeBytes(bytes.length);
            p.setSha256(PhotoHash.sha256(bytes));
            p.setDhash(PhotoHash.dhash(bytes));
            p.setTakenAt(parseTime(m.takenAt()));
            p.setLat(m.lat());
            p.setLng(m.lng());
            p.setFilePath(storage.store(appId, bytes, f.getContentType().equals("image/png") ? ".png" : ".jpg"));
            photos.save(p);
            saved.add(p);
            List<Check> c = new ArrayList<>();
            // reuse across applications: the same file, or a near copy (perceptual hash within 6 bits)
            Optional<FieldPhoto> copy = others.stream().filter(o -> o.getSha256().equals(p.getSha256())
                    || (p.getDhash() != null && PhotoHash.distance(o.getDhash(), p.getDhash()) <= 6)).findFirst();
            if (copy.isPresent()) {
                reused = true;
                String other = appRepo.findById(copy.get().getApplicationId()).map(LoanApplication::getAppNo).orElse("another application");
                c.add(new Check("Photo reuse", "FAIL", "Same or near-identical photo already used on " + other));
            } else c.add(new Check("Photo reuse", "OK", "Not seen on any other application"));
            if (p.getTakenAt() == null) c.add(new Check("Photo time", "WARN", "No capture time from the app"));
            else {
                long mins = Duration.between(checkin, p.getTakenAt()).toMinutes();
                boolean ok = mins >= -60 && mins <= 180;
                c.add(new Check("Photo time", ok ? "OK" : "FAIL", ok ? "Taken during the visit" : "Taken " + Math.abs(mins) + " minutes " + (mins < 0 ? "before" : "after") + " the check-in"));
            }
            if (p.getLat() == null || in.checkinLat() == null) c.add(new Check("Photo location", "WARN", "No GPS position with the photo"));
            else {
                double d = PhotoHash.metres(p.getLat(), p.getLng(), in.checkinLat(), in.checkinLng());
                c.add(new Check("Photo location", d <= RADIUS_M ? "OK" : "FAIL", Math.round(d) + " m from the check-in"));
            }
            checks.put(p.getId(), c);
        }

        // the AI checks: EXIF, scene, shelf fill, owner face against the KYC photo
        boolean aiDone = false;
        if (reader.analysesVideoKyc()) {
            List<DocumentReader.Part> parts = new ArrayList<>();
            for (int i = 0; i < saved.size(); i++)
                parts.add(new DocumentReader.Part(saved.get(i).getKind() + "-" + (i + 1) + ".jpg", storage.read(saved.get(i).getFilePath()), saved.get(i).getContentType()));
            List<DocumentReader.Part> ids = new ArrayList<>();
            for (AppDocument d : documents.list(appId)) {
                boolean idDoc = Set.of("PAN", "AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PASSPORT", "PHOTO").contains(d.getDocType());
                if (idDoc && !"REJECTED".equals(d.getStatus()) && d.getContentType() != null && d.getContentType().startsWith("image/"))
                    ids.add(new DocumentReader.Part(d.getDocType() + ".img", documents.content(d), d.getContentType()));
            }
            var out = gateway.call(appId, reader.name(), "field.photos", () -> reader.analyzeFieldPhotos(parts, ids), r -> parts.size() + " photos checked", null);
            if (out.ok()) {
                aiDone = true;
                Map<String, Object> body = Json.read(out.value(), new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked") List<Map<String, Object>> res = body.get("photos") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
                for (int i = 0; i < Math.min(res.size(), saved.size()); i++) aiChecks(saved.get(i), res.get(i), checks.get(saved.get(i).getId()), a);
            }
        }
        for (FieldPhoto p : saved) {
            List<Check> c = checks.get(p.getId());
            if (!aiDone) c.add(new Check("AI photo checks", "WARN", "AI service not available: scene, shelf and face were not checked"));
            p.setChecksJson(Json.write(c));
            photos.save(p);
        }
        boolean fail = visitChecks.stream().anyMatch(x -> "FAIL".equals(x.status())) || checks.values().stream().flatMap(List::stream).anyMatch(x -> "FAIL".equals(x.status()));
        boolean negative = Boolean.FALSE.equals(answers.get("businessRunning")) || Boolean.FALSE.equals(answers.get("ownerMet"));
        String outcome = negative ? "NEGATIVE" : fail || Boolean.FALSE.equals(answers.get("matchesType")) ? "REFER" : "POSITIVE";
        v.setOutcome(outcome);
        v.setPhotoChecksJson(Json.write(visitChecks));
        visits.save(v);

        String note = "Field visit " + outcome.toLowerCase() + " (app): " + v.getRemarks();
        apps.recordFieldVisit(appId, note.length() > 900 ? note.substring(0, 900) : note, by);
        audit.record(by, "field.visit_synced", "APPLICATION", a.getAppNo(), appId, outcome + " · " + saved.size() + " photos · location " + v.getLocationCheck()
                + (v.isCapturedOffline() ? " · captured offline" : ""));
        if (reused) {
            LoanApplication fresh = apps.get(appId);
            if (List.of("NOT_CHECKED", "CLEAR").contains(fresh.getFraudState()))
                apps.transition(fresh, Domain.FRAUD, "REVIEW", "fraud.photo_reuse", by, "Field photo already used on another application");
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("visitId", v.getId());
        res.put("duplicate", false);
        res.put("outcome", outcome);
        res.put("locationCheck", v.getLocationCheck());
        res.put("distanceM", v.getDistanceM());
        res.put("photoReuse", reused);
        res.put("aiChecked", aiDone);
        return res;
    }

    /** After the visit: continue the automatic steps; a decision made before the visit is re-run with the visit's readings. */
    public void afterVisit(Long appId) {
        process.continueAfterPersonStep(appId);
        LoanApplication a = apps.get(appId);
        boolean decidedBefore = List.of("APPROVE", "APPROVE_WITH_CONDITIONS", "REFER").contains(a.getDecisionState());
        boolean notSanctioned = "NOT_STARTED".equals(a.getSanctionState()) || a.getSanctionState().startsWith("PENDING_");
        if (decidedBefore && notSanctioned && ApplicationService.isOpen(a)) {
            try { decisions.run(appId, CurrentUser.SYSTEM); } catch (ApiException e) { /* left for the credit officer */ }
        }
    }

    @SuppressWarnings("unchecked")
    void aiChecks(FieldPhoto p, Map<String, Object> r, List<Check> c, LoanApplication a) {
        Map<String, Object> ex = (Map<String, Object>) r.getOrDefault("exif", Map.of());
        if (ex.get("takenAt") != null && p.getTakenAt() != null) {
            try {
                Instant exifAt = LocalDateTime.parse(String.valueOf(ex.get("takenAt"))).atZone(IST).toInstant();
                long mins = Math.abs(Duration.between(exifAt, p.getTakenAt()).toMinutes());
                c.add(new Check("Camera time (EXIF)", mins <= 120 ? "OK" : "FAIL", mins <= 120 ? "Matches the app's capture time" : "Camera says " + ex.get("takenAt") + ": " + mins + " minutes off"));
            } catch (Exception ignored) { }
        }
        if (ex.get("lat") instanceof Number la && ex.get("lng") instanceof Number ln && p.getLat() != null) {
            double d = PhotoHash.metres(la.doubleValue(), ln.doubleValue(), p.getLat(), p.getLng());
            c.add(new Check("Camera location (EXIF)", d <= 300 ? "OK" : "FAIL", Math.round(d) + " m from the app's position"));
        }
        if (r.get("scene") instanceof Map<?, ?> sc && sc.get("scene") != null) {
            String seen = String.valueOf(sc.get("scene"));
            double conf = sc.get("confidence") instanceof Number n ? n.doubleValue() : 0;
            String group = FieldChecklists.group(a.getIndustry(), a.getSegment());
            String expected = FieldChecklists.EXPECTED_SCENE.get(p.getKind());
            if (("front".equals(p.getKind()) || "inside".equals(p.getKind())) && "KIRANA".equals(group)) expected = "SHOP";
            if (expected == null) c.add(new Check("Scene", "OK", "Looks like " + seen.toLowerCase() + " (" + Math.round(conf * 100) + "%)"));
            else if (seen.equals(expected)) c.add(new Check("Scene", "OK", "Looks like a " + seen.toLowerCase() + " as expected"));
            else c.add(new Check("Scene", conf >= 0.3 ? "WARN" : "OK", "Expected " + expected.toLowerCase() + ", the photo looks like " + seen.toLowerCase() + " (" + Math.round(conf * 100) + "%)"));
        }
        if (r.get("shelf") instanceof Map<?, ?> sh)
            c.add(new Check("Shelf fill (estimate)", "LOW".equals(sh.get("level")) ? "WARN" : "OK", "Shelves look " + String.valueOf(sh.get("level")).toLowerCase() + " (" + sh.get("fill") + ")"));
        if (r.get("face") instanceof Map<?, ?> f && Boolean.TRUE.equals(f.get("checked"))) {
            boolean matched = Boolean.TRUE.equals(f.get("matched"));
            c.add(new Check("Owner face vs KYC", matched ? "OK" : "WARN", matched ? "Matches the KYC photo (" + f.get("score") + ")"
                    : f.get("note") != null ? String.valueOf(f.get("note")) : "Does not match the KYC photo (" + f.get("score") + ")"));
        }
        if (r.get("sharpness") instanceof Number s && s.doubleValue() < 40) c.add(new Check("Photo quality", "WARN", "Blurred"));
    }
}
