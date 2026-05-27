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
@Table(schema = "rpj_dgu", name = "gradovi_i_opcine")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class MunicipalityEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "jls_ime", nullable = false, updatable = false)
    private String name;

    @Column(name = "jls_mb", nullable = false, updatable = false)
    private String jlsMb;

    @Column(name = "zu_rb", nullable = false, updatable = false)
    private Integer zuRb;

    public String getTypeCode() {
        return null;
    }
}
