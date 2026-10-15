package com.rhythm.los.vkyc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.document.AppDocument;
import com.rhythm.los.document.DocumentReader;
import com.rhythm.los.document.DocumentService;
import com.rhythm.los.document.StorageService;
import com.rhythm.los.integration.IntegrationGateway;
import com.rhythm.los.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Video KYC (V-CIP) sessions.
 *
 * An operations official creates a session and sends the customer a one-time link (in this build the link is shown
 * to copy; SMS/WhatsApp delivery comes with the integrations phase). The customer consents and shares location,
 * then the official runs a live video call (WebRTC, signalled through this service). During the call the official
 * captures frames for each challenge (face, head turns, reading a random code, showing the PAN card) and the call is
 * recorded. The AI service checks liveness, face match with the ID photo and the PAN on camera. The official decides.
 */
@Service
public class VkycService {
    public static final Set<String> OPEN = Set.of("WAITING", "JOINED", "IN_CALL");
    static final Set<String> STEPS = Set.of("front", "left", "right", "code", "pan");
    private static final SecureRandom RNG = new SecureRandom();

    private final VkycSessionRepository sessions;
    private final VkycSignalRepository signals;
    private final ApplicationService apps;
    private final DocumentService documents;
    private final StorageService storage;
    private final DocumentReader reader;
    private final IntegrationGateway gateway;
    private final AuditService audit;
    private final Duration linkValidity;
    private final String iceServers;

    public VkycService(VkycSessionRepository sessions, VkycSignalRepository signals, ApplicationService apps, DocumentService documents,
                       StorageService storage, DocumentReader reader, IntegrationGateway gateway, AuditService audit,
                       @Value("${rhythm.vkyc.link-hours:24}") long linkHours,
                       @Value("${rhythm.vkyc.ice-servers:[]}") String iceServers) {
        this.sessions = sessions;
        this.signals = signals;
        this.apps = apps;
        this.documents = documents;
        this.storage = storage;
        this.reader = reader;
        this.gateway = gateway;
        this.audit = audit;
        this.linkValidity = Duration.ofHours(linkHours);
        this.iceServers = iceServers;
    }

    public record Created(VkycSession session, String link) {}

    private com.rhythm.los.party.ApplicationPartyRepository parties;
    private com.rhythm.los.party.PartyDocumentRepository partyDocs;
    private com.rhythm.los.product.ProductService products;

    @org.springframework.beans.factory.annotation.Autowired
    void setParties(com.rhythm.los.party.ApplicationPartyRepository p, com.rhythm.los.party.PartyDocumentRepository d, com.rhythm.los.product.ProductService pr) {
        this.parties = p;
        this.partyDocs = d;
        this.products = pr;
    }

    @Value("${rhythm.vkyc.ip-country-file:}") String ipCountryFile = "";
    @Value("${rhythm.vkyc.slot-capacity:2}") int slotCapacity = 2;
    private volatile IpCountry ipTable;

    /** Sample ranges plus the lender's geo-IP file (CIDR,country per line). */
    IpCountry ipTable() {
        if (ipTable == null) {
            List<String> lines = new ArrayList<>(IpCountry.SAMPLES);
            if (ipCountryFile != null && !ipCountryFile.isBlank()) {
                try { lines.addAll(java.nio.file.Files.readAllLines(java.nio.file.Path.of(ipCountryFile))); }
                catch (Exception e) { org.slf4j.LoggerFactory.getLogger(VkycService.class).warn("Geo-IP file {} not read: {}", ipCountryFile, e.getMessage()); }
            }
            ipTable = new IpCountry(lines);
        }
        return ipTable;
    }

    /** The product's video KYC settings for this application (its pinned version, else the live product, else defaults). */
    public VkycPolicy policy(LoanApplication a) {
        String json = apps.product(a).getVkycPolicy();
        if (json == null && products != null) json = products.live(a.getProductCode()).getVkycPolicy();
        return VkycPolicy.parse(json);
    }

    /** The settings a session runs with: those saved when its link was made. */
    VkycPolicy policy(VkycSession s) {
        return s.getPolicyJson() != null ? VkycPolicy.parse(s.getPolicyJson()) : policy(apps.get(s.getApplicationId()));
    }

