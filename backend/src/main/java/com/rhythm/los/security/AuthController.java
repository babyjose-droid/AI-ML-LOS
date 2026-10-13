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

    public record LoginRequest(@NotBlank String username, @NotBlank String password, String otp) {}

    static final int MAX_FAILED = 5;
    static final long LOCK_MINUTES = 15;

    @org.springframework.beans.factory.annotation.Value("${rhythm.security.idle-timeout-minutes:15}")
    long idleMinutes = 15;

    /**
     * Sign-in (FSD 23): five wrong passwords or codes in a row lock the account for 15 minutes; users who turned on MFA
     * also give the 6-digit code from their authenticator app.
     */
    @PostMapping("/login")
    @org.springframework.transaction.annotation.Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        ApiException bad = new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Wrong username or password");
        AppUser u = users.findByUsername(req.username().trim().toLowerCase()).filter(AppUser::isActive).orElseThrow(() -> bad);
        if (u.isLocked()) throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "Too many failed sign-ins. Try again after " + LOCK_MINUTES + " minutes or ask the admin to unlock");
        if (!encoder.matches(req.password(), u.getPasswordHash())) { failed(u, "wrong password"); throw bad; }
        if (u.isMfaEnabled()) {
            if (req.otp() == null || req.otp().isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_REQUIRED", "Enter the 6-digit code from your authenticator app");
            if (!Totp.verify(u.getMfaSecret(), req.otp().trim(), java.time.Instant.now().getEpochSecond())) {
                failed(u, "wrong MFA code");
                throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_OTP", "The code is not correct");
            }
        }
        u.setFailedLogins(0);
        u.setLockedUntil(null);
        u.setLastLoginAt(java.time.Instant.now());
        users.save(u);
        audit.record(u.getUsername(), u.getRole().name(), "auth.login", "USER", u.getUsername(), null, u.isMfaEnabled() ? "Signed in with MFA" : "Signed in");
        return Map.of("token", jwt.issue(u), "user", userView(u), "idleTimeoutMinutes", idleMinutes);
    }

    private void failed(AppUser u, String why) {
        u.setFailedLogins(u.getFailedLogins() + 1);
        if (u.getFailedLogins() >= MAX_FAILED) {
            u.setLockedUntil(java.time.Instant.now().plus(LOCK_MINUTES, java.time.temporal.ChronoUnit.MINUTES));
            u.setFailedLogins(0);
            audit.record(u.getUsername(), u.getRole().name(), "auth.locked", "USER", u.getUsername(), null, MAX_FAILED + " failed sign-ins (" + why + ")");
        } else audit.record(u.getUsername(), u.getRole().name(), "auth.failed", "USER", u.getUsername(), null, why);
        users.save(u);
    }

    /** Starts MFA set-up: a new secret to add to an authenticator app. MFA is on only after a code is confirmed. */
    @PostMapping("/mfa/setup")
    public Map<String, Object> mfaSetup() {
        AppUser u = users.findByUsername(CurrentUser.get().username()).orElseThrow(() -> ApiException.notFound("User"));
        if (u.isMfaEnabled()) throw ApiException.conflict("MFA_ON", "MFA is already on; ask the admin to reset it to set up a new device");
        String secret = Totp.newSecret();
        u.setMfaSecret(secret);
        users.save(u);
        return Map.of("secret", secret, "otpauthUri", Totp.uri("Rhythm LOS", u.getUsername(), secret));
    }

    public record CodeRequest(String code) {}

    @PostMapping("/mfa/enable")
    public Map<String, Object> mfaEnable(@RequestBody CodeRequest r) {
        AppUser u = users.findByUsername(CurrentUser.get().username()).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getMfaSecret() == null) throw ApiException.conflict("NO_SETUP", "Start MFA set-up first");
        if (!Totp.verify(u.getMfaSecret(), r.code() == null ? null : r.code().trim(), java.time.Instant.now().getEpochSecond()))
            throw ApiException.unprocessable("BAD_OTP", "The code is not correct; check the time on your phone");
        u.setMfaEnabled(true);
        users.save(u);
        audit.record(CurrentUser.get(), "auth.mfa_enabled", "USER", u.getUsername(), null, "Authenticator app");
        return userView(u);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser cu = CurrentUser.get();
        AppUser u = users.findByUsername(cu.username()).orElseThrow(() -> ApiException.notFound("User"));
        return userView(u);
    }

    static Map<String, Object> userView(AppUser u) {
        return Map.of("username", u.getUsername(), "name", u.getFullName(), "role", u.getRole().name(),
                "branchId", u.getBranchId() == null ? 0 : u.getBranchId(), "sanctionLevel", u.getRole().sanctionLevel(), "mfaEnabled", u.isMfaEnabled());
    }
}
