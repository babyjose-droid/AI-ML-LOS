package com.rhythm.los.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "model_shadow_score")
public class ShadowScore {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "decision_id") private Long decisionId;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "model_version_id") private Long modelVersionId;
    private BigDecimal pd;
    private String band;
    private String decision;
    @Column(name = "champion_pd") private BigDecimal championPd;
    @Column(name = "champion_band") private String championBand;
    @Column(name = "champion_decision") private String championDecision;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    protected ShadowScore() {}

    public ShadowScore(Long decisionId, Long applicationId, Long modelVersionId, double pd, String band, String decision,
                       double championPd, String championBand, String championDecision) {
        this.decisionId = decisionId;
        this.applicationId = applicationId;
        this.modelVersionId = modelVersionId;
        this.pd = BigDecimal.valueOf(pd).setScale(6, java.math.RoundingMode.HALF_UP);
        this.band = band;
        this.decision = decision;
        this.championPd = BigDecimal.valueOf(championPd).setScale(6, java.math.RoundingMode.HALF_UP);
        this.championBand = championBand;
        this.championDecision = championDecision;
    }

    public Long getId() { return id; }
    public Long getDecisionId() { return decisionId; }
    public Long getApplicationId() { return applicationId; }
    public Long getModelVersionId() { return modelVersionId; }
    public BigDecimal getPd() { return pd; }
    public String getBand() { return band; }
    public String getDecision() { return decision; }
    public BigDecimal getChampionPd() { return championPd; }
    public String getChampionBand() { return championBand; }
    public String getChampionDecision() { return championDecision; }
    public Instant getCreatedAt() { return createdAt; }
}
