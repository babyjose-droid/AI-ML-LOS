package com.rhythm.los.security;

import com.rhythm.los.org.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final long ttlMinutes;

    public JwtService(JwtEncoder encoder, @Value("${rhythm.jwt.ttl-minutes}") long ttlMinutes) {
        this.encoder = encoder;
        this.ttlMinutes = ttlMinutes;
    }

    /** Short-lived token for a borrower who verified their mobile number with an OTP. */
    public String issueBorrower(String mobile, long minutes) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("rhythm-los")
                .subject("borrower:" + mobile)
                .issuedAt(now)
                .expiresAt(now.plus(minutes, ChronoUnit.MINUTES))
                .claim("role", "BORROWER")
                .claim("roles", List.of("BORROWER"))
                .claim("name", "Borrower " + mobile.substring(0, 2) + "XXXXXX" + mobile.substring(8))
                .claim("mobile", mobile)
                .claim("branchId", "")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public String issue(AppUser u) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("rhythm-los")
                .subject(u.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(ttlMinutes, ChronoUnit.MINUTES))
                .claim("role", u.getRole().name())
                .claim("roles", List.of(u.getRole().name()))
                .claim("name", u.getFullName())
                .claim("branchId", u.getBranchId() == null ? "" : u.getBranchId().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
