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
@Table(schema = "str", name = "subject")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class StrSubjectEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "active", nullable = false, updatable = false)
    private boolean active;

    @Column(name = "subtype_id", updatable = false)
    private Long subtypeId;

    @Column(name = "jips", updatable = false)
    private String jips;

    @Column(name = "jips_source_id", updatable = false)
    private Long jipsSourceId;
}
