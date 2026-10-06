-- =====================================================================================================
-- M-1 · dijagnostika, 7. krug (CDU test): cijeli skup pravila na podacima + brzina konačnog oblika upita
--
-- SAMO ČITANJE nad str; stvara samo TEMP tablice. Jedna sesija, cijela skripta (Alt+X).
-- OIB-ovi se ne ispisuju (oznake test-1 … i top-migrirani).
--
-- PRAVILA (odluke do 6. 10. 2026.; točke označene [Simon] još čekaju njegovu potvrdu):
--   P1  zapis facility = smještajna jedinica; system_uuid = objekt
--   P2  verificiran   = created_by <> 'optimit' + svi uvjeti viewa vw_src_facility_actual (1:1)
--   P3  neverificiran = created_by =  'optimit' + uvjeti viewa BEZ „predmet gotov”
--                       (status BCST_RJES_IZVRSNO i execution_date — migrirani ih nemaju)   [Simon]
--   P4  po objektu (uuid) vrijedi samo NAJNOVIJI aktualni predmet: predmet s najkasnijim
--       facility.created_date, a kod jednakosti veći business_case.id. Računa se PRIJE filtara
--       vlasnika, statusa i vrste — noviji odjavljeni ili preneseni predmet skriva stariji.
--   P5  vlasnik = business_case.subject_version_id → subject.jips                        [Simon]
--   P6  prikazuje se poslovni status FBS_ACTIVE (W-5) i vrsta smještaja FS_*
-- =====================================================================================================

DROP TABLE IF EXISTS pg_temp.t7_redovi, pg_temp.t7_aktualni, pg_temp.t7_najnoviji, pg_temp.t7_top;

-- P2 + P3: redovi f_active logike za oba skupa (bez created_by IS NULL — view ga nema ni u jednom)
CREATE TEMP TABLE t7_redovi AS
SELECT facility.id,
       facility.system_uuid::text            AS system_uuid,
       facility.created_by::text <> 'optimit' AS verificiran,
       facility.created_date,
       business_case.id                      AS bc_id
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
  LEFT JOIN str.codebook_element bc_status_ce
    ON bc_status_ce.id = business_case.status_type_id
 WHERE facility.active
   AND facility.created_by IS NOT NULL
   AND (facility.historical IS NULL OR facility.historical = false)
   AND (verification_source.id IS NULL OR verification_source_status.code::text = 'BCVS_U_IZRADI')
   AND (verification_target.id IS NULL OR verification_target_status.code::text = 'BCVS_ZAVRSENA')
   AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
   AND (facility.created_by::text = 'optimit'
        OR (bc_status_ce.code::text = 'BCST_RJES_IZVRSNO'
            AND document.execution_date IS NOT NULL
            AND document.execution_date < now()));

CREATE TEMP TABLE t7_aktualni AS
SELECT id, max(system_uuid) AS system_uuid, bool_and(verificiran) AS verificiran,
       max(created_date) AS created_date, max(bc_id) AS bc_id
  FROM t7_redovi
 GROUP BY id
HAVING count(system_uuid) = 1;

CREATE INDEX ON t7_aktualni (id);
CREATE INDEX ON t7_aktualni (system_uuid);
ANALYZE t7_aktualni;

-- P4: najnoviji predmet po objektu
CREATE TEMP TABLE t7_najnoviji AS
SELECT DISTINCT ON (system_uuid) system_uuid, bc_id
  FROM (SELECT system_uuid, bc_id, max(created_date) AS zadnji
          FROM t7_aktualni
         GROUP BY system_uuid, bc_id) p
 ORDER BY system_uuid, zadnji DESC, bc_id DESC;

CREATE INDEX ON t7_najnoviji (system_uuid, bc_id);
ANALYZE t7_najnoviji;


-- ---------------------------------------------------------------------------------------------------
-- Z1 · učinak pravila P4 na cijelom registru (prije filtara P5/P6)
-- ---------------------------------------------------------------------------------------------------
SELECT (SELECT count(*) FROM t7_aktualni)                                              AS aktualnih_zapisa,
       (SELECT count(*) FROM t7_aktualni WHERE verificiran)                            AS od_toga_verificiranih,
       (SELECT count(DISTINCT system_uuid) FROM t7_aktualni)                           AS objekata,
       (SELECT count(*) FROM (SELECT system_uuid FROM t7_aktualni
                               GROUP BY system_uuid HAVING count(DISTINCT bc_id) > 1) x) AS objekata_u_vise_predmeta,
       (SELECT count(*) FROM t7_aktualni a
         WHERE NOT EXISTS (SELECT 1 FROM t7_najnoviji n
                            WHERE n.system_uuid = a.system_uuid AND n.bc_id = a.bc_id))  AS zapisa_skriva_p4,
       (SELECT count(*) FROM t7_aktualni a
         WHERE NOT a.verificiran
           AND NOT EXISTS (SELECT 1 FROM t7_najnoviji n
                            WHERE n.system_uuid = a.system_uuid AND n.bc_id = a.bc_id)
           AND EXISTS (SELECT 1 FROM t7_aktualni v
                        WHERE v.system_uuid = a.system_uuid AND v.verificiran))         AS migriranih_skrivenih_jer_postoji_verificirani,
       (SELECT count(*) FROM t7_aktualni a
         WHERE a.verificiran
           AND NOT EXISTS (SELECT 1 FROM t7_najnoviji n
                            WHERE n.system_uuid = a.system_uuid AND n.bc_id = a.bc_id)
           AND EXISTS (SELECT 1 FROM t7_aktualni m JOIN t7_najnoviji n
                         ON n.system_uuid = m.system_uuid AND n.bc_id = m.bc_id
                        WHERE m.system_uuid = a.system_uuid AND NOT m.verificiran))     AS verificiranih_skrivenih_iza_migriranog;


