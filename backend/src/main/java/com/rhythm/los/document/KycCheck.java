package com.rhythm.los.document;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "kyc_check")
public class KycCheck {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "check_name") private String checkName;
    private String result;
    private BigDecimal score;
    private String expected;
    private String found;
    private String source;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    protected KycCheck() {}

    public KycCheck(Long applicationId, String checkName, double score, String expected, String found, String source) {
        this.applicationId = applicationId;
        this.checkName = checkName;
        this.score = BigDecimal.valueOf(Math.round(score * 1000) / 1000.0);
        this.result = score >= 0.85 ? "PASS" : score >= 0.6 ? "WARN" : "FAIL";
        this.expected = cut(expected);
        this.found = cut(found);
        this.source = source;
    }

    private static String cut(String s) { return s == null ? null : s.length() > 200 ? s.substring(0, 200) : s; }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public String getCheckName() { return checkName; }
    public String getResult() { return result; }
    public BigDecimal getScore() { return score; }
    public String getExpected() { return expected; }
    public String getFound() { return found; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
