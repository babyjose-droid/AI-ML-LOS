package com.rhythm.los.borrower;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "borrower_otp")
public class BorrowerOtp {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String mobile;
    @Column(name = "code_hash") private String codeHash;
    @Column(name = "expires_at") private Instant expiresAt;
    private int attempts;
    private boolean used;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    protected BorrowerOtp() {}

    public BorrowerOtp(String mobile, String codeHash, Instant expiresAt) {
        this.mobile = mobile;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getMobile() { return mobile; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { this.attempts = v; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean v) { this.used = v; }
    public Instant getCreatedAt() { return createdAt; }
}
