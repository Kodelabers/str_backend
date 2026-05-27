package com.str.backend.address;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "eturizam_test", name = "ar_address")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class HouseNumberEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "broj", nullable = false, updatable = false)
    private String name;

    @Column(name = "ulica_id", nullable = false, updatable = false)
    private Long ulicaId;

    @Column(name = "kc_broj", updatable = false)
    private String kcBroj;

    @Column(name = "kat_opcina_naziv", updatable = false)
    private String katOpcinaNaziv;
}
