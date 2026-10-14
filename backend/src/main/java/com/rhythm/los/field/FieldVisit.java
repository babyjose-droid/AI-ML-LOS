package com.rhythm.los.field;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "field_visit")
public class FieldVisit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "client_id") private String clientId;
    private String officer;
    @Column(name = "checkin_at") private Instant checkinAt;
    @Column(name = "checkin_lat") private Double checkinLat;
    @Column(name = "checkin_lng") private Double checkinLng;
    @Column(name = "checkin_accuracy_m") private Double checkinAccuracyM;
    @Column(name = "distance_m") private Double distanceM;
    @Column(name = "location_check") private String locationCheck;
    @Column(name = "captured_offline") private boolean capturedOffline;
    @Column(name = "checklist_json") private String checklistJson;
    @Column(name = "data_json") private String dataJson;
    @Column(name = "references_json") private String referencesJson;
    private String remarks;
    @Column(name = "photo_checks_json") private String photoChecksJson;
    private String outcome;
    @Column(name = "synced_at") private Instant syncedAt = Instant.now();

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getClientId() { return clientId; }
    public void setClientId(String v) { this.clientId = v; }
    public String getOfficer() { return officer; }
    public void setOfficer(String v) { this.officer = v; }
    public Instant getCheckinAt() { return checkinAt; }
    public void setCheckinAt(Instant v) { this.checkinAt = v; }
    public Double getCheckinLat() { return checkinLat; }
    public void setCheckinLat(Double v) { this.checkinLat = v; }
    public Double getCheckinLng() { return checkinLng; }
    public void setCheckinLng(Double v) { this.checkinLng = v; }
    public Double getCheckinAccuracyM() { return checkinAccuracyM; }
    public void setCheckinAccuracyM(Double v) { this.checkinAccuracyM = v; }
    public Double getDistanceM() { return distanceM; }
    public void setDistanceM(Double v) { this.distanceM = v; }
    public String getLocationCheck() { return locationCheck; }
    public void setLocationCheck(String v) { this.locationCheck = v; }
    public boolean isCapturedOffline() { return capturedOffline; }
    public void setCapturedOffline(boolean v) { this.capturedOffline = v; }
    public String getChecklistJson() { return checklistJson; }
    public void setChecklistJson(String v) { this.checklistJson = v; }
    public String getDataJson() { return dataJson; }
    public void setDataJson(String v) { this.dataJson = v; }
    public String getReferencesJson() { return referencesJson; }
    public void setReferencesJson(String v) { this.referencesJson = v; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String v) { this.remarks = v; }
    public String getPhotoChecksJson() { return photoChecksJson; }
    public void setPhotoChecksJson(String v) { this.photoChecksJson = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public Instant getSyncedAt() { return syncedAt; }
}
