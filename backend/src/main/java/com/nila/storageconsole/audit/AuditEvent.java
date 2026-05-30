package com.nila.storageconsole.audit;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at = Instant.now();

    @Column(length = 64)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false, length = 128)
    private String target;

    @Column(columnDefinition = "TEXT")
    private String detail;

    public AuditEvent() {}

    public AuditEvent(String actor, String action, String target, String detail) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getDetail() { return detail; }
}
