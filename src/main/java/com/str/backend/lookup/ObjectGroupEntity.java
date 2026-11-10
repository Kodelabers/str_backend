package com.str.backend.lookup;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * Skupina objekata ({@code str_rn.object_group}, T13) — eTurizmova skupina ({@code FT_*}): domaćinstvo,
 * ugostiteljske usluge u OPG-u, hoteli, kampovi… Vrsta smještaja se na nju veže preko
 * {@code accommodation_type.group_name}.
 *
 * <p>Šifrarnik je u vlasništvu migracija (changeset 156), pa entitet nema settere; javni
 * konstruktor služi samo testovima.
 */
@Entity
@Table(schema = "str_rn", name = "object_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObjectGroupEntity {

    /** eTurizam šifra skupine ({@code FT_*}). */
    @Id
    @Column(name = "code")
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    /** Oznaka skupine u registracijskom broju (dvije znamenke iza županije). */
    @Column(name = "rb_group_code", nullable = false)
    private short rbGroupCode;

    /** Vrste subjekta koje smiju prijaviti objekt ove skupine. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "str_rn", name = "subject_category_object_group",
            joinColumns = @JoinColumn(name = "object_group_code"))
    @Column(name = "subject_category_code")
    @Enumerated(EnumType.STRING)
    private Set<SubjectCategory> subjectCategories = new HashSet<>();

    /** Samo za testove (H2 bez Liquibasea). */
    public ObjectGroupEntity(String code, String name, int rbGroupCode, Set<SubjectCategory> subjectCategories) {
        this.code = code;
        this.name = name;
        this.rbGroupCode = (short) rbGroupCode;
        this.subjectCategories = new HashSet<>(subjectCategories);
    }
}
