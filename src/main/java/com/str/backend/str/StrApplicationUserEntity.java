package com.str.backend.str;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projekcija externo vlasništvom {@code str.application_user} tablice.
 * Koristi se isključivo za autorizaciju: po NIAS OIB-u ({@code username = oib})
 * pronalazimo internog korisnika i čitamo {@code internal} zastavicu.
 *
 * <p>Interni korisnik je uvijek fizička osoba (username = OIB). Tablica ima puno više
 * stupaca; ovdje mapiramo samo one koje autorizacija konzumira. Na dev/prod čita se iz
 * stvarne (externo punjene) tablice; na local/mock mockirano changesetom 119 (context=local).
 */
@Entity
@Immutable
@Table(schema = "str", name = "application_user")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class StrApplicationUserEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "username", updatable = false)
    private String username;

    /** Nullable u stvarnoj tablici — {@code null} tretiramo kao „nije interni". */
    @Column(name = "internal", updatable = false)
    private Boolean internal;

    @Column(name = "active", updatable = false)
    private boolean active;
}
