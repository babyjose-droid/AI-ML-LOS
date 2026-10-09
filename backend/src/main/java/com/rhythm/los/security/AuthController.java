package com.rhythm.los.security;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.org.AppUser;
import com.rhythm.los.org.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;

    public AuthController(AppUserRepository users, PasswordEncoder encoder, JwtService jwt, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        AppUser u = users.findByUsername(req.username().trim().toLowerCase())
                .filter(AppUser::isActive)
                .filter(x -> encoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Wrong username or password"));
        audit.record(u.getUsername(), u.getRole().name(), "auth.login", "USER", u.getUsername(), null, "Signed in");
        return Map.of("token", jwt.issue(u), "user", userView(u));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser cu = CurrentUser.get();
        AppUser u = users.findByUsername(cu.username()).orElseThrow(() -> ApiException.notFound("User"));
        return userView(u);
    }

    static Map<String, Object> userView(AppUser u) {
        return Map.of("username", u.getUsername(), "name", u.getFullName(), "role", u.getRole().name(),
                "branchId", u.getBranchId() == null ? 0 : u.getBranchId(), "sanctionLevel", u.getRole().sanctionLevel());
    }
}
