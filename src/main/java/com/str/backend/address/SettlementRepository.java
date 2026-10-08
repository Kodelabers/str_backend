package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

@Transactional(readOnly = true)
public interface SettlementRepository extends JpaRepository<SettlementEntity, Long> {

    interface SettlementProjection {
        Long getId();
        String getName();
        String getPostalCode();
    }

    /** Redak sirovog spoja: naselje × poštanski broj istog imena, sa županijama za sužavanje. */
    interface SettlementPostalRow extends SettlementProjection, PostalCodes.Candidate {
    }

    /**
     * Naselja općine, po jedan redak za svaki poštanski broj naselja (frontend ih deduplicira).
     * Brojevi su suženi na županiju naselja, a kad se nijedan ne poklopi, ostaju svi brojevi po
     * imenu, kao prije — v. {@link PostalCodes#preferSameCounty}.
     */
    default List<SettlementProjection> findByMunicipalityIdOrderByName(Long municipalityId, String q) {
        return findWithPostalCandidates(municipalityId, q).stream()
                .collect(Collectors.groupingBy(SettlementPostalRow::getId, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .flatMap(rows -> PostalCodes.preferSameCounty(rows).stream())
                .map(SettlementProjection.class::cast)
                .toList();
    }

    /**
     * Sirovi spoj, bez sužavanja po županiji. {@code postanski_brojevi} se veže samo po imenu
     * naselja, jer drugog ključa nema. Županije se uspoređuju u Javi
     * ({@link #findByMunicipalityIdOrderByName}), ne u SQL-u: {@code LOWER('Ž')} u PostgreSQL-u
     * ovisi o {@code LC_CTYPE} baze, a format {@code postanski_brojevi.zupanija} na stvarnoj bazi
     * nije provjeren. {@code LEFT JOIN} na županiju: naselje bez nje ostaje na popisu, samo bez
     * sužavanja.
     */
    @Query(value = """
            SELECT n.id AS id,
                   n.na_ime AS name,
                   p.broj_pu AS "postalCode",
                   p.zupanija AS "postalCounty",
                   z.zu_ime AS county
            FROM rpj_dgu.naselja n
            JOIN rpj_dgu.gradovi_i_opcine g ON g.jls_mb = LPAD(n.jls_mb::text, 5, '0')
            LEFT JOIN rpj_dgu.zupanije z ON z.zu_rb = g.zu_rb
            LEFT JOIN rpj_dgu.postanski_brojevi p ON LOWER(p.naselje) = LOWER(n.na_ime)
            WHERE g.id = :municipalityId
              AND (CAST(:q AS text) IS NULL OR LOWER(n.na_ime) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')))
            ORDER BY n.na_ime, n.id, p.broj_pu
            """, nativeQuery = true)
    List<SettlementPostalRow> findWithPostalCandidates(@Param("municipalityId") Long municipalityId,
                                                       @Param("q") String q);
}
