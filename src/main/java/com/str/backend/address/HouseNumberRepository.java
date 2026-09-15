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

    /**
     * Naziv katastarske općine po kućnom broju.
     *
     * <p>{@code ar_address} nosi samo {@code kat_opcina_id}; naziv živi u
     * {@code eturizam_test.ka_katastar_ko(id, naziv)}. Ranije je ovaj upit čitao
     * {@code ar_address.kat_opcina_naziv} — stupac koji postoji <b>isključivo u lokalnom mocku</b>
     * (denormaliziran u changesetu 109), pa bi na pravoj bazi pucao s „column does not exist" čim
     * se {@code app.cadastral.enabled} upali. Mjereno na predprodukciji: u uzorku od 200 000
     * redaka svi imaju {@code kat_opcina_id} i svi se razrješavaju (0 promašaja).
     *
     * <p>{@code LEFT JOIN}, a ne {@code JOIN}: nerazrješiva općina smije značiti „nema podatka",
     * ne „nema kućnog broja" — inače bi jedan loš redak izbacio adresu s popisa.
     */
    @Query(value = """
            SELECT ad.id       AS id,
                   ko.naziv    AS katOpcinaNaziv
            FROM eturizam_test.ar_address ad
            LEFT JOIN eturizam_test.ka_katastar_ko ko ON ko.id = ad.kat_opcina_id
            WHERE ad.ulica_id = :streetId
            """, nativeQuery = true)
    List<KatOpcinaRow> findKatOpcinaByStreetId(@Param("streetId") Long streetId);

    /**
     * Katastar jednog kućnog broja, s nazivom ulice i brojem za provjeru da redak pripada adresi
     * iz zahtjeva — v. {@link CadastreResolver}.
     *
     * <p>Ide po primarnim ključevima cijelim putem ({@code ar_address_pkey}, {@code ar_ulice_pkey},
     * {@code ka_katastar_ko_pkey}). Plan na CDU bazi 15.09.2026.: trošak ~25, bez skeniranja
     * 1,68 M redaka. {@code JOIN} na ulicu je siguran — u uzorku od 100 000 redaka svaki kućni
     * broj ima ulicu, a kućni broj ponuđen kroz autocomplete ima je po konstrukciji.
     * {@code ka_katastar_ko.id} je primarni ključ (3484 retka, 3484 različita id-a), pa rezultat
     * nikad nema dva retka.
     *
     * <p>{@code status} se namjerno ne filtrira: autocomplete ({@link #findByStreetIdOrderByName})
     * ga ne filtrira, pa bi filtar ovdje odbio kućni broj koji je korisniku upravo ponuđen.
     */
    @Query(value = """
            SELECT ad.broj        AS broj,
                   u.naziv_ulice  AS nazivUlice,
                   ad.kc_broj     AS kcBroj,
                   ko.naziv       AS katOpcinaNaziv
            FROM eturizam_test.ar_address ad
            JOIN eturizam_test.ar_ulice u             ON u.id  = ad.ulica_id
            LEFT JOIN eturizam_test.ka_katastar_ko ko ON ko.id = ad.kat_opcina_id
            WHERE ad.id = :id
            """, nativeQuery = true)
    Optional<CadastreRow> findCadastreById(@Param("id") long id);

    interface CadastreRow {
        String getBroj();
        String getNazivUlice();
        String getKcBroj();
        String getKatOpcinaNaziv();
    }

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
