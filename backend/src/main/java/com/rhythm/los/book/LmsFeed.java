package com.rhythm.los.book;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "lms_feed")
public class LmsFeed {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "feed_date") private LocalDate feedDate;
    private String source;
    @Column(name = "rows_total") private int rowsTotal;
    private int accepted;
    private int rejected;
    @Column(name = "errors_json") private String errorsJson;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public LocalDate getFeedDate() { return feedDate; }
    public void setFeedDate(LocalDate v) { this.feedDate = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public int getRowsTotal() { return rowsTotal; }
    public void setRowsTotal(int v) { this.rowsTotal = v; }
    public int getAccepted() { return accepted; }
    public void setAccepted(int v) { this.accepted = v; }
    public int getRejected() { return rejected; }
    public void setRejected(int v) { this.rejected = v; }
    public String getErrorsJson() { return errorsJson; }
    public void setErrorsJson(String v) { this.errorsJson = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
}
