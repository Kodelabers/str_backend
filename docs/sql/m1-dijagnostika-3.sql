-- =====================================================================================================
-- M-1 · dijagnostika, 3. krug: prijedlog definicije neverificiranih (migriranih) objekata
--
-- SAMO ČITANJE nad shemama str i str_rn. Stvara samo TEMP tablice (kao 1. krug).
--
-- KAKO POKRENUTI
--   * CDU test: cijela skripta, jedna sesija (Alt+X).
--   * Ostale okoline (dev, preprod, CDU preprod): SAMO prva dva bloka (U0 i U1) — samostalni su,
--     ne stvaraju ništa; služe da se nađe okolina na kojoj postoji objekt iz W-8.
--   * Na preprod / CDU preprod prvo: SET ROLE str_owner;
--   * Ako blok padne, poslati grešku i ne ispravljati upit. Ako U6 padne s „relation str_rn…
--     does not exist”, samo to javiti — ostalo vrijedi.
--
-- PRIJEDLOG koji se mjeri (za potvrdu sa Simonom):
--   neverificiran = created_by = 'optimit'
--                   + svi uvjeti viewa OSIM „predmet gotov” (BCST_RJES_IZVRSNO i execution_date),
--                     jer ih migrirani predmeti nikad nemaju (2. krug, R1: 237.140 od 237.140 predmeta
--                     bez statusa, 238.682 od 238.682 bez datuma izvršnosti)
--                   + oba uvjeta verifikacije kao u viewu (migrirani predmet je uvijek SOURCE — R3)
--   Usporedno se mjeri i Simonova varijanta nad istom osnovom.
-- =====================================================================================================


-- U0 · okolina
SELECT current_database() AS baza, current_user AS korisnik, inet_server_addr() AS server, now() AS vrijeme;


-- ---------------------------------------------------------------------------------------------------
-- U1 · W-8: sve verzije objekta 12bcff39-… (samostalno — za sve okoline)
--      TuRegistar: Nin, Ulica Vile Velebita 4, oznaka „1”, 4 + 2 kreveta; STR je prikazao naziv „2”
--      i kućni broj 6. kucni_broj_nas = adresa kako je čita STR danas (subjekt preko objekta),
--      kucni_broj_view = kako je čita view (subjekt preko predmeta).
-- ---------------------------------------------------------------------------------------------------
SELECT f.id, f.active, f.historical, f.created_by, f.name, f.registration_number, f.created_date,
       f.same_address_subject,
       d.subtype_code AS dok_podvrsta, d.active AS dok_aktivan, d.execution_date AS izvrsnost,
       bc.active AS predmet_aktivan, bcs.code AS status_predmeta, c_st.code AS poslovni_status,
       (SELECT string_agg(coalesce(ce.code::text, '?'), ',')
          FROM str.business_case_verification v
          LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
         WHERE v.unverified_business_case_id = bc.id)                       AS verif_kao_izvor,
       (SELECT string_agg(coalesce(ce.code::text, '?'), ',')
          FROM str.business_case_verification v
          LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
         WHERE v.verified_business_case_id = bc.id)                         AS verif_kao_cilj,
       coalesce(hn1.name, a1.house_number)                                  AS kucni_broj_nas,
       a1.full_address                                                      AS adresa_nas,
       coalesce(hn2.name, a2.house_number)                                  AS kucni_broj_view,
       a2.full_address                                                      AS adresa_view,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
           AND ce.code = 'CAT_BROJ_KREVETA')                                AS kreveti,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
           AND ce.code = 'CAT_BROJ_POM_KREVETA')                            AS pomocni_kreveti,
       f.id IN (SELECT v.f_id FROM str.vw_src_facility_actual v
                 WHERE v.f_system_uuid = f.system_uuid)                     AS u_viewu
  FROM str.facility f
  LEFT JOIN str.document d            ON d.id    = f.document_id
  LEFT JOIN str.business_case bc      ON bc.id   = d.business_case_id
  LEFT JOIN str.codebook_element bcs  ON bcs.id  = bc.status_type_id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.address a1
         ON a1.id = CASE WHEN f.same_address_subject = true
                         THEN (SELECT max(x.address_id) FROM str.subject_address x
                                WHERE x.subject_version_id = f.subject_version_id
                                  AND coalesce(x.active, true) = true)
                         ELSE f.address_id END
  LEFT JOIN str.house_number hn1 ON hn1.id = a1.house_number_id
  LEFT JOIN str.address a2
         ON a2.id = CASE WHEN f.same_address_subject = true
                         THEN (SELECT max(x.address_id) FROM str.subject_address x
                                WHERE x.subject_version_id = bc.subject_version_id
                                  AND x.active = true)
                         ELSE f.address_id END
  LEFT JOIN str.house_number hn2 ON hn2.id = a2.house_number_id
 WHERE f.system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9'
 ORDER BY f.id;


