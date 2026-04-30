package com.str.backend.core.rpj;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "core", name = "rpj_adresa")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CoreRpjAdresaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "zupanija", nullable = false)
    private String zupanija;

    @Column(name = "grad", nullable = false)
    private String grad;

    @Column(name = "naselje")
    private String naselje;

    @Column(name = "ulica", nullable = false)
    private String ulica;

    @Column(name = "kucni_broj", nullable = false)
    private String kucniBroj;

    @Column(name = "postanski_broj")
    private String postanskiBroj;
}
