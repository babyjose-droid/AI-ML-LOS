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
                                @NotBlank String name, @NotBlank String level, Long parentId, String city,
                                String pincodes, String products) {}

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
        b.setPincodes(pins(r.pincodes(), null));
        b.setProducts(codes(r.products()));
        Branch saved = branches.save(b);
        audit.record(CurrentUser.get(), "branch.create", "BRANCH", r.code(), null, r.name() + " (" + r.level() + ")");
        return saved;
    }

    /** Edit a branch: name, city, parent, PIN codes served and products offered (Branch hierarchy, SCR-121). */
    @PutMapping("/api/admin/branches/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Branch updateBranch(@PathVariable Long id, @Valid @RequestBody BranchRequest r) {
        Branch b = branches.findById(id).orElseThrow(() -> ApiException.notFound("Branch"));
        if (!b.getCode().equals(r.code())) throw ApiException.unprocessable("CODE_FIXED", "A branch code cannot be changed");
        if (!b.getLevel().equals(r.level())) throw ApiException.unprocessable("LEVEL_FIXED", "A branch level cannot be changed");
        if (r.parentId() != null) {
            if (r.parentId().equals(id)) throw ApiException.unprocessable("BAD_PARENT", "A branch cannot be its own parent");
            if (!branches.existsById(r.parentId())) throw ApiException.notFound("Parent branch");
            // no cycles: the new parent may not sit under this branch
            Long p = r.parentId();
            for (int i = 0; p != null && i < 20; i++) {
                if (p.equals(id)) throw ApiException.unprocessable("BAD_PARENT", "The parent cannot be a unit under this branch");
                p = branches.findById(p).map(Branch::getParentId).orElse(null);
            }
        } else if (!"HEAD_OFFICE".equals(b.getLevel())) throw ApiException.unprocessable("PARENT_REQUIRED", "Only the head office can have no parent");
        String before = b.getName() + " · PINs " + (b.getPincodes() == null ? "none" : b.getPincodes().split(",").length) + " · products " + (b.getProducts() == null ? "all" : b.getProducts());
        b.setName(r.name());
        b.setCity(r.city());
        b.setParentId(r.parentId());
        b.setPincodes(pins(r.pincodes(), id));
        b.setProducts(codes(r.products()));
        Branch saved = branches.save(b);
        audit.record(CurrentUser.get(), "branch.update", "BRANCH", b.getCode(), null, before + " → " + b.getName() + " · PINs "
                + (b.getPincodes() == null ? "none" : b.getPincodes().split(",").length) + " · products " + (b.getProducts() == null ? "all" : b.getProducts()));
        return saved;
    }

    @PatchMapping("/api/admin/branches/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Branch setBranchActive(@PathVariable Long id, @RequestParam boolean value) {
        Branch b = branches.findById(id).orElseThrow(() -> ApiException.notFound("Branch"));
        if (!value) {
            if ("HEAD_OFFICE".equals(b.getLevel())) throw ApiException.unprocessable("HEAD_OFFICE", "The head office cannot be deactivated");
            if (branches.findAll().stream().anyMatch(x -> id.equals(x.getParentId()) && x.isActive()))
                throw ApiException.conflict("HAS_ACTIVE_UNITS", "Deactivate or move the units under this branch first");
            long staff = users.findAll().stream().filter(u -> id.equals(u.getBranchId()) && u.isActive()).count();
            if (staff > 0) throw ApiException.conflict("HAS_ACTIVE_USERS", staff + " active user(s) still belong to this branch; move them first");
        } else if (b.getParentId() != null && !branches.findById(b.getParentId()).map(Branch::isActive).orElse(false))
            throw ApiException.conflict("PARENT_INACTIVE", "Activate the parent unit first");
        b.setActive(value);
        audit.record(CurrentUser.get(), value ? "branch.activate" : "branch.deactivate", "BRANCH", b.getCode(), null, b.getName());
        return branches.save(b);
    }

    /** Six-digit PIN codes, de-duplicated; a PIN can belong to only one active branch so routing is unambiguous. */
    private String pins(String raw, Long self) {
        if (raw == null || raw.isBlank()) return null;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String p : raw.split("[,\\s]+")) {
            if (p.isBlank()) continue;
            if (!p.matches("[1-9][0-9]{5}")) throw ApiException.unprocessable("BAD_PINCODE", p + " is not a 6-digit PIN code");
            out.add(p);
        }
        if (out.size() > 300) throw ApiException.unprocessable("TOO_MANY_PINCODES", "At most 300 PIN codes per branch");
        for (Branch other : branches.findAll()) {
            if (other.getId().equals(self) || !other.isActive()) continue;
            for (String p : out) if (other.servesPin(p)) throw ApiException.conflict("PIN_TAKEN", "PIN " + p + " is already served by " + other.getName());
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    private static String codes(String raw) {
        if (raw == null || raw.isBlank()) return null;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String p : raw.split("[,\\s]+")) if (!p.isBlank()) {
            if (!p.matches("[A-Za-z0-9-]{2,20}")) throw ApiException.unprocessable("BAD_PRODUCT", p + " is not a product code");
            out.add(p.toUpperCase());
        }
        return out.isEmpty() ? null : String.join(",", out);
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
        if (r.role() == com.rhythm.los.org.Role.BORROWER) throw ApiException.unprocessable("NOT_A_STAFF_ROLE", "Borrowers sign in with OTP; they are not staff users");
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
        if (r.role() == com.rhythm.los.org.Role.BORROWER) throw ApiException.unprocessable("NOT_A_STAFF_ROLE", "Borrowers sign in with OTP; they are not staff users");
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

    @PostMapping("/api/admin/users/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser unlock(@PathVariable Long id) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        u.setLockedUntil(null);
        u.setFailedLogins(0);
        audit.record(CurrentUser.get(), "user.unlock", "USER", u.getUsername(), null, null);
        return users.save(u);
    }

    @PostMapping("/api/admin/users/{id}/mfa-reset")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser resetMfa(@PathVariable Long id) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        u.setMfaEnabled(false);
        u.setMfaSecret(null);
        audit.record(CurrentUser.get(), "user.mfa_reset", "USER", u.getUsername(), null, "MFA turned off; the user sets up a new device");
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