-- =====================================================================================================
-- Odavde samo CDU test.
-- =====================================================================================================

-- pg_temp. eksplicitno: DROP smije dirati samo TEMP tablice ove sesije
DROP TABLE IF EXISTS pg_temp.t3_redovi, pg_temp.t3_verif, pg_temp.t3_neverif, pg_temp.t3_neverif_simon;

-- osnova za migrirane: f_active iz viewa s created_by = 'optimit', BEZ uvjeta „predmet gotov”
CREATE TEMP TABLE t3_redovi AS
SELECT facility.id,
       facility.system_uuid::text               AS system_uuid,
       business_case.subject_version_id         AS bc_subject_version_id,
       document.subtype_code::text              AS dok_podvrsta,
       verification_source.id                   AS vs_id,
       verification_source_status.code::text    AS vs_code,
       verification_target.id                   AS vt_id,
       verification_target_status.code::text    AS vt_code
  FROM str.facility facility
  JOIN str.document document
    ON facility.document_id = document.id AND document.active
  JOIN str.sif_podvrsta_dokumenta document_subtype
    ON document_subtype.code::text = document.subtype_code::text
  JOIN str.sif_vrsta_dokumenata document_type
    ON document_type.code::text = document_subtype.vrsta_dokumenata_code::text
   AND document_type.code::text IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU')
  JOIN str.business_case business_case
    ON document.business_case_id = business_case.id AND business_case.active
  LEFT JOIN str.business_case_verification verification_source
    ON business_case.id = verification_source.unverified_business_case_id
  LEFT JOIN str.codebook_element verification_source_status
    ON verification_source.status_id = verification_source_status.id
  LEFT JOIN str.business_case_verification verification_target
    ON business_case.id = verification_target.verified_business_case_id
  LEFT JOIN str.codebook_element verification_target_status
    ON verification_target.status_id = verification_target_status.id
 WHERE facility.active
   AND facility.created_by::text = 'optimit'
   AND (facility.historical IS NULL OR facility.historical = false)
   AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                WHERE ou.id = business_case.jurisdiction_organizational_unit_id);

CREATE INDEX ON t3_redovi (id);
ANALYZE t3_redovi;

-- prijedlog: oba uvjeta verifikacije kao u viewu
CREATE TEMP TABLE t3_neverif AS
SELECT id, max(system_uuid) AS system_uuid, max(dok_podvrsta) AS dok_podvrsta
  FROM t3_redovi
 WHERE (vs_id IS NULL OR vs_code = 'BCVS_U_IZRADI')
   AND (vt_id IS NULL OR vt_code = 'BCVS_ZAVRSENA')
 GROUP BY id
HAVING count(system_uuid) = 1;

-- Simonova varijanta nad istom osnovom
CREATE TEMP TABLE t3_neverif_simon AS
SELECT id, max(system_uuid) AS system_uuid
  FROM t3_redovi
 WHERE (vt_id IS NULL OR vt_code <> 'BCVS_ZAVRSENA')
 GROUP BY id
HAVING count(system_uuid) = 1;

-- verificirani = view, kako je dan
CREATE TEMP TABLE t3_verif AS
SELECT DISTINCT f_id AS id, f_system_uuid::text AS system_uuid
  FROM str.vw_src_facility_actual;

CREATE INDEX ON t3_neverif (id);        CREATE INDEX ON t3_neverif (system_uuid);
CREATE INDEX ON t3_neverif_simon (id);  CREATE INDEX ON t3_neverif_simon (system_uuid);
CREATE INDEX ON t3_verif (id);          CREATE INDEX ON t3_verif (system_uuid);
ANALYZE t3_neverif; ANALYZE t3_neverif_simon; ANALYZE t3_verif;


