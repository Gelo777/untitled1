package org.example.model.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "idempotency", indexes = @Index(name = "idx_idem_key", columnList = "idemKey", unique = true))
public class IdempotencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String idemKey;

    @Column(nullable = false)
    private String licenseKey;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int status;

    @Lob
    @Column(nullable = false)
    private String responseJson;

    protected IdempotencyEntity() {}

    public static IdempotencyEntity of(String idemKey, String licenseKey, int status, String responseJson) {
        IdempotencyEntity e = new IdempotencyEntity();
        e.idemKey = idemKey;
        e.licenseKey = licenseKey;
        e.status = status;
        e.responseJson = responseJson;
        e.createdAt = Instant.now();
        return e;
    }

    public String getResponseJson() { return responseJson; }
    public String getLicenseKey() { return licenseKey; }
}
