package com.str.backend.core.sr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "core", name = "sr_pravna_osoba")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CoreSrPravnaOsobaEntity {

    @Id
    @Column(name = "oib", length = 11, nullable = false, updatable = false)
    private String oib;

    @Column(name = "naziv", nullable = false)
    private String naziv;

    @Column(name = "sjediste")
    private String sjediste;

    @Column(name = "zastupnici")
    private String zastupnici;
}
