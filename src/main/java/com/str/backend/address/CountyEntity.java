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
@Table(schema = "rpj_dgu", name = "zupanije")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CountyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "zu_ime", nullable = false, updatable = false)
    private String name;

    @Column(name = "zu_rb", nullable = false, updatable = false)
    private Integer zuRb;
}
