package com.str.backend.str.verifikacija;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>Metode se smiju zvati samo iz transakcije pokušaja {@link EturizamVerifikacijaWriter}-a, da
 * zahtjev, obavijesti i outbox redak prođu ili padnu zajedno, i nikad iz transakcije koja izdaje RB.
 * {@link Propagation#MANDATORY} sam to ne jamči — priključio bi se bilo kojoj transakciji — pa svaka
 * metoda još provjerava naziv tekuće transakcije ({@link EturizamVerifikacijaWriter#TX_NAME}) i bez
 * njega baca {@link IllegalStateException}.
 *
 * <p>Vremena su lokalno vrijeme Europe/Zagreb ({@code timestamp} bez zone, kao eTurizamovi retci) —
 * zadaje ih pozivatelj.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class EturizamVerifikacijaRepository {

    private final JdbcTemplate jdbc;

    public EturizamVerifikacijaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Zahtjev za dokument, bez obzira na {@code active} — po dokumentu smije postojati samo jedan. */
    public Optional<Long> findRequestId(long documentId) {
        requireWriterTransaction();
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
    public long insertRequest(long documentId, String korisnik, LocalDateTime now) {
        requireWriterTransaction();
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
    public Optional<Long> findJurisdictionUnit(long documentId) {
        requireWriterTransaction();
        return jdbc.queryForList(
                        "SELECT jurisdiction_organizational_unit_id FROM str.document WHERE id = ?",
                        Long.class, documentId)
                .stream().filter(Objects::nonNull).findFirst();
    }

    /** Aktivni interni korisnici jedinice — primatelji obavijesti. Bez filtra po roli (odluka 5. 11. 2026.). */
    public List<Long> findRecipients(long organizationalUnitId) {
        requireWriterTransaction();
        return jdbc.queryForList("""
                SELECT u.id
                  FROM str.application_user u
                 WHERE u.organizational_unit_id = ?
                   AND u.active = TRUE
                   AND u.internal = TRUE
                 ORDER BY u.id
                """, Long.class, organizationalUnitId);
    }

    /** Nepročitana obavijest jednom korisniku; {@code podaci} je JSON tekst. */
    public void insertNotification(long applicationUserId, String vrsta, String tekst, String objektTip,
                                   long objektId, String podaci, LocalDateTime now) {
        requireWriterTransaction();
        jdbc.update("""
                INSERT INTO str.korisnicka_obavijest
                  (application_user_id, vrsta, tekst, objekt_tip, objekt_id, podaci, stvoreno)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, applicationUserId, vrsta, tekst, objektTip, objektId, podaci, now);
    }

    private static void requireWriterTransaction() {
        if (!EturizamVerifikacijaWriter.TX_NAME.equals(TransactionSynchronizationManager.getCurrentTransactionName())) {
            throw new IllegalStateException("str.verification_request / str.korisnicka_obavijest smiju se dirati "
                    + "samo iz transakcije EturizamVerifikacijaWriter-a, ne iz '"
                    + TransactionSynchronizationManager.getCurrentTransactionName() + "'");
        }
    }
}
