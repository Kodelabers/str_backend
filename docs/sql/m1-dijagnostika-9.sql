-- =====================================================================================================
-- M-1 · dijagnostika, 9. krug (CDU test): STVARNI upiti iz aplikacije — ispravnost i brzina
--
-- Tekst upita je IZVUČEN iz kompiliranih @Query anotacija (StrFacilityRepository), ne prepisan:
--   m9_popis   = findListingByOib   ($1 oznaka iznajmljivača, $2 veličina stranice, $3 pomak — u objektima)
--   m9_broj    = countListingByOib  ($1 oznaka iznajmljivača)
--   m9_vlasnik = findOwnership      ($1 facility.id)
-- Jedina razlika od aplikacije: :oib je zamijenjen podupitom na TEMP tablicu (da se OIB ne
-- ispisuje), a :codes popisom šifara FS_* (aplikacija ga veže kao 4 parametra).
--
-- SAMO ČITANJE nad str; stvara TEMP tablicu i prepared statemente (nestaju sa sesijom).
-- DBeaver: kad pita za parametre ($1, $2, $3) — klikni „Ignore" (to su parametri PREPARE-a).
-- Ako se skripta ponavlja u istoj sesiji: prvo DEALLOCATE m9_popis; DEALLOCATE m9_broj; DEALLOCATE m9_vlasnik;
-- =====================================================================================================

DROP TABLE IF EXISTS pg_temp.t9_oibi;

CREATE TEMP TABLE t9_oibi AS
SELECT x.oznaka, x.oib
  FROM (VALUES ('test-1', '06756460531'), ('test-2', '12312312316'), ('test-3', '98765432106')) x(oznaka, oib)
UNION ALL
SELECT 'top-migrirani', top.oib
  FROM (SELECT s.jips AS oib
          FROM str.facility f
          JOIN str.document d         ON d.id  = f.document_id
          JOIN str.business_case bc   ON bc.id = d.business_case_id
          JOIN str.subject_version sv ON sv.id = bc.subject_version_id
          JOIN str.subject s          ON s.id  = sv.subject_id
         WHERE f.active AND f.created_by = 'optimit' AND coalesce(f.historical, false) = false
         GROUP BY s.jips
         ORDER BY count(*) DESC
         LIMIT 1) top;

