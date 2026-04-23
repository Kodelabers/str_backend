package com.str.backend.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

@Entity
@Immutable
@Table(schema = "core", name = "objekt")
public class CoreObjektEntity {

    @Id
    @Column(name = "uuid", nullable = false, updatable = false)
    private UUID uuid;

    @Column(name = "katastarska_opcina")
    private String katastarskaOpcina;

    @Column(name = "broj_katastarske_cestice")
    private String brojKatastarskeCestice;

    @Column(name = "max_kreveta", nullable = false)
    private int maxKreveta;

    @Column(name = "max_gostiju", nullable = false)
    private int maxGostiju;

    @Column(name = "legalan", nullable = false)
    private boolean legalan;

    protected CoreObjektEntity() {
    }

    public UUID getUuid() { return uuid; }
    public String getKatastarskaOpcina() { return katastarskaOpcina; }
    public String getBrojKatastarskeCestice() { return brojKatastarskeCestice; }
    public int getMaxKreveta() { return maxKreveta; }
    public int getMaxGostiju() { return maxGostiju; }
    public boolean isLegalan() { return legalan; }
}
