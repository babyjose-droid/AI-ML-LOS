package com.rhythm.los.org;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
public class AdminController {
    private static final Set<String> LEVELS = Set.of("HEAD_OFFICE", "ZONE", "REGION", "BRANCH");

    private final BranchRepository branches;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AdminController(BranchRepository branches, AppUserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.branches = branches;
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    public record BranchRequest(@NotBlank @Pattern(regexp = "[A-Z0-9]{2,20}") String code,
                                @NotBlank String name, @NotBlank String level, Long parentId, String city) {}

    public record UserRequest(@NotBlank @Pattern(regexp = "[a-z0-9._]{3,60}") String username,
                              @NotBlank String fullName, @NotNull Role role, Long branchId,
                              @Email String email, @Pattern(regexp = "^$|[6-9][0-9]{9}") String mobile,
                              @Size(min = 8, max = 64) String password) {}

    @GetMapping("/api/branches")
    public List<Branch> listBranches() { return branches.findAll(); }

    @PostMapping("/api/admin/branches")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Branch createBranch(@Valid @RequestBody BranchRequest r) {
        if (!LEVELS.contains(r.level())) throw ApiException.unprocessable("BAD_LEVEL", "Level must be one of " + LEVELS);
        if (branches.existsByCode(r.code())) throw ApiException.conflict("DUPLICATE_CODE", "Branch code already exists");
        if (!"HEAD_OFFICE".equals(r.level()) && r.parentId() == null)
            throw ApiException.unprocessable("PARENT_REQUIRED", "Only the head office can have no parent");
        if (r.parentId() != null && !branches.existsById(r.parentId())) throw ApiException.notFound("Parent branch");
        Branch b = new Branch();
        b.setCode(r.code());
        b.setName(r.name());
        b.setLevel(r.level());
        b.setParentId(r.parentId());
        b.setCity(r.city());
        Branch saved = branches.save(b);
        audit.record(CurrentUser.get(), "branch.create", "BRANCH", r.code(), null, r.name() + " (" + r.level() + ")");
        return saved;
    }

    @GetMapping("/api/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AppUser> listUsers() { return users.findAll(); }

    @PostMapping("/api/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser createUser(@Valid @RequestBody UserRequest r) {
        if (users.existsByUsername(r.username())) throw ApiException.conflict("DUPLICATE_USERNAME", "Username already exists");
        if (r.password() == null) throw ApiException.unprocessable("PASSWORD_REQUIRED", "Password is required for a new user");
        if (r.branchId() != null && !branches.existsById(r.branchId())) throw ApiException.notFound("Branch");
        AppUser u = new AppUser();
        u.setUsername(r.username());
        u.setFullName(r.fullName());
        u.setRole(r.role());
        u.setBranchId(r.branchId());
        u.setEmail(r.email());
        u.setMobile(r.mobile() == null || r.mobile().isBlank() ? null : r.mobile());
        u.setPasswordHash(encoder.encode(r.password()));
        AppUser saved = users.save(u);
        audit.record(CurrentUser.get(), "user.create", "USER", r.username(), null, "Role " + r.role());
        return saved;
    }

    @PutMapping("/api/admin/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser updateUser(@PathVariable Long id, @Valid @RequestBody UserRequest r) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (r.branchId() != null && !branches.existsById(r.branchId())) throw ApiException.notFound("Branch");
        String before = u.getRole().name();
        u.setFullName(r.fullName());
        u.setRole(r.role());
        u.setBranchId(r.branchId());
        u.setEmail(r.email());
        u.setMobile(r.mobile() == null || r.mobile().isBlank() ? null : r.mobile());
        if (r.password() != null) u.setPasswordHash(encoder.encode(r.password()));
        audit.record(CurrentUser.get(), "user.update", "USER", u.getUsername(), null, "Role " + before + " -> " + r.role());
        return users.save(u);
    }

    @PatchMapping("/api/admin/users/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser setActive(@PathVariable Long id, @RequestParam boolean value) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getUsername().equals(CurrentUser.get().username()) && !value)
            throw ApiException.unprocessable("SELF_DEACTIVATE", "You cannot deactivate your own account");
        u.setActive(value);
        audit.record(CurrentUser.get(), value ? "user.activate" : "user.deactivate", "USER", u.getUsername(), null, null);
        return users.save(u);
    }
}