PREPARE m9_popis(text, int, bigint) AS
SELECT f.id                                    AS facilityId,
       s.su                                    AS systemUuid,
       s.verificiran                           AS verified,
       s.ukupno_objekata                       AS totalObjects,
       s.ukupno_jedinica                       AS totalUnits,
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
       f.email                                 AS contactEmail,
       f.phone                                 AS contactPhone,
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
       coalesce(
           (SELECT sum(fc2.quantity) FROM str.facility_capacity fc2
              JOIN str.codebook_element ce3 ON ce3.id = fc2.type_id
             WHERE fc2.facility_id = f.id AND fc2.active = true
               AND ce3.code = 'CAT_BROJ_POM_KREVETA'),
           (SELECT sum(fuc2.quantity) FROM str.facility_unit fu2
              JOIN str.facility_unit_capacity fuc2
                ON fuc2.facility_unit_id = fu2.id AND fuc2.active = true
              JOIN str.codebook_element ce4 ON ce4.id = fuc2.type_id
             WHERE fu2.facility_id = f.id AND fu2.active = true
               AND ce4.code = 'CAT_BROJ_POM_KREVETA')
       )                                       AS auxiliaryBeds
  FROM (SELECT u.*,
               max(u.redni) OVER () AS ukupno_objekata,
               count(*) OVER ()     AS ukupno_jedinica
          FROM (SELECT p.*,
                       dense_rank() OVER (ORDER BY p.obj_verificiran DESC, p.obj_prvi_id) AS redni
                  FROM (SELECT q.*,
                               bool_or(q.verificiran) OVER (PARTITION BY q.su) AS obj_verificiran,
                               min(q.id) OVER (PARTITION BY q.su)              AS obj_prvi_id
                          FROM (SELECT r.id, r.su, r.verificiran, r.bc_sv
  FROM(SELECT a.*,
        dense_rank() OVER (PARTITION BY a.su
                           ORDER BY a.predmet_zadnji DESC NULLS LAST, a.bc_id DESC) AS predmet_rang
   FROM (SELECT x.*,
                max(x.created_date) OVER (PARTITION BY x.su, x.bc_id) AS predmet_zadnji
           FROM (SELECT id,
                        max(su)               AS su,
                        bool_and(verificiran) AS verificiran,
                        max(created_date)     AS created_date,
                        max(bc_id)            AS bc_id,
                        max(bc_sv)            AS bc_sv
                   FROM (SELECT facility.id,
                                cast(facility.system_uuid AS varchar(64)) AS su,
                                facility.created_date,
                                facility.created_by <> 'optimit'          AS verificiran,
                                business_case.id                           AS bc_id,
                                business_case.subject_version_id           AS bc_sv
                           FROM(SELECT DISTINCT f.system_uuid
   FROM str.subject s
   JOIN str.subject_version sv ON sv.subject_id = s.id
   JOIN str.business_case bc   ON bc.subject_version_id = sv.id
   JOIN str.document d         ON d.business_case_id = bc.id
   JOIN str.facility f         ON f.document_id = d.id
  WHERE s.jips = (SELECT oib FROM t9_oibi WHERE oznaka = $1)
    AND f.system_uuid IS NOT NULL) o
                           JOIN str.facility facility
                             ON facility.system_uuid = o.system_uuid
                           JOIN str.document document
                             ON facility.document_id = document.id AND document.active = true
                           JOIN str.sif_podvrsta_dokumenta document_subtype
                             ON document_subtype.code = document.subtype_code
                           JOIN str.sif_vrsta_dokumenata document_type
                             ON document_type.code = document_subtype.vrsta_dokumenata_code
                            AND document_type.code IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU')
                           JOIN str.business_case business_case
                             ON document.business_case_id = business_case.id
                            AND business_case.active = true
                           LEFT JOIN str.business_case_verification verification_source
                             ON business_case.id = verification_source.unverified_business_case_id
                           LEFT JOIN str.codebook_element verification_source_status
                             ON verification_source.status_id = verification_source_status.id
                           LEFT JOIN str.business_case_verification verification_target
                             ON business_case.id = verification_target.verified_business_case_id
                           LEFT JOIN str.codebook_element verification_target_status
                             ON verification_target.status_id = verification_target_status.id
                           LEFT JOIN str.codebook_element bc_status_ce
                             ON bc_status_ce.id = business_case.status_type_id
                          WHERE facility.active = true
                            AND facility.created_by IS NOT NULL
                            AND (facility.historical IS NULL OR facility.historical = false)
                            AND (verification_source.id IS NULL
                                 OR verification_source_status.code = 'BCVS_U_IZRADI')
                            AND (verification_target.id IS NULL
                                 OR verification_target_status.code = 'BCVS_ZAVRSENA')
                            AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                                         WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
                            AND (facility.created_by = 'optimit'
                                 OR (bc_status_ce.code = 'BCST_RJES_IZVRSNO'
                                     AND document.execution_date IS NOT NULL
                                     AND document.execution_date < now()))
                        ) redovi
                  GROUP BY id
                 HAVING count(su) = 1) x) a)
   r
  JOIN str.facility f ON f.id = r.id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 WHERE r.predmet_rang = 1
   AND r.bc_sv IN (SELECT sv.id
                     FROM str.subject s
                     JOIN str.subject_version sv ON sv.subject_id = s.id
                    WHERE s.jips = (SELECT oib FROM t9_oibi WHERE oznaka = $1))
   AND c_st.code = 'FBS_ACTIVE'
   AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')
                               ) q) p) u) s
  JOIN str.facility f ON f.id = s.id
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
                               WHERE x.subject_version_id = s.bc_sv
                                 AND coalesce(x.active, true) = true)
                        ELSE f.address_id END
  LEFT JOIN str.county co        ON co.id  = a.county_id
  LEFT JOIN str.municipality mu  ON mu.id  = a.municipality_id
  LEFT JOIN str.settlement se    ON se.id  = a.settlement_id
  LEFT JOIN str.street stt       ON stt.id = a.street_id
  LEFT JOIN str.house_number hn  ON hn.id  = a.house_number_id
 WHERE s.redni > $3
   AND s.redni <= $3 + $2
 ORDER BY s.redni, f.id