    /** The product decides which officials may run its video KYC; an admin always can. */
    void requireOfficial(VkycPolicy p, CurrentUser by) {
        if (by.role() == com.rhythm.los.org.Role.ADMIN) return;
        if (!p.officerRoles().contains(by.role().name()))
            throw ApiException.forbidden("NOT_VKYC_OFFICIAL", "For this product video KYC is done by " + String.join(", ", p.officerRoles().stream().map(r -> r.toLowerCase().replace('_', ' ')).toList()));
    }

    public Created create(Long appId, CurrentUser by) { return create(appId, null, by); }

    // ---------------------------------------------------------------- official

    /** A link for the applicant (partyId null) or for one co-applicant or guarantor. */
    @Transactional
    public Created create(Long appId, Long partyId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!ApplicationService.isOpen(a)) throw ApiException.conflict("WRONG_STAGE", "Application is closed");
        VkycPolicy pol = policy(a);
        requireOfficial(pol, by);
        String person = a.getApplicantName();
        if (partyId == null) {
            if ("FAILED".equals(a.getKycState())) throw ApiException.conflict("KYC_FAILED", "KYC has already failed for this application");
        } else {
            var p = parties.findById(partyId).filter(x -> x.getApplicationId().equals(appId))
                    .orElseThrow(() -> ApiException.notFound("Co-applicant or guarantor"));
            if (!p.active()) throw ApiException.conflict("NOT_ACTIVE", p.getName() + " is no longer on this application");
            if (p.getConsentAt() == null) throw ApiException.conflict("CONSENT_MISSING", p.getName() + " must give consent before video KYC");
            if ("FAILED".equals(p.getKycState())) throw ApiException.conflict("KYC_FAILED", p.getName() + "'s KYC has already failed");
            if ("NONE".equals(pol.parties())) throw ApiException.unprocessable("NOT_FOR_PARTIES", "This product does not use video KYC for co-applicants and guarantors");
            person = p.getName();
        }
        List<VkycSession> mine = sessions.findByApplicationIdOrderByIdDesc(appId).stream().filter(x -> Objects.equals(x.getPartyId(), partyId)).toList();
        if (mine.stream().anyMatch(x -> "APPROVED".equals(x.getStatus()))) throw ApiException.conflict("ALREADY_APPROVED", "Video KYC for " + person + " is already approved");
        long held = mine.stream().filter(x -> x.getCallStartedAt() != null).count();
        if (held >= pol.maxAttempts())
            throw ApiException.conflict("ATTEMPTS_USED", person + " has had " + held + " video KYC calls, the product's limit; use the branch KYC route or reject");
        for (VkycSession s : mine) {
            if (OPEN.contains(s.getStatus())) { s.setStatus("CANCELLED"); sessions.save(s); }
        }
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        VkycSession s = new VkycSession();
        s.setApplicationId(appId);
        s.setTokenHash(sha256(token.getBytes(StandardCharsets.UTF_8)));
        s.setStatus("WAITING");
        s.setCreatedBy(by.username());
        s.setExpiresAt(Instant.now().plus(Duration.ofHours(pol.linkHours())));
        s.setPartyId(partyId);
        s.setPersonName(person);
        s.setPolicyJson(Json.write(pol));
        sessions.save(s);
        audit.record(by, "vkyc.created", "VKYC", String.valueOf(s.getId()), appId, "Video KYC link for " + person + ", valid until " + s.getExpiresAt());
        return new Created(s, "/vkyc/" + token + (com.rhythm.los.tenant.TenantContext.isDefault() ? "" : "?t=" + com.rhythm.los.tenant.TenantContext.code()));
    }

    public List<VkycSession> list(Long appId) {
        List<VkycSession> out = sessions.findByApplicationIdOrderByIdDesc(appId);
        out.forEach(this::expire);
        return out;
    }

    public VkycSession get(Long id) {
        VkycSession s = sessions.findById(id).orElseThrow(() -> ApiException.notFound("Video KYC session"));
        expire(s);
        return s;
    }

    @Transactional
    public VkycSession start(Long id, CurrentUser by) {
        VkycSession s = get(id);
        requireOfficial(policy(s), by);
        if (!Set.of("JOINED", "IN_CALL").contains(s.getStatus()))
            throw ApiException.conflict("NOT_READY", s.getStatus().equals("WAITING") ? "The customer has not joined yet" : "Session is " + s.getStatus());
        if (s.getOfficer() != null && !s.getOfficer().equals(by.username()))
            throw ApiException.conflict("OTHER_OFFICER", "This call is being handled by " + s.getOfficer());
        if (!"IN_CALL".equals(s.getStatus())) {
            s.setStatus("IN_CALL");
            s.setOfficer(by.username());
            s.setCallStartedAt(Instant.now());
            s.setChallengeCode(String.format("%04d", RNG.nextInt(10000)));
            sessions.save(s);
            audit.record(by, "vkyc.call_started", "VKYC", String.valueOf(id), s.getApplicationId(), "Officer " + by.username() + " started the call");
        }
        return s;
    }

    @Transactional
    public Map<String, Object> addFrames(Long id, List<MultipartFile> files, CurrentUser by) {
        VkycSession s = requireCall(id, by);
        List<Map<String, String>> frames = frames(s);
        if (frames.size() + files.size() > 60) throw ApiException.unprocessable("TOO_MANY_FRAMES", "At most 60 frames per session");
        for (MultipartFile f : files) {
            String name = Optional.ofNullable(f.getOriginalFilename()).orElse("front.jpg");
            String step = name.split("[-.]")[0].toLowerCase();
            if (!STEPS.contains(step)) throw ApiException.unprocessable("BAD_STEP", "Unknown step " + step);
            String ct = f.getContentType() == null ? "" : f.getContentType();
            if (!Set.of("image/jpeg", "image/png").contains(ct)) throw ApiException.unprocessable("BAD_FILE_TYPE", "Frames must be JPEG or PNG");
            byte[] b = bytes(f);
            frames.add(Map.of("step", step, "path", storage.store(s.getApplicationId(), b, ct.equals("image/png") ? ".png" : ".jpg"),
                    "sha256", sha256(b), "contentType", ct));
        }
        s.setFramesJson(Json.write(frames));
        sessions.save(s);
        return Map.of("frames", frames.size());
    }

    @Transactional
    public VkycSession addRecording(Long id, MultipartFile file, CurrentUser by) {
        VkycSession s = get(id);
        if (!by.username().equals(s.getOfficer())) throw ApiException.forbidden("NOT_YOUR_CALL", "Only the officer on the call can upload its recording");
        if (!Set.of("IN_CALL", "COMPLETED").contains(s.getStatus())) throw ApiException.conflict("WRONG_STATE", "Session is " + s.getStatus());
        byte[] b = bytes(file);
        if (b.length == 0) throw ApiException.unprocessable("EMPTY_FILE", "The recording is empty");
        if (s.getRecordingPath() != null) storage.delete(s.getRecordingPath());
        s.setRecordingPath(storage.store(s.getApplicationId(), b, ".webm"));
        s.setRecordingSha256(sha256(b));
        s.setRecordingBytes((long) b.length);
        sessions.save(s);
        audit.record(by, "vkyc.recording_saved", "VKYC", String.valueOf(id), s.getApplicationId(),
                "Recording " + b.length / 1024 + " KB, sha256 " + s.getRecordingSha256().substring(0, 12));
        return s;
    }

    /** Ends the call and runs the AI checks on the captured frames. */
    @Transactional
    public VkycSession finish(Long id, CurrentUser by) {
        VkycSession s = requireCall(id, by);
        s.setCallEndedAt(Instant.now());
        s.setStatus("COMPLETED");
        signals.save(new VkycSignal(id, "officer", "hangup", "{}"));
        LoanApplication a = apps.get(s.getApplicationId());
        List<Map<String, String>> frames = frames(s);
        if (frames.isEmpty()) {
            s.setAiStatus("REVIEW");
            s.setAnalysisJson(Json.write(Map.of("status", "REVIEW", "reasons", List.of("No frames were captured during the call"))));
        } else if (!reader.analysesVideoKyc()) {
            s.setAiStatus("REVIEW");
            s.setAnalysisJson(Json.write(Map.of("status", "REVIEW", "reasons", List.of("AI service not configured: the officer must check face, liveness and PAN by eye"))));
        } else {
            List<DocumentReader.Part> fparts = new ArrayList<>();
            int i = 0;
            for (Map<String, String> f : frames)
                fparts.add(new DocumentReader.Part(f.get("step") + "-" + (++i) + ".jpg", storage.read(f.get("path")), f.get("contentType")));
            List<DocumentReader.Part> ids = new ArrayList<>();
            String expectedPan = a.getPan();
            if (s.getPartyId() == null) {
                for (AppDocument d : documents.list(a.getId())) {
                    boolean idDoc = Set.of("PAN", "AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PASSPORT", "PHOTO").contains(d.getDocType());
                    if (idDoc && !"REJECTED".equals(d.getStatus()) && d.getContentType().startsWith("image/"))
                        ids.add(new DocumentReader.Part(d.getDocType() + ".img", documents.content(d), d.getContentType()));
                }
            } else {
                // a co-applicant or guarantor is matched against their own documents and PAN, never the applicant's
                var p = parties.findById(s.getPartyId()).orElseThrow();
                expectedPan = p.getPan();
                for (var d : partyDocs.findByPartyIdOrderByIdAsc(p.getId()))
                    if (!"REJECTED".equals(d.getStatus()) && d.getContentType() != null && d.getContentType().startsWith("image/") && !"-".equals(d.getStoragePath()))
                        ids.add(new DocumentReader.Part(d.getDocType() + ".img", storage.read(d.getStoragePath()), d.getContentType()));
            }
            final String pan = expectedPan;
            var out = gateway.call(a.getId(), reader.name(), "vkyc.analyze", () -> reader.analyzeVideoKyc(fparts, ids, pan),
                    r -> "Video KYC " + Json.read(r, Map.class).get("status"), null);
            if (out.ok()) {
                // the product's own thresholds decide PASS or REVIEW; the AI's raw verdict is kept alongside
                @SuppressWarnings("unchecked") Map<String, Object> judged = policy(s).judge(Json.read(out.value(), Map.class));
                s.setAnalysisJson(Json.write(judged));
                s.setAiStatus("PASS".equals(judged.get("status")) ? "PASS" : "REVIEW");
            } else {
                s.setAiStatus("ERROR");
                s.setAnalysisJson(Json.write(Map.of("status", "ERROR", "reasons", List.of("AI check failed: " + out.error()))));
            }
        }
        sessions.save(s);
        audit.record(by, "vkyc.call_ended", "VKYC", String.valueOf(id), a.getId(), frames.size() + " frames · AI " + s.getAiStatus());
        return s;
    }

    /** answers: for each of the product's questions, in order, whether the person answered it satisfactorily. */
    public record DecisionRequest(boolean approve, boolean codeHeard, String note, List<Boolean> answers) {
        public DecisionRequest(boolean approve, boolean codeHeard, String note) { this(approve, codeHeard, note, null); }
    }

    /** The official's decision. RBI V-CIP requires a human decision; the AI result is advice. */
    @Transactional
    public VkycSession decide(Long id, DecisionRequest req, CurrentUser by) {
        VkycSession s = get(id);
        if (!"COMPLETED".equals(s.getStatus())) throw ApiException.conflict("WRONG_STATE", "End the call before deciding (session is " + s.getStatus() + ")");
        if (!by.username().equals(s.getOfficer())) throw ApiException.forbidden("NOT_YOUR_CALL", "Only the official who conducted the call can decide");
        requireOfficial(policy(s), by);
        String note = req.note() == null ? "" : req.note().trim();
        if (note.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        boolean override = false;
        if (req.approve()) {
            if (!Boolean.TRUE.equals(s.getInIndia()))
                throw ApiException.unprocessable("OUTSIDE_INDIA", "The customer's location was not in India; V-CIP cannot be approved");
            if (s.getIpCountry() != null && !Set.of("IN", "PRIVATE", "UNKNOWN").contains(s.getIpCountry()))
                throw ApiException.unprocessable("OUTSIDE_INDIA", "The customer joined from an IP address in " + s.getIpCountry() + " although GPS showed India (VPN or a location spoof); V-CIP cannot be approved");
            if (!req.codeHeard())
                throw ApiException.unprocessable("CODE_NOT_CONFIRMED", "Confirm that the customer read out the random code " + s.getChallengeCode());
            if (s.getRecordingPath() == null)
                throw ApiException.unprocessable("NO_RECORDING", "The call recording has not been saved");
            List<String> qs = policy(s).questions();
            List<Boolean> ans = req.answers() == null ? List.of() : req.answers();
            for (int i = 0; i < qs.size(); i++)
                if (i >= ans.size() || !Boolean.TRUE.equals(ans.get(i)))
                    throw ApiException.unprocessable("QUESTIONS_NOT_CONFIRMED", "Ask and confirm every question the product sets: \"" + qs.get(i) + "\"");
            if (!"PASS".equals(s.getAiStatus())) {
                if (note.length() < 30)
                    throw ApiException.unprocessable("OVERRIDE_REASON", "The AI checks did not all pass. Explain in at least 30 characters why you are approving");
                override = true;
            }
        }
        s.setDecision(req.approve() ? "APPROVED" : "REJECTED");
        s.setStatus(s.getDecision());
        s.setCodeHeard(req.codeHeard());
        s.setDecisionNote(note.length() > 1000 ? note.substring(0, 1000) : note);
        s.setAiOverride(override);
        s.setDecidedBy(by.username());
        s.setDecidedAt(Instant.now());
        List<String> qs = policy(s).questions();
        List<Map<String, Object>> asked = new ArrayList<>();
        for (int i = 0; i < qs.size(); i++)
            asked.add(Map.of("question", qs.get(i), "answered", req.answers() != null && i < req.answers().size() && Boolean.TRUE.equals(req.answers().get(i))));
        s.setQuestionsJson(Json.write(asked));
        sessions.save(s);
        LoanApplication a = apps.get(s.getApplicationId());
        if (s.getPartyId() != null) {
            // a co-applicant's or guarantor's call settles their own KYC review, never the applicant's
            var p = parties.findById(s.getPartyId()).orElseThrow();
            if (req.approve() && "REVIEW".equals(p.getKycState())) { p.setKycState("VERIFIED"); p.setStatus("VERIFIED"); p.setKycReviewedBy(by.username()); p.setKycNote("Cleared by video KYC: " + trim(note)); }
            if (!req.approve() && !"FAILED".equals(p.getKycState())) { p.setKycState("FAILED"); p.setStatus("FAILED"); p.setKycReviewedBy(by.username()); p.setKycNote("Video KYC rejected: " + trim(note)); }
            p.setUpdatedAt(Instant.now());
            parties.save(p);
            audit.record(by, req.approve() ? (override ? "vkyc.approved_override" : "vkyc.approved") : "vkyc.rejected", "VKYC", String.valueOf(id), a.getId(),
                    s.getPersonName() + " (" + p.getPartyNo() + "): " + note + (override ? " · approved although AI checks did not pass" : ""));
            return s;
        }
        String kyc = a.getKycState();
        if (req.approve() && "REVIEW".equals(kyc)) apps.transition(a, Domain.KYC, "VERIFIED", "kyc.vkyc_approved", by, "Video KYC approved: " + note);
        if (!req.approve() && Set.of("NOT_STARTED", "REVIEW", "VERIFIED").contains(kyc)) apps.transition(a, Domain.KYC, "FAILED", "kyc.vkyc_rejected", by, "Video KYC rejected: " + note);
        audit.record(by, req.approve() ? (override ? "vkyc.approved_override" : "vkyc.approved") : "vkyc.rejected", "VKYC", String.valueOf(id), a.getId(),
                note + (override ? " · approved although AI checks did not pass" : ""));
        return s;
    }

    /** The questions the official must ask on this call. */
    public List<String> questions(VkycSession s) { return policy(s).questions(); }

    public List<Map<String, String>> frameList(VkycSession s) {
        List<Map<String, String>> out = new ArrayList<>();
        List<Map<String, String>> f = frames(s);
        for (int i = 0; i < f.size(); i++) out.add(Map.of("index", String.valueOf(i), "step", f.get(i).get("step")));
        return out;
    }

    public byte[] frame(VkycSession s, int index) {
        List<Map<String, String>> f = frames(s);
        if (index < 0 || index >= f.size()) throw ApiException.notFound("Frame");
        return storage.read(f.get(index).get("path"));
    }

    public String frameType(VkycSession s, int index) { return frames(s).get(index).get("contentType"); }

    public byte[] recording(VkycSession s) {
        if (s.getRecordingPath() == null) throw ApiException.notFound("Recording");
        return storage.read(s.getRecordingPath());
    }

    // ---------------------------------------------------------------- customer (by link token)

    public VkycSession byToken(String token) {
        VkycSession s = sessions.findByTokenHash(sha256(token.getBytes(StandardCharsets.UTF_8)))
                .orElseThrow(() -> ApiException.notFound("This video KYC link"));
        expire(s);
        return s;
    }

    public Map<String, Object> customerView(String token) {
        VkycSession s = byToken(token);
        LoanApplication a = apps.get(s.getApplicationId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s.getStatus());
        m.put("firstName", (s.getPersonName() == null ? a.getApplicantName() : s.getPersonName()).split(" ")[0]);
        VkycPolicy pol = policy(s);
        m.put("callHours", pol.allTheTime() ? null : pol.callFrom() + "–" + pol.callTo() + " IST");
        m.put("languages", pol.languages());
        m.put("lender", "Rhythm demo NBFC");
        m.put("appNo", a.getAppNo());
        m.put("expiresAt", s.getExpiresAt());
        m.put("iceServers", iceServers());
        m.put("slotAt", s.getSlotAt());
        return m;
    }

    // ---------------------------------------------------------------- slots and the officials' queue

    Map<Instant, Long> booked(Instant from, Instant to) {
        return sessions.findBySlotAtBetween(from, to).stream().filter(x -> OPEN.contains(x.getStatus()))
                .collect(java.util.stream.Collectors.groupingBy(VkycSession::getSlotAt, java.util.stream.Collectors.counting()));
    }

    public List<VkycSlots.Slot> slots(VkycSession s) {
        Instant now = Instant.now();
        return VkycSlots.open(policy(s), java.time.ZonedDateTime.now(), Math.max(1, slotCapacity), booked(now, now.plus(Duration.ofDays(VkycSlots.DAYS + 1))));
    }

    public List<VkycSlots.Slot> slotsByToken(String token) {
        VkycSession s = byToken(token);
        if (!Set.of("WAITING", "JOINED").contains(s.getStatus())) throw ApiException.conflict("LINK_USED", linkMessage(s));
        return slots(s);
    }

    public record BookRequest(String slotAt) {}

    /** Books (or moves) the call to a slot; the link is kept open until two hours after it. */
    @Transactional
    public VkycSession book(VkycSession s, BookRequest r, String by) {
        if (!Set.of("WAITING", "JOINED").contains(s.getStatus())) throw ApiException.conflict("LINK_USED", linkMessage(s));
        Instant at;
        try { at = Instant.parse(r.slotAt()); } catch (Exception e) { throw ApiException.unprocessable("BAD_SLOT", "Pick one of the times offered"); }
        VkycPolicy pol = policy(s);
        if (!VkycSlots.valid(pol, at, java.time.ZonedDateTime.now())) throw ApiException.unprocessable("BAD_SLOT", "Pick one of the times offered");
        if (!at.equals(s.getSlotAt()) && booked(at, at).getOrDefault(at, 0L) >= Math.max(1, slotCapacity))
            throw ApiException.conflict("SLOT_FULL", "That time has just been taken; pick another");
        s.setSlotAt(at);
        s.setBookedBy(by);
        s.setBookedAt(Instant.now());
        if (s.getExpiresAt().isBefore(at.plus(Duration.ofHours(2)))) s.setExpiresAt(at.plus(Duration.ofHours(2)));
        sessions.save(s);
        audit.record(by.equals("customer") ? "customer" : by, by.equals("customer") ? "CUSTOMER" : "STAFF", "vkyc.slot_booked", "VKYC", String.valueOf(s.getId()), s.getApplicationId(),
                "Call booked for " + VkycSlots.label(at.atZone(VkycPolicy.IST), java.time.ZonedDateTime.now(VkycPolicy.IST)) + " by " + by);
        return s;
    }

    @Transactional
    public VkycSession bookByStaff(Long id, BookRequest r, CurrentUser by) {
        VkycSession s = get(id);
        requireOfficial(policy(s), by);
        return book(s, r, by.username());
    }

    /** Customers waiting on the call now (longest first), then booked calls in time order. */
    public Map<String, Object> queue(CurrentUser by) {
        List<Map<String, Object>> waiting = new ArrayList<>(), booked = new ArrayList<>(), inCall = new ArrayList<>();
        Instant now = Instant.now();
        for (VkycSession s : sessions.findByStatusInOrderByIdAsc(List.of("WAITING", "JOINED", "IN_CALL"))) {
            expire(s);
            if (!OPEN.contains(s.getStatus())) continue;
            VkycPolicy pol = policy(s);
            boolean mine = by.role() == com.rhythm.los.org.Role.ADMIN || pol.officerRoles().contains(by.role().name());
            if (!mine) continue;
            LoanApplication a = apps.get(s.getApplicationId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("applicationId", a.getId());
            m.put("appNo", a.getAppNo());
            m.put("person", s.getPersonName() == null ? a.getApplicantName() : s.getPersonName());
            m.put("party", s.getPartyId() != null);
            m.put("product", a.getProductCode());
            m.put("status", s.getStatus());
            m.put("slotAt", s.getSlotAt());
            m.put("joinedAt", s.getJoinedAt());
            m.put("officer", s.getOfficer());
            m.put("inIndia", s.getInIndia());
            m.put("ipCountry", s.getIpCountry());
            m.put("languages", pol.languages());
            if ("IN_CALL".equals(s.getStatus())) inCall.add(m);
            else if ("JOINED".equals(s.getStatus())) {
                m.put("waitingMinutes", s.getJoinedAt() == null ? 0 : Duration.between(s.getJoinedAt(), now).toMinutes());
                m.put("late", s.getSlotAt() != null && now.isAfter(s.getSlotAt().plus(Duration.ofMinutes(VkycSlots.MINUTES))));
                waiting.add(m);
            } else if (s.getSlotAt() != null) booked.add(m);
        }
        waiting.sort(Comparator.comparing((Map<String, Object> m) -> (Instant) m.get("joinedAt"), Comparator.nullsLast(Comparator.naturalOrder())));
        booked.sort(Comparator.comparing((Map<String, Object> m) -> (Instant) m.get("slotAt")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waiting", waiting);
        out.put("inCall", inCall);
        out.put("booked", booked);
        out.put("slotCapacity", slotCapacity);
        return out;
    }

    public List<?> iceServers() {
        try { return Json.read(iceServers, List.class); } catch (Exception e) { return List.of(); }
    }

    public record JoinRequest(boolean consent, Double lat, Double lng, Double accuracy) {}

    @Transactional
    public Map<String, Object> join(String token, JoinRequest req, String ip, String agent) {
        VkycSession s = byToken(token);
        if (!Set.of("WAITING", "JOINED").contains(s.getStatus())) throw ApiException.conflict("LINK_USED", linkMessage(s));
        if (!req.consent()) throw ApiException.unprocessable("CONSENT_REQUIRED", "Please give your consent to continue");
        VkycPolicy pol = policy(s);
        if (!pol.withinHours(java.time.ZonedDateTime.now()))
            throw ApiException.conflict("OUTSIDE_HOURS", "Video KYC calls are taken between " + pol.callFrom() + " and " + pol.callTo() + " (India time). Please open the link again then.");
        if (req.lat() == null || req.lng() == null) throw ApiException.unprocessable("LOCATION_REQUIRED", "Location is required for video KYC");
        s.setStatus("JOINED");
        s.setJoinedAt(Instant.now());
        s.setConsentAt(Instant.now());
        s.setCustomerLat(BigDecimal.valueOf(req.lat()).setScale(6, RoundingMode.HALF_UP));
        s.setCustomerLng(BigDecimal.valueOf(req.lng()).setScale(6, RoundingMode.HALF_UP));
        if (req.accuracy() != null) s.setCustomerAccuracyM(BigDecimal.valueOf(req.accuracy()).setScale(1, RoundingMode.HALF_UP));
        s.setInIndia(inIndia(req.lat(), req.lng()));
        s.setCustomerIp(ip == null ? null : ip.substring(0, Math.min(64, ip.length())));
        IpCountry.Result geo = ipTable().lookup(ip);
        s.setIpCountry(geo.country() == null ? "UNKNOWN" : geo.country());
        s.setCustomerAgent(agent == null ? null : agent.substring(0, Math.min(300, agent.length())));
        sessions.save(s);
        audit.record("customer", "CUSTOMER", "vkyc.customer_joined", "VKYC", String.valueOf(s.getId()), s.getApplicationId(),
                "Consent given · location " + s.getCustomerLat() + "," + s.getCustomerLng() + (s.getInIndia() ? " (India)" : " (OUTSIDE INDIA)")
                        + " · IP country " + s.getIpCountry() + (geo.outsideIndia() ? " (OUTSIDE INDIA)" : ""));
        return customerView(token);
    }

    /** Rough bounding box of India. Precise geofencing (and IP country) comes with the integrations phase. */
    static boolean inIndia(double lat, double lng) {
        return lat >= 6.4 && lat <= 37.6 && lng >= 68.0 && lng <= 97.5;
    }

    // ---------------------------------------------------------------- signalling

    public record SignalRequest(String kind, String payload) {}
    static final Set<String> KINDS = Set.of("offer", "answer", "ice", "instruction", "hangup", "ready");

    @Transactional
    public VkycSignal signal(VkycSession s, String sender, SignalRequest req) {
        if (!Set.of("JOINED", "IN_CALL").contains(s.getStatus())) throw ApiException.conflict("NOT_IN_CALL", linkMessage(s));
        if (req.kind() == null || !KINDS.contains(req.kind())) throw ApiException.unprocessable("BAD_SIGNAL", "Unknown signal");
        if (req.payload() == null || req.payload().length() > 20000) throw ApiException.unprocessable("BAD_SIGNAL", "Signal payload missing or too large");
        if ("customer".equals(sender) && "instruction".equals(req.kind())) throw ApiException.forbidden("NOT_ALLOWED", "Only the official sends instructions");
        return signals.save(new VkycSignal(s.getId(), sender, req.kind(), req.payload()));
    }

    public List<VkycSignal> signalsFor(VkycSession s, String reader, long after) {
        return signals.findTop200BySessionIdAndSenderNotAndIdGreaterThanOrderByIdAsc(s.getId(), reader, after);
    }

    // ---------------------------------------------------------------- helpers

    private VkycSession requireCall(Long id, CurrentUser by) {
        VkycSession s = get(id);
        if (!"IN_CALL".equals(s.getStatus())) throw ApiException.conflict("NOT_IN_CALL", "Session is " + s.getStatus());
        if (!by.username().equals(s.getOfficer())) throw ApiException.forbidden("NOT_YOUR_CALL", "This call is being handled by " + s.getOfficer());
        return s;
    }

    private void expire(VkycSession s) {
        if (Set.of("WAITING", "JOINED").contains(s.getStatus()) && Instant.now().isAfter(s.getExpiresAt())) {
            s.setStatus("EXPIRED");
            sessions.save(s);
        }
    }

    private static String trim(String n) { return n.length() > 250 ? n.substring(0, 247) + "..." : n; }

    static String linkMessage(VkycSession s) {
        return switch (s.getStatus()) {
            case "EXPIRED" -> "This link has expired. Please ask for a new one.";
            case "CANCELLED" -> "This link was replaced by a newer one.";
            case "COMPLETED", "APPROVED", "REJECTED" -> "This video KYC call has ended.";
            default -> "This link cannot be used now (" + s.getStatus() + ").";
        };
    }

    private List<Map<String, String>> frames(VkycSession s) {
        if (s.getFramesJson() == null) return new ArrayList<>();
        return new ArrayList<>(Json.read(s.getFramesJson(), new TypeReference<List<Map<String, String>>>() {}));
    }

    private static byte[] bytes(MultipartFile f) {
        try { return f.getBytes(); } catch (IOException e) { throw ApiException.unprocessable("UPLOAD_FAILED", "Could not read the upload"); }
    }

    static String sha256(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
