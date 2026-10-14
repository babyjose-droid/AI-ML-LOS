package com.rhythm.los.field;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Field officer app API: assigned visits, checklists, sync of a captured visit, and the visits on an application. */
@RestController
public class FieldController {
    static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FRAUD_ANALYST','COMPLIANCE','FIELD_OFFICER')";

    private final FieldVisitService service;

    public FieldController(FieldVisitService service) { this.service = service; }

    @GetMapping("/api/field/visits")
    @PreAuthorize(STAFF)
    public List<Map<String, Object>> assigned() { return service.assigned(CurrentUser.get()); }

    @GetMapping("/api/field/checklists")
    @PreAuthorize(STAFF)
    public Map<String, FieldChecklists.Checklist> checklists() { return FieldChecklists.all(); }

    @PostMapping(value = "/api/field/visits/{appId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(STAFF)
    public Map<String, Object> sync(@PathVariable Long appId, @RequestPart("visit") String visit,
                                    @RequestPart(value = "photos", required = false) List<MultipartFile> photos) {
        FieldVisitService.VisitIn in;
        try { in = Json.read(visit, FieldVisitService.VisitIn.class); }
        catch (Exception e) { throw ApiException.unprocessable("BAD_VISIT", "The visit data could not be read"); }
        Map<String, Object> r = service.submit(appId, in, photos, CurrentUser.get());
        if (!Boolean.TRUE.equals(r.get("duplicate"))) service.afterVisit(appId);
        return r;
    }

    @GetMapping("/api/applications/{id}/field-visits")
    @PreAuthorize(STAFF)
    public List<Map<String, Object>> visits(@PathVariable Long id) { return service.forApplication(id); }

    @GetMapping("/api/field/photos/{id}/content")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        FieldPhoto p = service.photo(id, CurrentUser.get());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.getContentType()))
                .header("Cache-Control", "private, max-age=300").body(service.content(p));
    }
}
