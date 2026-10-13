package com.rhythm.los.vkyc;

import jakarta.persistence.*;
import java.time.Instant;

/** One WebRTC signalling message (offer, answer, ICE candidate) or an instruction shown to the customer. */
@Entity
@Table(name = "vkyc_signal")
public class VkycSignal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id") private Long sessionId;
    private String sender;
    private String kind;
    private String payload;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    protected VkycSignal() {}

    public VkycSignal(Long sessionId, String sender, String kind, String payload) {
        this.sessionId = sessionId;
        this.sender = sender;
        this.kind = kind;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public String getSender() { return sender; }
    public String getKind() { return kind; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
