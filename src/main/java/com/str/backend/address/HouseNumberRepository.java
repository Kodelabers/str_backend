package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface HouseNumberRepository extends JpaRepository<HouseNumberEntity, Long> {

    @Query("""
            SELECT h FROM HouseNumberEntity h
            WHERE h.ulicaId = :streetId
              AND (:q = '' OR LOWER(h.name) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY h.name
            """)
    List<HouseNumberEntity> findByStreetIdOrderByName(@Param("streetId") Long streetId, @Param("q") String q);

    interface KatOpcinaRow {
        Long getId();
        String getKatOpcinaNaziv();
    }

    @Query(value = """
            SELECT id, kat_opcina_naziv
            FROM eturizam_test.ar_address
            WHERE ulica_id = :streetId
            """, nativeQuery = true)
    List<KatOpcinaRow> findKatOpcinaByStreetId(@Param("streetId") Long streetId);

    interface LessorAddressProjection {
        String getStreet();
        String getStreetNumber();
        String getSettlement();
        String getCounty();
    }

    @Query(value = """
            SELECT u.naziv_ulice          AS street,
                   a.broj                 AS streetNumber,
                   n.na_ime               AS settlement,
                   z.zu_ime               AS county
            FROM eturizam_test.ar_address a
            JOIN eturizam_test.ar_ulice         u ON u.id      = a.ulica_id
            JOIN rpj_dgu.naselja                n ON n.na_mb   = u.naselje_id
            JOIN rpj_dgu.gradovi_i_opcine       g ON g.jls_mb  = LPAD(n.jls_mb::text, 5, '0')
            JOIN rpj_dgu.zupanije               z ON z.zu_rb   = g.zu_rb
            WHERE a.id = :id
            """, nativeQuery = true)
    Optional<LessorAddressProjection> resolveFullAddress(@Param("id") Long id);

    interface FullAddressProjection {
        String getCounty();
        String getMunicipality();
        String getSettlement();
        String getStreet();
        String getStreetNumber();
    }

    @Query(value = """
            SELECT z.zu_ime               AS county,
                   g.jls_ime              AS municipality,
                   n.na_ime               AS settlement,
                   u.naziv_ulice          AS street,
                   a.broj                 AS streetNumber
            FROM eturizam_test.ar_address a
            JOIN eturizam_test.ar_ulice         u ON u.id      = a.ulica_id
            JOIN rpj_dgu.naselja                n ON n.na_mb   = u.naselje_id
            JOIN rpj_dgu.gradovi_i_opcine       g ON g.jls_mb  = LPAD(n.jls_mb::text, 5, '0')
            JOIN rpj_dgu.zupanije               z ON z.zu_rb   = g.zu_rb
            WHERE a.id::text = :code
            """, nativeQuery = true)
    Optional<FullAddressProjection> resolveAddressHierarchy(@Param("code") String code);
}