;

PREPARE m9_broj(text) AS
SELECT count(DISTINCT q.su) AS objects,
       count(*)             AS units
  FROM (SELECT r.id, r.su, r.verificiran, r.bc_sv
  FROM(SELECT a.*,
        dense_rank() OVER (PARTITION BY a.su
                           ORDER BY a.predmet_zadnji DESC NULLS LAST, a.bc_id DESC) AS predmet_rang
   FROM (SELECT x.*,
                max(x.created_date) OVER (PARTITION BY x.su, x.bc_id) AS predmet_zadnji
           FROM (SELECT id,
                        max(su)               AS su,
                        bool_and(verificiran) AS verificiran,
                        max(created_date)     AS created_date,
                        max(bc_id)            AS bc_id,
                        max(bc_sv)            AS bc_sv
                   FROM (SELECT facility.id,
                                cast(facility.system_uuid AS varchar(64)) AS su,
                                facility.created_date,
                                facility.created_by <> 'optimit'          AS verificiran,
                                business_case.id                           AS bc_id,
                                business_case.subject_version_id           AS bc_sv
                           FROM(SELECT DISTINCT f.system_uuid
   FROM str.subject s
   JOIN str.subject_version sv ON sv.subject_id = s.id
   JOIN str.business_case bc   ON bc.subject_version_id = sv.id
   JOIN str.document d         ON d.business_case_id = bc.id
   JOIN str.facility f         ON f.document_id = d.id
  WHERE s.jips = (SELECT oib FROM t9_oibi WHERE oznaka = $1)
    AND f.system_uuid IS NOT NULL) o
                           JOIN str.facility facility
                             ON facility.system_uuid = o.system_uuid
                           JOIN str.document document
                             ON facility.document_id = document.id AND document.active = true
                           JOIN str.sif_podvrsta_dokumenta document_subtype
                             ON document_subtype.code = document.subtype_code
                           JOIN str.sif_vrsta_dokumenata document_type
                             ON document_type.code = document_subtype.vrsta_dokumenata_code
                            AND document_type.code IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU')
                           JOIN str.business_case business_case
                             ON document.business_case_id = business_case.id
                            AND business_case.active = true
                           LEFT JOIN str.business_case_verification verification_source
                             ON business_case.id = verification_source.unverified_business_case_id
                           LEFT JOIN str.codebook_element verification_source_status
                             ON verification_source.status_id = verification_source_status.id
                           LEFT JOIN str.business_case_verification verification_target
                             ON business_case.id = verification_target.verified_business_case_id
                           LEFT JOIN str.codebook_element verification_target_status
                             ON verification_target.status_id = verification_target_status.id
                           LEFT JOIN str.codebook_element bc_status_ce
                             ON bc_status_ce.id = business_case.status_type_id
                          WHERE facility.active = true
                            AND facility.created_by IS NOT NULL
                            AND (facility.historical IS NULL OR facility.historical = false)
                            AND (verification_source.id IS NULL
                                 OR verification_source_status.code = 'BCVS_U_IZRADI')
                            AND (verification_target.id IS NULL
                                 OR verification_target_status.code = 'BCVS_ZAVRSENA')
                            AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                                         WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
                            AND (facility.created_by = 'optimit'
                                 OR (bc_status_ce.code = 'BCST_RJES_IZVRSNO'
                                     AND document.execution_date IS NOT NULL
                                     AND document.execution_date < now()))
                        ) redovi
                  GROUP BY id
                 HAVING count(su) = 1) x) a)
   r
  JOIN str.facility f ON f.id = r.id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 WHERE r.predmet_rang = 1
   AND r.bc_sv IN (SELECT sv.id
                     FROM str.subject s
                     JOIN str.subject_version sv ON sv.subject_id = s.id
                    WHERE s.jips = (SELECT oib FROM t9_oibi WHERE oznaka = $1))
   AND c_st.code = 'FBS_ACTIVE'
   AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')
       ) q
