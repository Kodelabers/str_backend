package com.str.backend.sso;

import com.str.backend.domain.Ponuda;
import com.str.backend.domain.Status;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "sso")
public class SsoEntity {

    @Id
    @Column(name = "uuid_sso", nullable = false, updatable = false)
    private UUID uuidSso;

    @Column(name = "registracijski_broj", length = 18, unique = true)
    private String registracijskiBroj;

    @Column(name = "kapacitet_kreveta", nullable = false)
    private int kapacitetKreveta;

    @Column(name = "kapacitet_gostiju", nullable = false)
    private int kapacitetGostiju;

    @Enumerated(EnumType.STRING)
    @Column(name = "ponuda", nullable = false)
    private Ponuda ponuda;

    @Column(name = "kat")
    private String kat;

    @Column(name = "broj_stana")
    private String brojStana;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SsoEntity() {
    }

    public static SsoEntity initiate(UUID coreObjektUuid, int kapacitetKreveta, int kapacitetGostiju,
                                     Ponuda ponuda, String kat, String brojStana) {
        SsoEntity e = new SsoEntity();
        e.uuidSso = coreObjektUuid;
        e.kapacitetKreveta = kapacitetKreveta;
        e.kapacitetGostiju = kapacitetGostiju;
        e.ponuda = ponuda;
        e.kat = kat;
        e.brojStana = brojStana;
        e.status = Status.INICIIRAN;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public UUID getUuidSso() { return uuidSso; }
    public String getRegistracijskiBroj() { return registracijskiBroj; }
    public int getKapacitetKreveta() { return kapacitetKreveta; }
    public int getKapacitetGostiju() { return kapacitetGostiju; }
    public Ponuda getPonuda() { return ponuda; }
    public String getKat() { return kat; }
    public String getBrojStana() { return brojStana; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    void applyStatus(Status next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    void assignRegistracijskiBroj(String rb) {
        this.registracijskiBroj = rb;
        this.updatedAt = Instant.now();
    }
}
