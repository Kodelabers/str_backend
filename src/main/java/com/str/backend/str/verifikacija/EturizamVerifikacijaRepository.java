package com.str.backend.str.verifikacija;

import com.str.backend.address.CountyNames;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Upis zahtjeva za žurnu verifikaciju i obavijesti službenicima u eTurizmovu shemu {@code str}
 * ({@code str.verification_request}, {@code str.korisnicka_obavijest}) te čitanje primatelja: po
 * nadležnoj jedinici dokumenta (objekt iz eTurizma) ili po županiji (novi objekt — {@code str.county},
 * nadležnost naselja, {@code rpj_dgu.zupanije}).
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

    /**
     * Županija u eTurizmu ({@code str.county.id}) za naziv županije iz STR-a ({@code accommodation.county},
     * adresni registar), istim pravilom kao {@code OfficialCountyResolver} u obrnutom smjeru: prvo po
     * šifri ({@code str.county.external_code} = {@code rpj_dgu.zupanije.zu_rb} županije tog naziva), a
     * kad je nema, po nazivu ({@link CountyNames#key}). Prazno kad se županija ne prepozna. Obje tablice
     * imaju dvadesetak redaka, pa se uspoređuje u Javi — usporedivi oblik naziva nije SQL.
     */
    Optional<Long> findCountyId(String zupanija) {
        String key = CountyNames.key(zupanija);
        if (key == null) {
            return Optional.empty();
        }
        Set<Integer> zuRb = new HashSet<>();
        jdbc.query("SELECT zu_rb, zu_ime FROM rpj_dgu.zupanije", rs -> {
            if (key.equals(CountyNames.key(rs.getString("zu_ime")))) {
                zuRb.add(rs.getInt("zu_rb"));
            }
        });
        List<Map<String, Object>> zupanije = jdbc.queryForList(
                "SELECT id, name, external_code FROM str.county ORDER BY id");
        Optional<Long> poSifri = zupanije.stream()
                .filter(c -> {
                    Integer sifra = parseSifra(c.get("external_code"));
                    return sifra != null && zuRb.contains(sifra);
                })
                .map(c -> ((Number) c.get("id")).longValue())
                .findFirst();
        if (poSifri.isPresent()) {
            return poSifri;
        }
        return zupanije.stream()
                .filter(c -> key.equals(CountyNames.key((String) c.get("name"))))
                .map(c -> ((Number) c.get("id")).longValue())
                .findFirst();
    }

    /**
     * Aktivni interni službenici županijske organizacije nadležne za županiju — primatelji obavijesti
     * za novi objekt, najviše {@code limit} (po id-u). Novi objekt nema dokument, pa ni nadležnu
     * jedinicu; uzimaju se organizacije vrste {@code IOT_ZUPANIJA} čija je neka aktivna jedinica nadležna
     * ({@code settlement_jurisdiction}) za barem jedno naselje te županije — obrnut lanac od
     * {@code InternalUserRepository.findOrganization} (po njemu {@code OfficialCountyResolver} službeniku
     * određuje županiju).
     *
     * <p>Službenik pripada organizaciji po {@code internal_organization_id}, ne po jedinici: na CDU-u
     * (9. 11. 2026., W3) dio službenika županije nije u jedinici koja nosi nadležnost (Šibensko-kninska:
     * 1 u jedinici, 2 u organizaciji), a svaka županija ima točno jednu takvu organizaciju s barem jednim
     * službenikom (W5). Ministarstvo ({@code IOT_MINTS}, nadležno za cijelu državu, 35 korisnika) ne
     * dobiva obavijest za svaki novi objekt (plan T8/T9 § 2: službenici nadležne županije).
     */
    List<Long> findCountyRecipients(long countyId, int limit) {
        return jdbc.queryForList("""
                SELECT u.id
                  FROM str.application_user u
                  JOIN str.internal_organization io ON io.id = u.internal_organization_id AND io.active = TRUE
                  JOIN str.codebook_element ce ON ce.id = io.type_id AND ce.code = 'IOT_ZUPANIJA'
                 WHERE u.active = TRUE
                   AND u.internal = TRUE
                   AND EXISTS (SELECT 1
                                 FROM str.organizational_unit ou
                                 JOIN str.settlement_jurisdiction sj ON sj.organizational_unit_id = ou.id
                                                                    AND sj.active = TRUE
                                 JOIN str.settlement s ON s.id = sj.settlement_id
                                 JOIN str.municipality m ON m.id = s.municipality_id
                                WHERE ou.internal_organization_id = io.id
                                  AND ou.active = TRUE
                                  AND m.county_id = ?)
                 ORDER BY u.id
                 LIMIT ?
                """, Long.class, countyId, limit);
    }

    /**
     * Nepročitana obavijest jednom korisniku; {@code podaci} je JSON tekst. {@code objektTip} i
     * {@code objektId} su {@code NULL} za novi objekt (nema zahtjeva).
     */
    void insertNotification(long applicationUserId, String vrsta, String tekst, String objektTip,
                            Long objektId, String podaci, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO str.korisnicka_obavijest
                  (application_user_id, vrsta, tekst, objekt_tip, objekt_id, podaci, stvoreno)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, applicationUserId, vrsta, tekst,
                // Tip izričito: za novi objekt su NULL, a NULL bez tipa driver ne mora razriješiti.
                new SqlParameterValue(Types.VARCHAR, objektTip),
                new SqlParameterValue(Types.BIGINT, objektId),
                podaci, now);
    }

    private static Integer parseSifra(Object externalCode) {
        if (externalCode == null) {
            return null;
        }
        try {
            return Integer.parseInt(externalCode.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