;

PREPARE m9_vlasnik(bigint) AS
SELECT s.jips      AS oib,
       c_sub.code  AS subtypeCode,
       f.active    AS active,
       c_st.code   AS businessStatusCode,
       coalesce(r.predmet_rang = 1, false) AS current,
       f.name      AS name,
       sv.name     AS ownerName,
       btrim(coalesce(sv.first_name,'') || ' ' || coalesce(sv.last_name,'')) AS ownerFullName,
       coalesce(co.name, a.county)       AS countyName,
       coalesce(mu.name, a.municipality) AS municipalityName,
       coalesce(se.name, a.settlement)   AS settlementName,
       coalesce(stt.name, a.street)      AS streetName,
       coalesce(hn.name, a.house_number) AS houseNumber,
       coalesce(a.postal_code, se.postal_code) AS postalCode,
       f.email     AS contactEmail,
       f.phone     AS contactPhone,
       f.document_id AS documentId,
       coalesce(
           (SELECT sum(fc.quantity) FROM str.facility_capacity fc
              JOIN str.codebook_element ce ON ce.id = fc.type_id
             WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
               AND ce.code = 'CAT_BROJ_KREVETA'),
           (SELECT sum(fuc.quantity) FROM str.facility_unit fu
              JOIN str.facility_unit_capacity fuc
                ON fuc.facility_unit_id = fu.id AND coalesce(fuc.active, true) = true
              JOIN str.codebook_element ce2 ON ce2.id = fuc.type_id
             WHERE fu.facility_id = f.id AND coalesce(fu.active, true) = true
               AND ce2.code = 'CAT_BROJ_KREVETA')
       ) AS beds,
       coalesce(
           (SELECT sum(fc.quantity) FROM str.facility_capacity fc
              JOIN str.codebook_element ce ON ce.id = fc.type_id
             WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
               AND ce.code = 'CAT_BROJ_POM_KREVETA'),
           (SELECT sum(fuc.quantity) FROM str.facility_unit fu
              JOIN str.facility_unit_capacity fuc
                ON fuc.facility_unit_id = fu.id AND coalesce(fuc.active, true) = true
              JOIN str.codebook_element ce2 ON ce2.id = fuc.type_id
             WHERE fu.facility_id = f.id AND coalesce(fu.active, true) = true
               AND ce2.code = 'CAT_BROJ_POM_KREVETA')
       ) AS auxiliaryBeds
