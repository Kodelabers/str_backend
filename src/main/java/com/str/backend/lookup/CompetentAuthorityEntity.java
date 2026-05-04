package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "str", name = "internal_organization")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CompetentAuthorityEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "active", nullable = false, updatable = false)
    private boolean active;
}
