package com.str.backend.str.verifikacija;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Upis zahtjeva za žurnu verifikaciju i obavijesti službenicima u eTurizmovu shemu {@code str}
 * ({@code str.verification_request}, {@code str.korisnicka_obavijest}) te čitanje primatelja.
 *
 * <p>Uz {@code StrFacilityRepository.writeBackRegistrationNumber} i {@code clearRegistrationNumber}
 * (stupac {@code str.facility.registration_number}) ovo je jedini put pisanja u {@code str}. Uski
 * native SQL nad točno tim stupcima, bez entiteta — nijedan drugi tok ne može slučajno perzistirati
 * promjenu u tuđu tablicu. Postojeći redak se nikad ne mijenja, samo {@code INSERT}.
 *
 * <p><b>Vidljiv samo unutar paketa</b> (razred i metode): zove ga isključivo transakcija pokušaja
 * {@link EturizamVerifikacijaWriter}-a, da zahtjev, obavijesti i outbox redak prođu ili padnu zajedno.
 * Transakcija izdavanja RB-a ({@code RegistrationService}, drugi paket) do njega ne može.
 * {@link Propagation#MANDATORY} uz to odbija poziv bez transakcije.
 *
 * <p>Vremena su lokalno vrijeme Europe/Zagreb ({@code timestamp} bez zone, kao eTurizamovi retci) —
 * zadaje ih pozivatelj.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class EturizamVerifikacijaRepository {

    private final JdbcTemplate jdbc;

    EturizamVerifikacijaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Zahtjev za dokument, bez obzira na {@code active} — po dokumentu smije postojati samo jedan. */
    Optional<Long> findRequestId(long documentId) {
        return jdbc.queryForList(
                        "SELECT id FROM str.verification_request WHERE source_document_id = ?",
                        Long.class, documentId)
                .stream().findFirst();
    }

    /**
     * Upisuje aktivan zahtjev i vraća njegov id. Id se čita natrag po dokumentu (UNIQUE), a ne
     * kroz {@code RETURNING} / generirane ključeve, da isti SQL radi i na H2.
     *
     * <p>Ako je eTurizam u međuvremenu upisao zahtjev za isti dokument, INSERT padne na
     * {@code uq_verification_request_source_document} ({@code DuplicateKeyException}) i cijela
     * transakcija se poništi; pozivatelj tada ponovi pokušaj, koji zatečeni zahtjev nađe.
     * {@code ON CONFLICT DO NOTHING} bi to riješio u jednom koraku, ali H2 ga nema.
     */
    long insertRequest(long documentId, String korisnik, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO str.verification_request
                  (created_by, created_date, last_modified_by, last_modified_date, active, source_document_id)
                VALUES (?, ?, ?, ?, TRUE, ?)
                """, korisnik, now, korisnik, now, documentId);
        return findRequestId(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "upisani zahtjev za dokument " + documentId + " nije pronađen"));
    }

    /**
     * Nadležna jedinica dokumenta ({@code str.document.jurisdiction_organizational_unit_id});
     * prazno kad je dokument nema (ili dokumenta nema).
     */
    Optional<Long> findJurisdictionUnit(long documentId) {
        return jdbc.queryForList(
                        "SELECT jurisdiction_organizational_unit_id FROM str.document WHERE id = ?",
                        Long.class, documentId)
                .stream().filter(Objects::nonNull).findFirst();
    }

    /**
     * Aktivni interni korisnici jedinice — primatelji obavijesti, najviše {@code limit} (po id-u).
     * Bez filtra po roli (odluka 5. 11. 2026.).
     */
    List<Long> findRecipients(long organizationalUnitId, int limit) {
        return jdbc.queryForList("""
                SELECT u.id
                  FROM str.application_user u
                 WHERE u.organizational_unit_id = ?
                   AND u.active = TRUE
                   AND u.internal = TRUE
                 ORDER BY u.id
                 LIMIT ?
                """, Long.class, organizationalUnitId, limit);
    }

    /** Nepročitana obavijest jednom korisniku; {@code podaci} je JSON tekst. */
    void insertNotification(long applicationUserId, String vrsta, String tekst, String objektTip,
                            long objektId, String podaci, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO str.korisnicka_obavijest
                  (application_user_id, vrsta, tekst, objekt_tip, objekt_id, podaci, stvoreno)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, applicationUserId, vrsta, tekst, objektTip, objektId, podaci, now);
    }
}