FROM str.facility f
JOIN str.document d         ON d.id  = f.document_id
JOIN str.business_case bc   ON bc.id = d.business_case_id
JOIN str.subject_version sv ON sv.id = bc.subject_version_id
JOIN str.subject s          ON s.id  = sv.subject_id
LEFT JOIN(SELECT a.*,
        dense_rank() OVER (PARTITION BY a.su
                           ORDER BY a.predmet_zadnji DESC NULLS LAST, a.bc_id DESC) AS predmet_rang
   FROM (SELECT x.*,
                max(x.created_date) OVER (PARTITION BY x.su, x.bc_id) AS predmet_zadnji
           FROM (SELECT id,
                        max(su)               AS su,
                        bool_and(verificiran) AS verificiran,
                        max(created_date)     AS created_date,
                        max(bc_id)            AS bc_id,
                        max(bc_sv)            AS bc_sv
                   FROM (SELECT facility.id,
                                cast(facility.system_uuid AS varchar(64)) AS su,
                                facility.created_date,
                                facility.created_by <> 'optimit'          AS verificiran,
                                business_case.id                           AS bc_id,
                                business_case.subject_version_id           AS bc_sv
                           FROM(SELECT fx.system_uuid FROM str.facility fx
  WHERE fx.id = $1 AND fx.system_uuid IS NOT NULL) o
                           JOIN str.facility facility
                             ON facility.system_uuid = o.system_uuid
                           JOIN str.document document
                             ON facility.document_id = document.id AND document.active = true
                           JOIN str.sif_podvrsta_dokumenta document_subtype
                             ON document_subtype.code = document.subtype_code
                           JOIN str.sif_vrsta_dokumenata document_type
                             ON document_type.code = document_subtype.vrsta_dokumenata_code
                            AND document_type.code IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU')
                           JOIN str.business_case business_case
                             ON document.business_case_id = business_case.id
                            AND business_case.active = true
                           LEFT JOIN str.business_case_verification verification_source
                             ON business_case.id = verification_source.unverified_business_case_id
                           LEFT JOIN str.codebook_element verification_source_status
                             ON verification_source.status_id = verification_source_status.id
                           LEFT JOIN str.business_case_verification verification_target
                             ON business_case.id = verification_target.verified_business_case_id
                           LEFT JOIN str.codebook_element verification_target_status
                             ON verification_target.status_id = verification_target_status.id
                           LEFT JOIN str.codebook_element bc_status_ce
                             ON bc_status_ce.id = business_case.status_type_id
                          WHERE facility.active = true
                            AND facility.created_by IS NOT NULL
                            AND (facility.historical IS NULL OR facility.historical = false)
                            AND (verification_source.id IS NULL
                                 OR verification_source_status.code = 'BCVS_U_IZRADI')
                            AND (verification_target.id IS NULL
                                 OR verification_target_status.code = 'BCVS_ZAVRSENA')
                            AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                                         WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
                            AND (facility.created_by = 'optimit'
                                 OR (bc_status_ce.code = 'BCST_RJES_IZVRSNO'
                                     AND document.execution_date IS NOT NULL
                                     AND document.execution_date < now()))
                        ) redovi
                  GROUP BY id
                 HAVING count(su) = 1) x) a)
       r ON r.id = f.id
LEFT JOIN str.facility_type ft
       ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                    WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
LEFT JOIN str.codebook_element c_st  ON c_st.id  = f.business_status_id
LEFT JOIN str.address a
       ON a.id = CASE WHEN f.same_address_subject = true
                      THEN (SELECT max(x.address_id) FROM str.subject_address x
                             WHERE x.subject_version_id = bc.subject_version_id
                               AND coalesce(x.active, true) = true)
                      ELSE f.address_id END
LEFT JOIN str.county co        ON co.id  = a.county_id
LEFT JOIN str.municipality mu  ON mu.id  = a.municipality_id
LEFT JOIN str.settlement se    ON se.id  = a.settlement_id
LEFT JOIN str.street stt       ON stt.id = a.street_id
LEFT JOIN str.house_number hn  ON hn.id  = a.house_number_id
WHERE f.id = $1
ORDER BY s.id DESC
LIMIT 1
;


-- ---------------------------------------------------------------------------------------------------
-- L1 · popis: u prvom retku totalObjects / totalUnits moraju biti kao u 8. krugu (K1):
--      test-1 38/38, test-2 213/215, test-3 23/23, top-migrirani 51/3741.
--      Dovoljno je poslati prvi redak svakog rezultata (stupci totalobjects, totalunits, verified).
-- ---------------------------------------------------------------------------------------------------
EXECUTE m9_popis('test-1', 20, 0);
EXECUTE m9_popis('test-2', 20, 0);
EXECUTE m9_popis('test-3', 20, 0);
EXECUTE m9_popis('top-migrirani', 20, 0);

