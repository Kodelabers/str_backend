package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface SettlementRepository extends JpaRepository<SettlementEntity, Long> {

    interface SettlementProjection {
        Long getId();
        String getName();
        String getPostalCode();
    }

    @Query(value = """
            SELECT n.id AS id,
                   n.na_ime AS name,
                   p.broj_pu AS "postalCode"
            FROM rpj_dgu.naselja n
            JOIN rpj_dgu.gradovi_i_opcine g ON g.jls_mb = LPAD(n.jls_mb::text, 5, '0')
            LEFT JOIN rpj_dgu.postanski_brojevi p ON LOWER(p.naselje) = LOWER(n.na_ime)
            WHERE g.id = :municipalityId
              AND (CAST(:q AS text) IS NULL OR LOWER(n.na_ime) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')))
            ORDER BY n.na_ime
            """, nativeQuery = true)
    List<SettlementProjection> findByMunicipalityIdOrderByName(@Param("municipalityId") Long municipalityId,
                                                               @Param("q") String q);
}