-- ---------------------------------------------------------------------------------------------------
-- Z2 · novi popis po iznajmljivaču: objekti i jedinice, verificirano / neverificirano
--      Danas (dedup po uuid-u): test-1 38, test-2 207, test-3 20.
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP TABLE t7_top AS
SELECT s.jips AS oib
  FROM t7_aktualni a
  JOIN t7_najnoviji n          ON n.system_uuid = a.system_uuid AND n.bc_id = a.bc_id
  JOIN str.business_case bc    ON bc.id = a.bc_id
  JOIN str.subject_version sv  ON sv.id = bc.subject_version_id
  JOIN str.subject s           ON s.id = sv.subject_id
 WHERE NOT a.verificiran
 GROUP BY s.jips
 ORDER BY count(*) DESC
 LIMIT 1;

WITH o(oznaka, oib) AS (
    SELECT * FROM (VALUES ('test-1', '06756460531'), ('test-2', '12312312316'), ('test-3', '98765432106')) x
    UNION ALL
    SELECT 'top-migrirani', oib FROM t7_top),
prikaz AS (
    SELECT o.oznaka, a.id, a.system_uuid, a.verificiran
      FROM o
      JOIN str.subject s          ON s.jips = o.oib
      JOIN str.subject_version sv ON sv.subject_id = s.id
      JOIN str.business_case bc   ON bc.subject_version_id = sv.id
      JOIN t7_aktualni a          ON a.bc_id = bc.id
      JOIN t7_najnoviji n         ON n.system_uuid = a.system_uuid AND n.bc_id = a.bc_id
      JOIN str.facility f         ON f.id = a.id
      LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
      LEFT JOIN str.facility_type ft
             ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                          WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
      LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
     WHERE c_st.code = 'FBS_ACTIVE'
       AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR'))
SELECT o.oznaka,
       count(DISTINCT p.system_uuid)                                        AS objekata,
       count(p.id)                                                          AS jedinica,
       count(DISTINCT p.system_uuid) FILTER (WHERE p.verificiran)           AS verif_objekata,
       count(p.id) FILTER (WHERE p.verificiran)                             AS verif_jedinica,
       count(DISTINCT p.system_uuid) FILTER (WHERE NOT p.verificiran)       AS neverif_objekata,
       count(p.id) FILTER (WHERE NOT p.verificiran)                         AS neverif_jedinica,
       max(j.n)                                                             AS najvise_jedinica_u_objektu
  FROM o
  LEFT JOIN prikaz p ON p.oznaka = o.oznaka
  LEFT JOIN LATERAL (SELECT count(*) AS n FROM prikaz q
                      WHERE q.oznaka = o.oznaka AND q.system_uuid = p.system_uuid) j ON true
 GROUP BY o.oznaka
 ORDER BY o.oznaka;


-- ---------------------------------------------------------------------------------------------------
-- Z3 · brzina konačnog oblika upita (bez TEMP tablica, kako bi išao u aplikaciju)
--      Kandidati = jedinice iz predmeta OIB-a → svi aktualni zapisi njihovih objekata (i tuđi, zbog P4)
--      → najnoviji predmet → natrag na predmete OIB-a. Mjeri se za test-2 i za top-migriranog.
-- ---------------------------------------------------------------------------------------------------
EXPLAIN (ANALYZE, BUFFERS)
WITH kandidati AS MATERIALIZED (
    SELECT DISTINCT f.id, f.system_uuid, bc.id AS bc_id
      FROM str.subject s
      JOIN str.subject_version sv ON sv.subject_id = s.id
      JOIN str.business_case bc   ON bc.subject_version_id = sv.id
      JOIN str.document d         ON d.business_case_id = bc.id
      JOIN str.facility f         ON f.document_id = d.id
     WHERE s.jips = '12312312316'
       AND f.system_uuid IS NOT NULL),
redovi AS MATERIALIZED (
    SELECT facility.id, facility.system_uuid, facility.created_date,
           facility.created_by::text <> 'optimit' AS verificiran,
           business_case.id AS bc_id
      FROM (SELECT DISTINCT system_uuid FROM kandidati) u
      JOIN str.facility facility ON facility.system_uuid = u.system_uuid
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
      LEFT JOIN str.codebook_element bc_status_ce
        ON bc_status_ce.id = business_case.status_type_id
     WHERE facility.active
       AND facility.created_by IS NOT NULL
       AND (facility.historical IS NULL OR facility.historical = false)
       AND (verification_source.id IS NULL OR verification_source_status.code::text = 'BCVS_U_IZRADI')
       AND (verification_target.id IS NULL OR verification_target_status.code::text = 'BCVS_ZAVRSENA')
       AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                    WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
       AND (facility.created_by::text = 'optimit'
            OR (bc_status_ce.code::text = 'BCST_RJES_IZVRSNO'
                AND document.execution_date IS NOT NULL
                AND document.execution_date < now()))),
aktualni AS (
    SELECT id, max(system_uuid::text) AS system_uuid, bool_and(verificiran) AS verificiran,
           max(created_date) AS created_date, max(bc_id) AS bc_id
      FROM redovi
     GROUP BY id
    HAVING count(system_uuid) = 1),
najnoviji AS (
    SELECT DISTINCT ON (system_uuid) system_uuid, bc_id
      FROM (SELECT system_uuid, bc_id, max(created_date) AS zadnji
              FROM aktualni GROUP BY system_uuid, bc_id) p
     ORDER BY system_uuid, zadnji DESC, bc_id DESC)
SELECT a.id, a.system_uuid, a.verificiran
  FROM aktualni a
  JOIN najnoviji n ON n.system_uuid = a.system_uuid AND n.bc_id = a.bc_id
  JOIN kandidati k ON k.id = a.id AND k.bc_id = a.bc_id
  JOIN str.facility f ON f.id = a.id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 WHERE c_st.code = 'FBS_ACTIVE'
   AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')
 ORDER BY a.verificiran DESC, a.system_uuid, a.id;

-- isto za iznajmljivača s najviše migriranih jedinica (OIB iz t7_top, ne ispisuje se)
EXPLAIN (ANALYZE, BUFFERS)
WITH kandidati AS MATERIALIZED (
    SELECT DISTINCT f.id, f.system_uuid, bc.id AS bc_id
      FROM str.subject s
      JOIN str.subject_version sv ON sv.subject_id = s.id
      JOIN str.business_case bc   ON bc.subject_version_id = sv.id
      JOIN str.document d         ON d.business_case_id = bc.id
      JOIN str.facility f         ON f.document_id = d.id
     WHERE s.jips = (SELECT oib FROM t7_top)
       AND f.system_uuid IS NOT NULL),
redovi AS MATERIALIZED (
    SELECT facility.id, facility.system_uuid, facility.created_date,
           facility.created_by::text <> 'optimit' AS verificiran,
           business_case.id AS bc_id
      FROM (SELECT DISTINCT system_uuid FROM kandidati) u
      JOIN str.facility facility ON facility.system_uuid = u.system_uuid
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
      LEFT JOIN str.codebook_element bc_status_ce
        ON bc_status_ce.id = business_case.status_type_id
     WHERE facility.active
       AND facility.created_by IS NOT NULL
       AND (facility.historical IS NULL OR facility.historical = false)
       AND (verification_source.id IS NULL OR verification_source_status.code::text = 'BCVS_U_IZRADI')
       AND (verification_target.id IS NULL OR verification_target_status.code::text = 'BCVS_ZAVRSENA')
       AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                    WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
       AND (facility.created_by::text = 'optimit'
            OR (bc_status_ce.code::text = 'BCST_RJES_IZVRSNO'
                AND document.execution_date IS NOT NULL
                AND document.execution_date < now()))),
aktualni AS (
    SELECT id, max(system_uuid::text) AS system_uuid, bool_and(verificiran) AS verificiran,
           max(created_date) AS created_date, max(bc_id) AS bc_id
      FROM redovi
     GROUP BY id
    HAVING count(system_uuid) = 1),
najnoviji AS (
    SELECT DISTINCT ON (system_uuid) system_uuid, bc_id
      FROM (SELECT system_uuid, bc_id, max(created_date) AS zadnji
              FROM aktualni GROUP BY system_uuid, bc_id) p
     ORDER BY system_uuid, zadnji DESC, bc_id DESC)
SELECT a.id, a.system_uuid, a.verificiran
  FROM aktualni a
  JOIN najnoviji n ON n.system_uuid = a.system_uuid AND n.bc_id = a.bc_id
  JOIN kandidati k ON k.id = a.id AND k.bc_id = a.bc_id
  JOIN str.facility f ON f.id = a.id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 WHERE c_st.code = 'FBS_ACTIVE'
   AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')
 ORDER BY a.verificiran DESC, a.system_uuid, a.id;

-- kraj · TEMP tablice nestaju zatvaranjem sesije
