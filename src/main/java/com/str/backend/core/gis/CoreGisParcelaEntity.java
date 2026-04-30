package com.str.backend.core.gis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "core", name = "gis_parcela")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CoreGisParcelaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "katastarska_opcina", nullable = false)
    private String katastarskaOpcina;

    @Column(name = "broj_cestice", nullable = false)
    private String brojCestice;

    @Column(name = "povrsina_m2")
    private Integer povrsinaM2;

    @Column(name = "namjena")
    private String namjena;

    @Column(name = "legalan_objekt")
    private Boolean legalanObjekt;
}
