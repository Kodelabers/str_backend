package com.str.backend.str;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

@Entity
@Immutable
@Table(schema = "str", name = "subject_version")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class StrSubjectVersionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "active", nullable = false, updatable = false)
    private boolean active;

    @Column(name = "name", updatable = false)
    private String name;

    @Column(name = "first_name", updatable = false)
    private String firstName;

    @Column(name = "last_name", updatable = false)
    private String lastName;

    @Column(name = "country_id", updatable = false)
    private Long countryId;

    @Column(name = "subject_id", updatable = false)
    private Long subjectId;

    @Column(name = "pin", updatable = false)
    private String pin;

    @Column(name = "date_of_birth", updatable = false)
    private LocalDate dateOfBirth;

    @Column(name = "historical", updatable = false)
    private boolean historical;
}