-- ---------------------------------------------------------------------------------------------------
-- L2 · ukupno (za praznu stranicu) — mora biti isto kao L1
-- ---------------------------------------------------------------------------------------------------
EXECUTE m9_broj('test-1');
EXECUTE m9_broj('test-2');
EXECUTE m9_broj('test-3');
EXECUTE m9_broj('top-migrirani');

-- ---------------------------------------------------------------------------------------------------
-- L3 · vlasnički upit (claim / tuStart handoff) — poslati samo stupce current i businesstatuscode:
--      153049 migrirana jedinica test-1      → current = true
--      73     verificirana jedinica test-2   → current = true
--      19476  migrirana jedinica top-migr.   → current = true
--      243147 historical = true (1. krug Q5c) → current = false
--      241446 predmet u rješavanju (Q5c)     → current = false
-- ---------------------------------------------------------------------------------------------------
EXECUTE m9_vlasnik(153049);
EXECUTE m9_vlasnik(73);
EXECUTE m9_vlasnik(19476);
EXECUTE m9_vlasnik(243147);
EXECUTE m9_vlasnik(241446);

-- ---------------------------------------------------------------------------------------------------
-- L4 · brzina: prilagođeni plan, pa generički (JDBC nakon 5. izvršavanja). Poslati zadnje retke
--      svakog plana (Planning Time / Execution Time); cijeli plan samo ako je iznad 150 ms.
-- ---------------------------------------------------------------------------------------------------
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m9_popis('top-migrirani', 20, 0);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m9_popis('test-2', 20, 0);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m9_broj('top-migrirani');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m9_vlasnik(19476);

SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m9_popis('top-migrirani', 20, 0);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m9_popis('test-2', 20, 0);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m9_broj('top-migrirani');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m9_vlasnik(19476);
RESET plan_cache_mode;

-- ---------------------------------------------------------------------------------------------------
-- L5 · rubni slučaj pravila „najnoviji predmet": objekti čiji NAJNOVIJI aktualni predmet nosi
--      MANJE jedinica od nekog starijeg aktualnog predmeta. Ako novi predmet (npr. promjena podataka)
--      pokriva samo dio jedinica, pravilo bi ostale jedinice sakrilo. Očekivano 0 (ili objasnjivo).
--      Ista pravila kao aplikacija (RANGIRANE_JEDINICE_* iz StrFacilityRepository), nad cijelim
--      registrom — traje nekoliko sekundi.
-- ---------------------------------------------------------------------------------------------------
DROP TABLE IF EXISTS pg_temp.t9_rang;
CREATE TEMP TABLE t9_rang AS
SELECT r.su, r.bc_id, r.predmet_rang, r.verificiran
  FROM
