package com.str.backend.str;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "str", name = "facility")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class StrFacilityEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "active", nullable = false, updatable = false)
    private boolean active;

    @Column(name = "subject_id", updatable = false)
    private Long subjectId;
}
