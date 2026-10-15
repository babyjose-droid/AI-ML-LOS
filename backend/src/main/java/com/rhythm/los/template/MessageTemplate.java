package com.rhythm.los.template;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "message_template")
public class MessageTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String channel;
    private String code;
    private String language = "en";
    private String subject;
    private String body;
    private int version = 1;
    private boolean active = true;
    private String note;
    @Column(name = "updated_by") private String updatedBy = "system";
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { this.language = v; }
    public String getSubject() { return subject; }
    public void setSubject(String v) { this.subject = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