-- ---------------------------------------------------------------------------------------------------
-- U2 · veličina skupova, razlika prijedlog / Simon, duplikati
--   *_isti_uuid_kao_verif MORA biti 0 — inače bi isti objekt bio i verificiran i neverificiran.
--   uuid_s_vise_zapisa > 0 → ista stvar dvaput u neverificiranima (treba dodatni dedup — pitati Simona).
-- ---------------------------------------------------------------------------------------------------
SELECT (SELECT count(*) FROM t3_verif)                                                  AS verificirani,
       (SELECT count(DISTINCT id) FROM t3_redovi)                                       AS migrirani_osnova,
       (SELECT count(*) FROM t3_neverif)                                                AS neverif_prijedlog,
       (SELECT count(*) FROM t3_neverif_simon)                                          AS neverif_simon,
       (SELECT count(*) FROM t3_neverif_simon s
         WHERE NOT EXISTS (SELECT 1 FROM t3_neverif n WHERE n.id = s.id))               AS samo_u_simon,
       (SELECT count(*) FROM t3_neverif n
         WHERE NOT EXISTS (SELECT 1 FROM t3_neverif_simon s WHERE s.id = n.id))         AS samo_u_prijedlogu,
       (SELECT count(*) FROM t3_neverif n
         WHERE EXISTS (SELECT 1 FROM t3_verif v WHERE v.system_uuid = n.system_uuid))   AS prijedlog_isti_uuid_kao_verif,
       (SELECT count(*) FROM t3_neverif_simon n
         WHERE EXISTS (SELECT 1 FROM t3_verif v WHERE v.system_uuid = n.system_uuid))   AS simon_isti_uuid_kao_verif,
       (SELECT count(*) FROM (SELECT system_uuid FROM t3_neverif
                               GROUP BY system_uuid HAVING count(*) > 1) x)             AS uuid_s_vise_zapisa,
       (SELECT count(*) FROM (SELECT system_uuid FROM t3_verif
                               GROUP BY system_uuid HAVING count(*) > 1) x)             AS verif_uuid_s_vise_zapisa;


-- ---------------------------------------------------------------------------------------------------
-- U3 · prijedlog po podvrsti rješenja × poslovni status × je li smještaj (FS_*)
--      (DST_R_UK_* su rješenja o ukidanju — trebaju li se uopće prikazati?)
-- ---------------------------------------------------------------------------------------------------
SELECT n.dok_podvrsta,
       coalesce(c_st.code::text, 'NULL')                                         AS poslovni_status,
       c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR') AS smjestaj_fs,
       count(*)                                                                  AS objekata
  FROM t3_neverif n
  JOIN str.facility f ON f.id = n.id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 GROUP BY 1, 2, 3
 ORDER BY 4 DESC;


-- ---------------------------------------------------------------------------------------------------
-- U4 · primjeri uuid-ova s više zapisa u prijedlogu (ako ih U2 pokaže) — do 10 uuid-ova
-- ---------------------------------------------------------------------------------------------------
SELECT n.system_uuid, n.id, n.dok_podvrsta, f.created_date, f.last_modified_date,
       c_st.code AS poslovni_status, d.business_case_id AS predmet
  FROM t3_neverif n
  JOIN str.facility f ON f.id = n.id
  LEFT JOIN str.document d ON d.id = f.document_id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
 WHERE n.system_uuid IN (SELECT system_uuid FROM t3_neverif
                          GROUP BY system_uuid HAVING count(*) > 1
                          ORDER BY system_uuid LIMIT 10)
 ORDER BY n.system_uuid, n.id;


-- ---------------------------------------------------------------------------------------------------
-- U5 · novi popis za testne iznajmljivače (vlasnik preko predmeta, filtri FBS_ACTIVE + FS_*)
--      Danas (1. krug, Q5): test-1 38, test-2 207, test-3 20.
-- ---------------------------------------------------------------------------------------------------
WITH o(oznaka, oib) AS (VALUES ('test-1', '06756460531'), ('test-2', '12312312316'), ('test-3', '98765432106')),
k AS (
    SELECT DISTINCT o.oznaka, f.id
      FROM o
      JOIN str.subject s          ON s.jips = o.oib
      JOIN str.subject_version sv ON sv.subject_id = s.id
      JOIN str.business_case bc   ON bc.subject_version_id = sv.id
      JOIN str.document d         ON d.business_case_id = bc.id
      JOIN str.facility f         ON f.document_id = d.id),
