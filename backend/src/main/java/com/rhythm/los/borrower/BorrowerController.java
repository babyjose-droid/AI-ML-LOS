package com.rhythm.los.borrower;

import com.rhythm.los.application.ApplicationRequest;
import com.rhythm.los.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/borrower")
public class BorrowerController {
    private final BorrowerService borrower;

    public BorrowerController(BorrowerService borrower) { this.borrower = borrower; }

    public record OtpRequest(String mobile) {}
    public record VerifyRequest(String mobile, String otp) {}
    public record KfsAccept(String otp) {}

    @PostMapping("/otp")
    public Map<String, Object> otp(@RequestBody OtpRequest r) { return borrower.requestOtp(r.mobile()); }

    @PostMapping("/otp/verify")
    public Map<String, Object> verify(@RequestBody VerifyRequest r) { return borrower.verifyOtp(r.mobile(), r.otp()); }

    @GetMapping("/products")
    public List<Map<String, Object>> products() { return borrower.products(); }

    @GetMapping("/applications")
    @PreAuthorize("hasRole('BORROWER')")
    public List<Map<String, Object>> mine() { return borrower.mine(CurrentUser.get()); }

    @PostMapping("/applications")
    @PreAuthorize("hasRole('BORROWER')")
    public Map<String, Object> apply(@Valid @RequestBody ApplicationRequest r) { return borrower.apply(r, CurrentUser.get()); }

    @GetMapping("/applications/{id}")
    @PreAuthorize("hasRole('BORROWER')")
    public Map<String, Object> view(@PathVariable Long id) { return borrower.view(id, CurrentUser.get()); }

    @PostMapping(value = "/applications/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('BORROWER')")
    public Map<String, Object> upload(@PathVariable Long id, @RequestParam String type, @RequestParam MultipartFile file,
                                      @RequestParam(required = false) String password) {
        var d = borrower.upload(id, type, file, password, CurrentUser.get());
        return Map.of("docType", d.getDocType(), "fileName", d.getFileName(), "status", d.getStatus());
    }

    @PostMapping("/applications/{id}/kfs/accept")
    @PreAuthorize("hasRole('BORROWER')")
    public Map<String, Object> acceptKfs(@PathVariable Long id, @RequestBody KfsAccept r) { return borrower.acceptKfs(id, r.otp(), CurrentUser.get()); }
}
