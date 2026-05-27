package com.str.backend.address;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "rpj_dgu", name = "naselja")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SettlementEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "na_ime", nullable = false, updatable = false)
    private String name;

    @Column(name = "na_mb", nullable = false, updatable = false)
    private String naMb;

    @Column(name = "jls_mb", updatable = false)
    private Long jlsMb;

    @Transient
    @Setter
    private String postalCode;
}
