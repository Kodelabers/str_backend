package com.str.backend.str;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface StrFacilityRepository extends JpaRepository<StrFacilityEntity, Long> {

    long countByActiveTrue();

    /*
     * ---------------------------------------------------------------------------------
     * Popis objekata iznajmljivača za NIAS dashboard.
     *
     * Join-mapa je prepisana iz definicije view-a str.vw_src_facility_actual na dev-u, ali
     * sam view se NE koristi: njegov f_active CTE ima created_by <> 'optimit', čime izbacuje
     * sve migrirane objekte (1124 od 245.044 — za stvarnog iznajmljivača vraća 0 redaka) i
     * izvršava se 1,2 s jer agregira cijeli registar.
     *
     * Dedup ide po coalesce(system_uuid, document.business_case_id), kako je eTurizam
     * predložio, ali samo nad redovima traženog OIB-a — globalni dedup tjera seq scan cijelog
     * facility + document (102 ms po pozivu neovisno o iznajmljivaču). Objekt koji je u
     * međuvremenu prenesen na drugog vlasnika hvata NOT EXISTS na system_uuid.
     *
     * facility.active se filtrira NAKON dedupa: unutar njega bi objekt čiji je najnoviji
     * zapis neaktivan "oživio" kroz stariji aktivni red. historical se ne filtrira — 74.177
     * redaka s historical = true preživi dedup, a najveća skupina ima NULL.
     *
     * Skalarni podqueryji u JOIN uvjetima (facility_type, subject_address) sprječavaju
     * multiplikaciju redaka, pa su LIMIT/OFFSET i count točni.
     * ---------------------------------------------------------------------------------
     */

    interface FacilityListingRow {
        Long getFacilityId();
        String getName();
        String getTypeCode();
        String getSubtypeCode();
        String getSubtypeName();
        String getCategoryName();
        String getStatusName();
        String getRegistrationNumber();
        String getCountyName();
        String getMunicipalityName();
        String getSettlementName();
        String getStreetName();
        String getHouseNumber();
        String getPostalCode();
        String getFullAddress();
        Integer getBeds();
        Integer getAuxiliaryBeds();
    }

    String LISTING_FROM = """
             FROM (SELECT f.id AS fid,
                          row_number() OVER (
                              PARTITION BY coalesce(cast(f.system_uuid AS varchar),
                                                    cast(d.business_case_id AS varchar),
                                                    'facility-' || cast(f.id AS varchar))
                              ORDER BY f.id DESC) AS rnk
                     FROM str.facility f
                     LEFT JOIN str.document d       ON d.id  = f.document_id
                     JOIN str.subject_version sv    ON sv.id = f.subject_version_id
                     JOIN str.subject s             ON s.id  = sv.subject_id
                    WHERE s.jips = :oib) r
             JOIN str.facility f ON f.id = r.fid
             LEFT JOIN str.facility_type ft
                    ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                                 WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
             LEFT JOIN str.codebook_element c_type ON c_type.id = ft.type_id
             LEFT JOIN str.codebook_element c_sub  ON c_sub.id  = ft.sub_type_id
             LEFT JOIN str.codebook_element c_cat  ON c_cat.id  = f.category_id
             LEFT JOIN str.codebook_element c_st   ON c_st.id   = f.business_status_id
             LEFT JOIN str.address a
                    ON a.id = CASE WHEN f.same_address_subject = true
                                   THEN (SELECT max(x.address_id) FROM str.subject_address x
                                          WHERE x.subject_version_id = f.subject_version_id
                                            AND coalesce(x.active, true) = true)
                                   ELSE f.address_id END
             LEFT JOIN str.county co        ON co.id  = a.county_id
             LEFT JOIN str.municipality mu  ON mu.id  = a.municipality_id
             LEFT JOIN str.settlement se    ON se.id  = a.settlement_id
             LEFT JOIN str.street stt       ON stt.id = a.street_id
             LEFT JOIN str.house_number hn  ON hn.id  = a.house_number_id
             WHERE r.rnk = 1
               AND f.active = true
               AND c_sub.code IN (:codes)
               AND NOT EXISTS (SELECT 1 FROM str.facility f2
                                WHERE f2.system_uuid = f.system_uuid AND f2.id > f.id)
            """;

    @Query(value = """
            SELECT f.id                                    AS facilityId,
                   f.name                                  AS name,
                   c_type.code                             AS typeCode,
                   c_sub.code                              AS subtypeCode,
                   c_sub.name                              AS subtypeName,
                   c_cat.name                              AS categoryName,
                   c_st.name                               AS statusName,
                   f.registration_number                   AS registrationNumber,
                   coalesce(co.name, a.county)             AS countyName,
                   coalesce(mu.name, a.municipality)       AS municipalityName,
                   coalesce(se.name, a.settlement)         AS settlementName,
                   coalesce(stt.name, a.street)            AS streetName,
                   coalesce(hn.name, a.house_number)       AS houseNumber,
                   coalesce(a.postal_code, se.postal_code) AS postalCode,
                   a.full_address                          AS fullAddress,
                   coalesce(
                       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
                          JOIN str.codebook_element ce ON ce.id = fc.type_id
                         WHERE fc.facility_id = f.id AND fc.active = true
                           AND ce.code = 'CAT_BROJ_KREVETA'),
                       (SELECT sum(fuc.quantity) FROM str.facility_unit fu
                          JOIN str.facility_unit_capacity fuc
                            ON fuc.facility_unit_id = fu.id AND fuc.active = true
                          JOIN str.codebook_element ce2 ON ce2.id = fuc.type_id
                         WHERE fu.facility_id = f.id AND fu.active = true
                           AND ce2.code = 'CAT_BROJ_KREVETA')
                   )                                       AS beds,
                   (SELECT sum(fc2.quantity) FROM str.facility_capacity fc2
                      JOIN str.codebook_element ce3 ON ce3.id = fc2.type_id
                     WHERE fc2.facility_id = f.id AND fc2.active = true
                       AND ce3.code = 'CAT_BROJ_POM_KREVETA') AS auxiliaryBeds
            """ + LISTING_FROM + """
             ORDER BY f.id
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<FacilityListingRow> findListingByOib(@Param("oib") String oib,
                                              @Param("codes") Collection<String> codes,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    @Query(value = "SELECT count(*)" + LISTING_FROM, nativeQuery = true)
    long countListingByOib(@Param("oib") String oib, @Param("codes") Collection<String> codes);

    interface FacilityOwnershipRow {
        String getOib();
        String getSubtypeCode();
        Integer getBeds();
        Boolean getActive();
    }

    /**
     * Podaci potrebni da se za zahtjev koji nosi {@code facilityId} provjeri da objekt
     * pripada podnositelju i da poslana vrsta / kapacitet odgovaraju eTurizmu.
     *
     * <p>{@code subject.active} se namjerno <strong>ne</strong> filtrira: objekt vodi na točno
     * jednu verziju subjekta, pa filtar ne može spriječiti multiplikaciju — može samo sakriti
     * objekt čiji je zapis subjekta u međuvremenu nadjačan novijim. Tada bi legitiman handoff iz
     * tuStarta bio odbijen kao „objekt ne postoji". Identitet nosi {@code jips}, ne zastavica.
     *
     * <p>{@code coalesce(active, true)} jer {@code facility_type.active} u eTurizmu smije biti
     * NULL (njihov vlastiti view ga uopće ne filtrira); {@code active = true} bi za takve zapise
     * izgubio vrstu i provjera bi se tiho preskočila.
     */
    @Query(value = """
            SELECT s.jips      AS oib,
                   c_sub.code  AS subtypeCode,
                   f.active    AS active,
                   (SELECT sum(fc.quantity) FROM str.facility_capacity fc
                      JOIN str.codebook_element ce ON ce.id = fc.type_id
                     WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
                       AND ce.code = 'CAT_BROJ_KREVETA') AS beds
            FROM str.facility f
            JOIN str.subject_version sv ON sv.id = f.subject_version_id
            JOIN str.subject s          ON s.id  = sv.subject_id
            LEFT JOIN str.facility_type ft
                   ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                                WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
            LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
            WHERE f.id = :facilityId
            ORDER BY s.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<FacilityOwnershipRow> findOwnership(@Param("facilityId") long facilityId);

    /**
     * Upisuje dodijeljeni RB natrag u eTurizam registar, po dogovoru s tuStartom.
     *
     * <p>Ovo je <strong>jedini</strong> put pisanja u shemu {@code str}, koja je inače
     * read-only za ovaj servis. Namjerno je izveden kao uski native UPDATE nad jednom
     * kolonom umjesto kroz entitet: {@link StrFacilityEntity} ostaje {@code @Immutable},
     * pa nijedan drugi tok ne može slučajno perzistirati promjenu u tuđu tablicu.
     *
     * <p>{@code WHERE registration_number IS NULL} sprječava prepisivanje RB-a koji je
     * objekt već dobio (ponovni pokušaj, ručni upis u eTurizmu) — write-back je time
     * idempotentan i ne može tiho pregaziti tuđi podatak.
     *
     * @return broj ažuriranih redaka: 1 kad je upis prošao, 0 kad objekt ne postoji ili
     * već ima RB
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE str.facility
               SET registration_number = :rn
             WHERE id = :facilityId
               AND registration_number IS NULL
            """, nativeQuery = true)
    int writeBackRegistrationNumber(@Param("facilityId") long facilityId, @Param("rn") String rn);
}
