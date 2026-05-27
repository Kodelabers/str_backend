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
@Table(schema = "eturizam_test", name = "ar_ulice")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class StreetEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "naziv_ulice", nullable = false, updatable = false)
    private String name;

    @Column(name = "naselje_id", nullable = false, updatable = false)
    private String naseljeId;

    @Column(name = "tip_ulice", updatable = false)
    private String typeCode;
}
