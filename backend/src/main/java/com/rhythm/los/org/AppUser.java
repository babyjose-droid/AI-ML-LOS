package com.rhythm.los.org;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    @Column(name = "full_name")
    private String fullName;
    @JsonIgnore
    @Column(name = "password_hash")
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    private Role role;
    @Column(name = "branch_id")
    private Long branchId;
    private String email;
    private String mobile;
    private boolean active = true;
    @JsonIgnore @Column(name = "mfa_secret") private String mfaSecret;
    @Column(name = "mfa_enabled") private boolean mfaEnabled;
    @Column(name = "failed_logins") private int failedLogins;
    @Column(name = "locked_until") private Instant lockedUntil;
    @Column(name = "last_login_at") private Instant lastLoginAt;

    @JsonIgnore public String getMfaSecret() { return mfaSecret; }
    public void setMfaSecret(String v) { this.mfaSecret = v; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public void setMfaEnabled(boolean v) { this.mfaEnabled = v; }
    public int getFailedLogins() { return failedLogins; }
    public void setFailedLogins(int v) { this.failedLogins = v; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant v) { this.lockedUntil = v; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant v) { this.lastLoginAt = v; }
    public boolean isLocked() { return lockedUntil != null && lockedUntil.isAfter(Instant.now()); }
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
