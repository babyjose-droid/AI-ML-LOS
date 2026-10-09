package com.rhythm.los.application;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "state_history")
public class StateHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Enumerated(EnumType.STRING) private Domain domain;
    @Column(name = "from_state") private String fromState;
    @Column(name = "to_state") private String toState;
    private String event;
    private String actor;
    private String note;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    protected StateHistory() {}

    public StateHistory(Long applicationId, Domain domain, String from, String to, String event, String actor, String note) {
        this.applicationId = applicationId;
        this.domain = domain;
        this.fromState = from;
        this.toState = to;
        this.event = event;
        this.actor = actor;
        this.note = note == null ? null : (note.length() > 500 ? note.substring(0, 500) : note);
    }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public Domain getDomain() { return domain; }
    public String getFromState() { return fromState; }
    public String getToState() { return toState; }
    public String getEvent() { return event; }
    public String getActor() { return actor; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
