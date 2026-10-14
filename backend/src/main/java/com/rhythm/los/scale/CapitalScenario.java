package com.rhythm.los.scale;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "capital_scenario")
public class CapitalScenario {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    @Column(name = "inputs_json") private String inputsJson;
    @Column(name = "result_json") private String resultJson;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getInputsJson() { return inputsJson; }
    public void setInputsJson(String v) { this.inputsJson = v; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String v) { this.resultJson = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