(SELECT a.*,
        dense_rank() OVER (PARTITION BY a.su
                           ORDER BY a.predmet_zadnji DESC NULLS LAST, a.bc_id DESC) AS predmet_rang
   FROM (SELECT x.*,
                max(x.created_date) OVER (PARTITION BY x.su, x.bc_id) AS predmet_zadnji
           FROM (SELECT id,
                        max(su)               AS su,
                        bool_and(verificiran) AS verificiran,
                        max(created_date)     AS created_date,
                        max(bc_id)            AS bc_id,
                        max(bc_sv)            AS bc_sv
                   FROM (SELECT facility.id,
                                cast(facility.system_uuid AS varchar(64)) AS su,
                                facility.created_date,
                                facility.created_by <> 'optimit'          AS verificiran,
                                business_case.id                           AS bc_id,
                                business_case.subject_version_id           AS bc_sv
                           FROM       (SELECT DISTINCT system_uuid FROM str.facility WHERE system_uuid IS NOT NULL)
 o
                           JOIN str.facility facility
                             ON facility.system_uuid = o.system_uuid
                           JOIN str.document document
                             ON facility.document_id = document.id AND document.active = true
                           JOIN str.sif_podvrsta_dokumenta document_subtype
                             ON document_subtype.code = document.subtype_code
                           JOIN str.sif_vrsta_dokumenata document_type
                             ON document_type.code = document_subtype.vrsta_dokumenata_code
                            AND document_type.code IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU')
                           JOIN str.business_case business_case
                             ON document.business_case_id = business_case.id
                            AND business_case.active = true
                           LEFT JOIN str.business_case_verification verification_source
                             ON business_case.id = verification_source.unverified_business_case_id
                           LEFT JOIN str.codebook_element verification_source_status
                             ON verification_source.status_id = verification_source_status.id
                           LEFT JOIN str.business_case_verification verification_target
                             ON business_case.id = verification_target.verified_business_case_id
                           LEFT JOIN str.codebook_element verification_target_status
                             ON verification_target.status_id = verification_target_status.id
                           LEFT JOIN str.codebook_element bc_status_ce
                             ON bc_status_ce.id = business_case.status_type_id
                          WHERE facility.active = true
                            AND facility.created_by IS NOT NULL
                            AND (facility.historical IS NULL OR facility.historical = false)
                            AND (verification_source.id IS NULL
                                 OR verification_source_status.code = 'BCVS_U_IZRADI')
                            AND (verification_target.id IS NULL
                                 OR verification_target_status.code = 'BCVS_ZAVRSENA')
                            AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                                         WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
                            AND (facility.created_by = 'optimit'
                                 OR (bc_status_ce.code = 'BCST_RJES_IZVRSNO'
                                     AND document.execution_date IS NOT NULL
                                     AND document.execution_date < now()))
                        ) redovi
                  GROUP BY id
                 HAVING count(su) = 1) x) a)
       r;

WITH po_predmetu AS (
    SELECT su, bc_id, min(predmet_rang) AS rang, count(*) AS jedinica, bool_or(verificiran) AS verificiran
      FROM t9_rang GROUP BY su, bc_id),
po_objektu AS (
    SELECT su,
           max(jedinica) FILTER (WHERE rang = 1)  AS jedinica_najnoviji,
           max(jedinica) FILTER (WHERE rang > 1)  AS jedinica_stariji_max,
           bool_or(verificiran) FILTER (WHERE rang = 1) AS najnoviji_verificiran
      FROM po_predmetu
     GROUP BY su
    HAVING count(*) > 1)
SELECT count(*)                                                          AS objekata_u_vise_predmeta,
       count(*) FILTER (WHERE jedinica_najnoviji < jedinica_stariji_max) AS najnoviji_ima_manje_jedinica,
       count(*) FILTER (WHERE jedinica_najnoviji < jedinica_stariji_max
                          AND najnoviji_verificiran)                     AS od_toga_najnoviji_verificiran
  FROM po_objektu;

-- L5b · do 10 primjera (samo uuid i brojevi)
WITH po_predmetu AS (
    SELECT su, bc_id, min(predmet_rang) AS rang, count(*) AS jedinica
      FROM t9_rang GROUP BY su, bc_id)
SELECT su,
       string_agg(bc_id || ':' || jedinica || CASE WHEN rang = 1 THEN '*' ELSE '' END, ', '
                  ORDER BY rang) AS predmeti_jedinice
  FROM po_predmetu
 GROUP BY su
HAVING count(*) > 1
   AND max(jedinica) FILTER (WHERE rang = 1) < max(jedinica) FILTER (WHERE rang > 1)
 ORDER BY su
 LIMIT 10;

DEALLOCATE m9_popis;
DEALLOCATE m9_broj;
DEALLOCATE m9_vlasnik;
-- kraj · TEMP tablice nestaju zatvaranjem sesije