pr AS (
    SELECT k.oznaka, k.id
      FROM k
      JOIN str.facility f ON f.id = k.id
      LEFT JOIN str.facility_type ft
             ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                          WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
      LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
      LEFT JOIN str.codebook_element c_st  ON c_st.id  = f.business_status_id
     WHERE c_st.code = 'FBS_ACTIVE'
       AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR'))
SELECT o.oznaka,
       (SELECT count(*) FROM k WHERE k.oznaka = o.oznaka)                                  AS zapisa_preko_predmeta,
       (SELECT count(*) FROM pr WHERE pr.oznaka = o.oznaka AND pr.id IN (SELECT id FROM t3_verif))   AS verificirani,
       (SELECT count(*) FROM pr WHERE pr.oznaka = o.oznaka AND pr.id IN (SELECT id FROM t3_neverif)) AS neverificirani
  FROM o
 ORDER BY o.oznaka;


-- ---------------------------------------------------------------------------------------------------
-- U6 · registracijski brojevi koje je izdao STR: vise li na aktualnoj verziji objekta?
--      Popis RB veže po facility_id; nakon verifikacije eTurizam stvara NOVI zapis (drugi id, isti uuid
--      u većini slučajeva — R4). aktualna_je_druga_verzija > 0 → objekt bi na popisu izgubio RB i
--      korisnik bi mogao tražiti novi.
-- ---------------------------------------------------------------------------------------------------
SELECT r.status,
       count(*)                                                                  AS rb,
       count(*) FILTER (WHERE a.facility_id IS NULL)                             AS bez_facility_id,
       count(*) FILTER (WHERE a.facility_id IS NOT NULL AND f.id IS NULL)        AS facility_ne_postoji,
       count(*) FILTER (WHERE f.id IN (SELECT id FROM t3_verif))                 AS na_verificiranom,
       count(*) FILTER (WHERE f.id IN (SELECT id FROM t3_neverif))               AS na_neverificiranom,
       count(*) FILTER (WHERE f.id IS NOT NULL
                          AND f.id NOT IN (SELECT id FROM t3_verif)
                          AND f.id NOT IN (SELECT id FROM t3_neverif)
                          AND EXISTS (SELECT 1 FROM str.facility f2
                                       WHERE f2.system_uuid = f.system_uuid AND f2.id <> f.id
                                         AND (f2.id IN (SELECT id FROM t3_verif)
                                              OR f2.id IN (SELECT id FROM t3_neverif)))) AS aktualna_je_druga_verzija,
       count(*) FILTER (WHERE f.registration_number IS NOT NULL)                 AS upisan_u_eturizam
  FROM str_rn.registration_number r
  JOIN str_rn.accommodation a ON a.accommodation_id = r.accommodation_id
  LEFT JOIN str.facility f    ON f.id::text = a.facility_id
 GROUP BY r.status
 ORDER BY r.status;


-- ---------------------------------------------------------------------------------------------------
-- U7 · prenosi li eTurizam registration_number na novu verziju objekta
-- ---------------------------------------------------------------------------------------------------
SELECT count(*) FILTER (WHERE f.registration_number IS NOT NULL)                AS aktualnih_s_rb,
       count(*) FILTER (WHERE f.registration_number IS NULL
                          AND EXISTS (SELECT 1 FROM str.facility f2
                                       WHERE f2.system_uuid = f.system_uuid AND f2.id <> f.id
                                         AND f2.registration_number IS NOT NULL)) AS rb_samo_na_drugoj_verziji,
       (SELECT count(*) FROM str.facility WHERE registration_number IS NOT NULL) AS svih_zapisa_s_rb
  FROM str.facility f
 WHERE f.id IN (SELECT id FROM t3_verif UNION SELECT id FROM t3_neverif);

-- kraj · TEMP tablice nestaju zatvaranjem sesije
