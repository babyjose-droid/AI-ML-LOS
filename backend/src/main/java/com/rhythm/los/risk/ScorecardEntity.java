package com.rhythm.los.risk;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scorecard")
public class ScorecardEntity {
    @Id private String code;
    private String name;
    private String industry;
    @Column(name = "definition_json") private String definitionJson;
    private boolean active = true;
    @Column(name = "updated_by") private String updatedBy;
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getIndustry() { return industry; }
    public void setIndustry(String v) { this.industry = v; }
    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String v) { this.definitionJson = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
