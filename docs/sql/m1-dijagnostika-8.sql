-- =====================================================================================================
-- M-1 · dijagnostika, 8. krug (CDU test): popravljen oblik upita + paginacija po objektima
--
-- 7. krug, Z3: konačni upit za iznajmljivača s 3.741 jedinicom trajao je 55,9 s. Uzrok: CTE-ovi
-- (kandidati, aktualni, najnoviji) nemaju statistiku, planer procijeni 1 redak i spoji ih ugniježđenom
-- petljom — 14 milijuna dohvata facility / facility_type. Ovdje je „najnoviji predmet” izveden
-- prozorskom funkcijom nad JEDNIM skupom (bez samospajanja), a pripadnost OIB-u je zastavica u retku.
--
-- Upit je PREPARE-an s parametrima, kako će ga izvršavati aplikacija (JDBC):
--   $1 = oznaka iznajmljivača (OIB se čita iz TEMP tablice i ne ispisuje), $2 = veličina stranice
--   (objekata), $3 = pomak (objekata).
-- Mjeri se i GENERIČKI plan (pgjdbc nakon 5. izvršavanja prelazi na server-side prepared statement).
--
-- SAMO ČITANJE nad str; stvara samo TEMP tablicu i prepared statement (oboje nestaje sa sesijom).
-- Jedna sesija, cijela skripta (Alt+X).
-- =====================================================================================================

-- Ako se skripta ponavlja u istoj sesiji i PREPARE javi „already exists”: prvo DEALLOCATE m1_popis;
-- (ne DEALLOCATE ALL — to bi obrisalo i interne prepared statemente DBeavera/pgjdbc-a).
DROP TABLE IF EXISTS pg_temp.t8_oibi;

CREATE TEMP TABLE t8_oibi AS
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

PREPARE m1_popis(text, int, int) AS
WITH kandidati AS MATERIALIZED (
    -- jedinice iz predmeta iznajmljivača (vlasnik preko predmeta — P5)
    SELECT DISTINCT f.id, f.system_uuid
      FROM str.subject s
      JOIN str.subject_version sv ON sv.subject_id = s.id
      JOIN str.business_case bc   ON bc.subject_version_id = sv.id
      JOIN str.document d         ON d.business_case_id = bc.id
      JOIN str.facility f         ON f.document_id = d.id
     WHERE s.jips = (SELECT oib FROM t8_oibi WHERE oznaka = $1)
       AND f.system_uuid IS NOT NULL),
redovi AS MATERIALIZED (
    -- svi zapisi tih objekata, i tuđi (P4 mora vidjeti noviji predmet drugog vlasnika); uvjeti P2/P3
    SELECT facility.id,
           facility.system_uuid::text               AS su,
           facility.created_date,
           facility.created_by::text <> 'optimit'   AS verificiran,
           business_case.id                         AS bc_id,
           facility.id IN (SELECT id FROM kandidati) AS moj
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
    SELECT id, max(su) AS su, bool_and(verificiran) AS verificiran, max(created_date) AS created_date,
           max(bc_id) AS bc_id, bool_or(moj) AS moj
      FROM redovi
     GROUP BY id
    HAVING count(su) = 1),
rangirani AS (
    -- P4: rang predmeta unutar objekta; svi zapisi najnovijeg predmeta dobiju rang 1
    SELECT a.*,
           dense_rank() OVER (PARTITION BY a.su ORDER BY a.predmet_zadnji DESC, a.bc_id DESC) AS predmet_rang
      FROM (SELECT x.*, max(x.created_date) OVER (PARTITION BY x.su, x.bc_id) AS predmet_zadnji
              FROM aktualni x) a),
prikaz AS (
    -- P5 (moj) + P6 (FBS_ACTIVE, FS_*), tek nakon P4
    SELECT r.id, r.su, r.verificiran
      FROM rangirani r
      JOIN str.facility f ON f.id = r.id
      LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
      LEFT JOIN str.facility_type ft
             ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                          WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
      LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
     WHERE r.predmet_rang = 1
       AND r.moj
       AND c_st.code = 'FBS_ACTIVE'
       AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')),
objekti AS (
    SELECT su, bool_or(verificiran) AS verificiran, min(id) AS prvi_id, count(*) AS jedinica
      FROM prikaz
     GROUP BY su),
stranica AS (
    SELECT su,
           row_number() OVER (ORDER BY verificiran DESC, prvi_id) AS redni,
           count(*)     OVER ()                                   AS ukupno_objekata,
           sum(jedinica) OVER ()                                  AS ukupno_jedinica
      FROM objekti)
SELECT s.redni, s.su AS system_uuid, p.id AS facility_id, p.verificiran,
       s.ukupno_objekata, s.ukupno_jedinica
  FROM stranica s
  JOIN prikaz p ON p.su = s.su
 WHERE s.redni > $3
   AND s.redni <= $3 + $2
 ORDER BY s.redni, p.id;


-- ---------------------------------------------------------------------------------------------------
-- K1 · ispravnost: ukupno_objekata / ukupno_jedinica u prvom retku moraju biti kao u 7. krugu (Z2):
--      test-1 38/38, test-2 213/215, test-3 23/23 (top-migrirani: može biti drugi OIB nego u Z2).
--      Dovoljno je pogledati prvi redak svakog rezultata.
-- ---------------------------------------------------------------------------------------------------
EXECUTE m1_popis('test-1', 20, 0);
EXECUTE m1_popis('test-2', 20, 0);
EXECUTE m1_popis('test-3', 20, 0);
EXECUTE m1_popis('top-migrirani', 20, 0);


-- ---------------------------------------------------------------------------------------------------
-- K2 · brzina, prilagođeni plan (prvih 5 izvršavanja): prva i zadnja stranica najvećeg, test-2
-- ---------------------------------------------------------------------------------------------------
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m1_popis('top-migrirani', 20, 0);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m1_popis('top-migrirani', 20, 40);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m1_popis('test-2', 20, 0);


-- ---------------------------------------------------------------------------------------------------
-- K3 · brzina, GENERIČKI plan (kako će ga JDBC izvršavati nakon 5. poziva)
-- ---------------------------------------------------------------------------------------------------
SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m1_popis('top-migrirani', 20, 0);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE m1_popis('test-2', 20, 0);
RESET plan_cache_mode;

DEALLOCATE m1_popis;
-- kraj · TEMP tablica nestaje zatvaranjem sesije
