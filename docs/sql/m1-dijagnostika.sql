-- =====================================================================================================
-- M-1 · dijagnostika eTurizam objekata: verificirani (vw_src_facility_actual) i neverificirani (optimit)
--
-- SAMO ČITANJE nad shemom str. Skripta stvara isključivo TEMP tablice (žive do kraja sesije, nisu
-- vidljive nikome drugome i ne diraju shemu str).
--
-- KAKO POKRENUTI
--   * Jedna sesija po okolini, cijela skripta odjednom (DBeaver: Alt+X „Execute script”).
--   * Kao aplikacijski korisnik. Na preprod i CDU preprod prvo: SET ROLE str_owner;
--   * Svaki blok (Q0 … Q7) vraća svoj rezultat — poslati sve, uz naziv okoline.
--   * Ako blok padne, poslati poruku greške i NE ispravljati upit. Posebno:
--       - Q0 pokaže da str.document nema subtype_code (shema str1) → stati nakon Q0 i poslati rezultat;
--       - „cannot execute CREATE TABLE in a read-only transaction” → isključiti read-only za sesiju
--         ili javiti, šaljem inačicu bez TEMP tablica.
--   * Minimum na svakoj okolini je Q0. Cijela skripta: CDU i predprod.
--
-- OIB-ovi se u rezultatima ne ispisuju — OIB-ovi u Q5 imaju oznake (test-1, top-1, W-8 …).
--
-- Uvjeti u t_redovi su prepisani 1:1 iz f_active CTE-a viewa str.vw_src_facility_actual (DDL koji je
-- poslao Simon, eTurizam, 4. 10. 2026.). Tri varijante skupa:
--   t_verif          = view (created_by <> 'optimit' + oba uvjeta verifikacije)
--   t_neverif_simon  = Simonova inverzija (created_by = 'optimit' + target ne postoji ili nije ZAVRSENA)
--   t_neverif_alt    = samo created_by obrnut (created_by = 'optimit' + oba uvjeta verifikacije kao view)
-- =====================================================================================================


-- ---------------------------------------------------------------------------------------------------
-- Q0 · oblik okoline i prava
-- ---------------------------------------------------------------------------------------------------
SELECT current_database()                                                     AS baza,
       current_user                                                           AS korisnik,
       now()                                                                  AS vrijeme,
       to_regclass('str.vw_src_facility_actual')     IS NOT NULL              AS ima_view_actual,
       to_regclass('str.vw_src_facility_historical') IS NOT NULL              AS ima_view_historical,
       md5(pg_get_viewdef(to_regclass('str.vw_src_facility_actual'), true))   AS actual_md5,
       (SELECT string_agg(column_name::text, ',' ORDER BY column_name)
          FROM information_schema.columns
         WHERE table_schema = 'str' AND table_name = 'document'
           AND column_name IN ('subtype_code', 'document_subtype_id', 'execution_date')) AS document_kolone,
       (SELECT string_agg(column_name::text, ',' ORDER BY column_name)
          FROM information_schema.columns
         WHERE table_schema = 'str' AND table_name = 'facility'
           AND column_name IN ('created_by', 'historical', 'system_uuid'))              AS facility_kolone,
       (SELECT data_type FROM information_schema.columns
         WHERE table_schema = 'str' AND table_name = 'facility'
           AND column_name = 'system_uuid')                                   AS system_uuid_tip,
       has_table_privilege(to_regclass('str.vw_src_facility_actual'), 'SELECT')     AS pravo_view,
       has_table_privilege(to_regclass('str.business_case'), 'SELECT')              AS pravo_business_case,
       has_table_privilege(to_regclass('str.business_case_verification'), 'SELECT') AS pravo_bc_verification,
       has_table_privilege(to_regclass('str.sif_podvrsta_dokumenta'), 'SELECT')     AS pravo_sif_podvrsta,
       has_table_privilege(to_regclass('str.sif_vrsta_dokumenata'), 'SELECT')       AS pravo_sif_vrsta,
       has_table_privilege(to_regclass('str.organizational_unit'), 'SELECT')        AS pravo_org_unit;

-- Q0b · puna definicija viewa (za usporedbu sa Simonovim DDL-om; dovoljno s jedne okoline)
SELECT pg_get_viewdef(to_regclass('str.vw_src_facility_actual'), true) AS actual_definicija;


-- ---------------------------------------------------------------------------------------------------
-- Priprema · redovi f_active CTE-a BEZ uvjeta created_by i verifikacije (oni se primjenjuju po varijanti)
--
-- Vanjski SELECT viewa radi INNER JOIN na organizational_unit; ovdje je to EXISTS — svi redovi jednog
-- objekta dijele isti predmet, pa EXISTS ne mijenja broj redaka koje broji HAVING.
-- ---------------------------------------------------------------------------------------------------
-- pg_temp. eksplicitno: DROP smije dirati samo TEMP tablice ove sesije, nikad istoimenu tablicu u shemi
DROP TABLE IF EXISTS pg_temp.t_redovi, pg_temp.t_verif, pg_temp.t_neverif_simon, pg_temp.t_neverif_alt,
                     pg_temp.t_top, pg_temp.t_oibi, pg_temp.t_kandidati, pg_temp.t_prikazivo,
                     pg_temp.t_sadasnji;

CREATE TEMP TABLE t_redovi AS
SELECT facility.id,
       facility.system_uuid::text                AS system_uuid,
       facility.created_by::text                 AS created_by,
       facility.subject_version_id               AS f_subject_version_id,
       business_case.id                          AS bc_id,
       business_case.subject_version_id          AS bc_subject_version_id,
       verification_source.id                    AS vs_id,
       verification_source_status.code::text     AS vs_code,
       verification_target.id                    AS vt_id,
       verification_target_status.code::text     AS vt_code
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
   AND (facility.historical IS NULL OR facility.historical = false)
   AND bc_status_ce.code::text = 'BCST_RJES_IZVRSNO'
   AND document.execution_date IS NOT NULL
   AND document.execution_date < now()
   AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                WHERE ou.id = business_case.jurisdiction_organizational_unit_id);

CREATE INDEX ON t_redovi (id);
CREATE INDEX ON t_redovi (system_uuid);
ANALYZE t_redovi;

CREATE TEMP TABLE t_verif AS
SELECT id, max(system_uuid) AS system_uuid
  FROM t_redovi
 WHERE created_by <> 'optimit'
   AND (vs_id IS NULL OR vs_code = 'BCVS_U_IZRADI')
   AND (vt_id IS NULL OR vt_code = 'BCVS_ZAVRSENA')
 GROUP BY id
HAVING count(system_uuid) = 1;

CREATE TEMP TABLE t_neverif_simon AS
SELECT id, max(system_uuid) AS system_uuid
  FROM t_redovi
 WHERE created_by = 'optimit'
   AND (vt_id IS NULL OR vt_code <> 'BCVS_ZAVRSENA')
 GROUP BY id
HAVING count(system_uuid) = 1;

CREATE TEMP TABLE t_neverif_alt AS
SELECT id, max(system_uuid) AS system_uuid
  FROM t_redovi
 WHERE created_by = 'optimit'
   AND (vs_id IS NULL OR vs_code = 'BCVS_U_IZRADI')
   AND (vt_id IS NULL OR vt_code = 'BCVS_ZAVRSENA')
 GROUP BY id
HAVING count(system_uuid) = 1;

CREATE INDEX ON t_verif (id);         CREATE INDEX ON t_verif (system_uuid);
CREATE INDEX ON t_neverif_simon (id); CREATE INDEX ON t_neverif_simon (system_uuid);
CREATE INDEX ON t_neverif_alt (id);   CREATE INDEX ON t_neverif_alt (system_uuid);
ANALYZE t_verif; ANALYZE t_neverif_simon; ANALYZE t_neverif_alt;


-- ---------------------------------------------------------------------------------------------------
-- Q1 · statusi verifikacije predmeta
-- ---------------------------------------------------------------------------------------------------
SELECT ce.code AS status, ce.name AS naziv, count(*) AS verifikacija
  FROM str.business_case_verification v
  LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
 GROUP BY ce.code, ce.name
 ORDER BY count(*) DESC;


-- ---------------------------------------------------------------------------------------------------
-- Q2 · klasifikacija registra: tko je stvorio × verifikacija kao izvor × verifikacija kao cilj
--   prolazi_uvjete_viewa   → za „ostali” = verificirani (view); za „optimit” = varijanta alt
--   prolazi_simonove_uvjete → za „optimit” = Simonova varijanta
--   Ključni redak: optimit s kao_izvor = BCVS_ZAVRSENA (migrirani objekt kojemu je verifikacija gotova)
-- ---------------------------------------------------------------------------------------------------
WITH po_objektu AS (
    SELECT id,
           max(created_by) AS created_by,
           count(system_uuid) FILTER (WHERE (vs_id IS NULL OR vs_code = 'BCVS_U_IZRADI')
                                        AND (vt_id IS NULL OR vt_code = 'BCVS_ZAVRSENA')) AS cnt_view,
           count(system_uuid) FILTER (WHERE vt_id IS NULL OR vt_code <> 'BCVS_ZAVRSENA')  AS cnt_simon,
           coalesce(string_agg(DISTINCT coalesce(vs_code, '?'), ',') FILTER (WHERE vs_id IS NOT NULL), '-') AS kao_izvor,
           coalesce(string_agg(DISTINCT coalesce(vt_code, '?'), ',') FILTER (WHERE vt_id IS NOT NULL), '-') AS kao_cilj
      FROM t_redovi
     GROUP BY id)
SELECT CASE WHEN created_by IS NULL THEN '(NULL)'
            WHEN created_by = 'optimit' THEN 'optimit'
            ELSE 'ostali' END                         AS tko,
       kao_izvor,
       kao_cilj,
       count(*)                                       AS objekata,
       count(*) FILTER (WHERE cnt_view = 1)           AS prolazi_uvjete_viewa,
       count(*) FILTER (WHERE cnt_simon = 1)          AS prolazi_simonove_uvjete
  FROM po_objektu
 GROUP BY 1, 2, 3
 ORDER BY 1, 4 DESC;


-- ---------------------------------------------------------------------------------------------------
-- Q3 · vjernost kopije uvjeta i duplikati
--   samo_kopija i samo_view MORAJU biti 0 — inače kopija uvjeta nije 1:1 s viewom.
--   *_isti_uuid_kao_verif > 0 → isti objekt bi bio i u verificiranima i u neverificiranima.
--   simon_vec_verificiran → migrirani objekti kojima je verifikacija završena, a Simonova ih
--   varijanta i dalje vraća.
-- ---------------------------------------------------------------------------------------------------
WITH v AS (SELECT DISTINCT f_id AS id FROM str.vw_src_facility_actual)
SELECT (SELECT count(*) FROM t_verif)                                                    AS kopija_verificirani,
       (SELECT count(*) FROM v)                                                          AS view_verificirani,
       (SELECT count(*) FROM t_verif t WHERE NOT EXISTS (SELECT 1 FROM v WHERE v.id = t.id))      AS samo_kopija,
       (SELECT count(*) FROM v WHERE NOT EXISTS (SELECT 1 FROM t_verif t WHERE t.id = v.id))      AS samo_view,
       (SELECT count(*) FROM t_neverif_simon)                                            AS neverif_simon,
       (SELECT count(*) FROM t_neverif_alt)                                              AS neverif_alt,
       (SELECT count(*) FROM t_neverif_simon n
         WHERE NOT EXISTS (SELECT 1 FROM t_neverif_alt a WHERE a.id = n.id))             AS samo_u_simon,
       (SELECT count(*) FROM t_neverif_alt a
         WHERE NOT EXISTS (SELECT 1 FROM t_neverif_simon n WHERE n.id = a.id))           AS samo_u_alt,
       (SELECT count(*) FROM t_neverif_simon n
         WHERE EXISTS (SELECT 1 FROM t_verif t WHERE t.system_uuid = n.system_uuid))     AS simon_isti_uuid_kao_verif,
       (SELECT count(*) FROM t_neverif_alt n
         WHERE EXISTS (SELECT 1 FROM t_verif t WHERE t.system_uuid = n.system_uuid))     AS alt_isti_uuid_kao_verif,
       (SELECT count(*) FROM t_neverif_simon n
         WHERE EXISTS (SELECT 1 FROM t_redovi r
                        WHERE r.id = n.id AND r.vs_code = 'BCVS_ZAVRSENA'))               AS simon_vec_verificiran,
       (SELECT count(DISTINCT id) FROM t_redovi WHERE created_by IS NULL)                AS created_by_null,
       (SELECT count(DISTINCT id) FROM t_redovi WHERE system_uuid IS NULL)               AS bez_system_uuid;


-- ---------------------------------------------------------------------------------------------------
-- Q4 · vlasnik: subject_version objekta naspram subject_version predmeta (view adresu subjekta uzima
--      preko predmeta, mi OIB i adresu preko objekta)
-- ---------------------------------------------------------------------------------------------------
SELECT count(*)                                                                         AS objekata,
       count(*) FILTER (WHERE r.f_subject_version_id = r.bc_subject_version_id)         AS isti_subject_version,
       count(*) FILTER (WHERE r.f_subject_version_id IS DISTINCT FROM r.bc_subject_version_id) AS razlicit_subject_version,
       count(*) FILTER (WHERE s1.jips IS DISTINCT FROM s2.jips)                         AS razlicit_oib,
       count(*) FILTER (WHERE r.bc_subject_version_id IS NULL)                          AS predmet_bez_subjekta,
       count(*) FILTER (WHERE r.f_subject_version_id IS NULL)                           AS objekt_bez_subjekta
  FROM (SELECT DISTINCT id, f_subject_version_id, bc_subject_version_id FROM t_redovi) r
  LEFT JOIN str.subject_version sv1 ON sv1.id = r.f_subject_version_id
  LEFT JOIN str.subject s1          ON s1.id  = sv1.subject_id
  LEFT JOIN str.subject_version sv2 ON sv2.id = r.bc_subject_version_id
  LEFT JOIN str.subject s2          ON s2.id  = sv2.subject_id;

-- Q4b · do 20 primjera s različitim OIB-om (samo id-evi)
SELECT DISTINCT r.id AS facility_id, r.bc_id, r.f_subject_version_id, r.bc_subject_version_id
  FROM t_redovi r
  LEFT JOIN str.subject_version sv1 ON sv1.id = r.f_subject_version_id
  LEFT JOIN str.subject s1          ON s1.id  = sv1.subject_id
  LEFT JOIN str.subject_version sv2 ON sv2.id = r.bc_subject_version_id
  LEFT JOIN str.subject s2          ON s2.id  = sv2.subject_id
 WHERE s1.jips IS DISTINCT FROM s2.jips
 ORDER BY r.id
 LIMIT 20;


-- ---------------------------------------------------------------------------------------------------
-- Priprema za Q5–Q7 · OIB-ovi (testni + 3 s najviše objekata + vlasnik W-8 objekta) i sadašnji popis
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP TABLE t_top AS
SELECT s.jips AS oib, count(DISTINCT r.id) AS objekata
  FROM t_redovi r
  JOIN str.subject_version sv ON sv.id = r.f_subject_version_id
  JOIN str.subject s          ON s.id  = sv.subject_id
 GROUP BY s.jips
 ORDER BY count(DISTINCT r.id) DESC
 LIMIT 3;

CREATE TEMP TABLE t_oibi AS
SELECT x.oznaka, x.oib
  FROM (VALUES ('test-1', '06756460531'), ('test-2', '12312312316'), ('test-3', '98765432106')) x(oznaka, oib)
UNION
SELECT 'top-' || row_number() OVER (ORDER BY objekata DESC), oib FROM t_top
UNION
SELECT DISTINCT 'W-8', s.jips
  FROM str.facility f
  JOIN str.subject_version sv ON sv.id = f.subject_version_id
  JOIN str.subject s          ON s.id  = sv.subject_id
 WHERE f.system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9';

-- svi zapisi objekata tih OIB-ova (kao danas: facility.subject_version_id → subject.jips)
CREATE TEMP TABLE t_kandidati AS
SELECT DISTINCT o.oznaka, f.id, f.system_uuid::text AS system_uuid
  FROM t_oibi o
  JOIN str.subject s          ON s.jips = o.oib
  JOIN str.subject_version sv ON sv.subject_id = s.id
  JOIN str.facility f         ON f.subject_version_id = sv.id;

-- filtri koje zadržavamo i u novom popisu: aktivan zapis, FBS_ACTIVE (W-5), podvrsta smještaja
CREATE TEMP TABLE t_prikazivo AS
SELECT DISTINCT k.id
  FROM t_kandidati k
  JOIN str.facility f ON f.id = k.id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
  LEFT JOIN str.codebook_element c_st  ON c_st.id  = f.business_status_id
 WHERE f.active = true
   AND c_st.code = 'FBS_ACTIVE'
   AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR');

-- sadašnji popis = StrFacilityRepository.LISTING_FROM (dedup po uuid / predmetu, unutar OIB-a)
CREATE TEMP TABLE t_sadasnji AS
SELECT r.oznaka, f.id, f.system_uuid::text AS system_uuid
  FROM (SELECT k.oznaka, k.id AS fid,
               row_number() OVER (
                   PARTITION BY k.oznaka,
                                coalesce(cast(f.system_uuid AS varchar),
                                         cast(d.business_case_id AS varchar),
                                         'facility-' || cast(f.id AS varchar))
                   ORDER BY f.id DESC) AS rnk
          FROM t_kandidati k
          JOIN str.facility f      ON f.id = k.id
          LEFT JOIN str.document d ON d.id = f.document_id) r
  JOIN str.facility f ON f.id = r.fid
 WHERE r.rnk = 1
   AND r.fid IN (SELECT id FROM t_prikazivo)
   AND NOT EXISTS (SELECT 1 FROM str.facility f2
                    WHERE f2.system_uuid = f.system_uuid AND f2.id > f.id);


-- ---------------------------------------------------------------------------------------------------
-- Q5 · sadašnji popis naspram novog, po OIB-u
-- ---------------------------------------------------------------------------------------------------
SELECT o.oznaka,
       (SELECT count(*) FROM t_kandidati k WHERE k.oznaka = o.oznaka)                    AS zapisa_ukupno,
       (SELECT count(*) FROM t_sadasnji s WHERE s.oznaka = o.oznaka)                     AS sadasnji_popis,
       (SELECT count(*) FROM t_kandidati k WHERE k.oznaka = o.oznaka
           AND k.id IN (SELECT id FROM t_prikazivo) AND k.id IN (SELECT id FROM t_verif))         AS novi_verificirani,
       (SELECT count(*) FROM t_kandidati k WHERE k.oznaka = o.oznaka
           AND k.id IN (SELECT id FROM t_prikazivo) AND k.id IN (SELECT id FROM t_neverif_alt))   AS novi_neverif_alt,
       (SELECT count(*) FROM t_kandidati k WHERE k.oznaka = o.oznaka
           AND k.id IN (SELECT id FROM t_prikazivo) AND k.id IN (SELECT id FROM t_neverif_simon)) AS novi_neverif_simon,
       (SELECT count(*) FROM t_sadasnji s WHERE s.oznaka = o.oznaka
           AND s.id NOT IN (SELECT id FROM t_verif) AND s.id NOT IN (SELECT id FROM t_neverif_alt)) AS nestaje_iz_popisa,
       (SELECT count(*) FROM t_kandidati k WHERE k.oznaka = o.oznaka
           AND k.id IN (SELECT id FROM t_prikazivo)
           AND (k.id IN (SELECT id FROM t_verif) OR k.id IN (SELECT id FROM t_neverif_alt))
           AND k.id NOT IN (SELECT id FROM t_sadasnji))                                  AS novo_u_popisu
  FROM t_oibi o
 ORDER BY o.oznaka;

-- Q5b · zašto objekti iz sadašnjeg popisa nestaju (prvi razlog koji vrijedi, redom uvjeta iz viewa)
WITH nestaje AS (
    SELECT s.oznaka, s.id
      FROM t_sadasnji s
     WHERE s.id NOT IN (SELECT id FROM t_verif)
       AND s.id NOT IN (SELECT id FROM t_neverif_alt))
SELECT n.oznaka,
       CASE
           WHEN f.created_by IS NULL                                   THEN 'created_by je NULL'
           WHEN f.system_uuid IS NULL                                  THEN 'nema system_uuid'
           WHEN coalesce(f.historical, false)                          THEN 'historical = true'
           WHEN d.id IS NULL OR NOT coalesce(d.active, false)          THEN 'nema aktivnog dokumenta'
           WHEN NOT EXISTS (SELECT 1 FROM str.sif_podvrsta_dokumenta ds
                              JOIN str.sif_vrsta_dokumenata dt
                                ON dt.code::text = ds.vrsta_dokumenata_code::text
                             WHERE ds.code::text = d.subtype_code::text
                               AND dt.code::text IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU'))
                                                                       THEN 'vrsta dokumenta: ' || coalesce(d.subtype_code::text, 'NULL')
           WHEN bc.id IS NULL OR NOT coalesce(bc.active, false)        THEN 'nema aktivnog predmeta'
           WHEN bcs.code IS DISTINCT FROM 'BCST_RJES_IZVRSNO'          THEN 'status predmeta: ' || coalesce(bcs.code::text, 'NULL')
           WHEN d.execution_date IS NULL                               THEN 'nema datuma izvršnosti'
           WHEN d.execution_date >= now()                              THEN 'izvršnost u budućnosti'
           WHEN NOT EXISTS (SELECT 1 FROM str.organizational_unit ou
                             WHERE ou.id = bc.jurisdiction_organizational_unit_id)
                                                                       THEN 'predmet bez organizational_unit'
           ELSE 'verifikacija ili više redaka (HAVING count = 1)'
       END AS razlog,
       count(*) AS objekata
  FROM nestaje n
  JOIN str.facility f ON f.id = n.id
  LEFT JOIN str.document d             ON d.id   = f.document_id
  LEFT JOIN str.business_case bc       ON bc.id  = d.business_case_id
  LEFT JOIN str.codebook_element bcs   ON bcs.id = bc.status_type_id
 GROUP BY 1, 2
 ORDER BY 1, 3 DESC;

-- Q5c · do 50 primjera nestalih objekata (id-evi i stanje, bez osobnih podataka)
SELECT s.oznaka, s.id AS facility_id, f.created_by, f.historical,
       d.active AS dok_aktivan, d.subtype_code AS dok_podvrsta, d.execution_date AS izvrsnost,
       bc.active AS predmet_aktivan, bcs.code AS status_predmeta,
       (SELECT string_agg(coalesce(ce.code::text, '?'), ',')
          FROM str.business_case_verification v
          LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
         WHERE v.unverified_business_case_id = bc.id)                      AS verif_kao_izvor,
       (SELECT string_agg(coalesce(ce.code::text, '?'), ',')
          FROM str.business_case_verification v
          LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
         WHERE v.verified_business_case_id = bc.id)                        AS verif_kao_cilj,
       (SELECT count(*) FROM str.facility x WHERE x.system_uuid = f.system_uuid) AS zapisa_istog_uuid,
       (SELECT string_agg(t.id::text, ',') FROM t_verif t
         WHERE t.system_uuid = s.system_uuid)                              AS novi_verif_id_istog_uuid,
       (SELECT string_agg(t.id::text, ',') FROM t_neverif_alt t
         WHERE t.system_uuid = s.system_uuid)                              AS novi_neverif_id_istog_uuid
  FROM t_sadasnji s
  JOIN str.facility f ON f.id = s.id
  LEFT JOIN str.document d           ON d.id   = f.document_id
  LEFT JOIN str.business_case bc     ON bc.id  = d.business_case_id
  LEFT JOIN str.codebook_element bcs ON bcs.id = bc.status_type_id
 WHERE s.id NOT IN (SELECT id FROM t_verif)
   AND s.id NOT IN (SELECT id FROM t_neverif_alt)
 ORDER BY s.oznaka, s.id
 LIMIT 50;


-- ---------------------------------------------------------------------------------------------------
-- Q6 · W-8: sve verzije objekta 12bcff39-… i koju bira koja logika
--      (TuRegistar: Nin, Ulica Vile Velebita 4, oznaka „1”, 4 + 2 kreveta; STR je prikazao naziv „2”,
--      kućni broj 6)
-- ---------------------------------------------------------------------------------------------------
SELECT f.id, f.active, f.historical, f.created_by, f.name, f.registration_number, f.created_date,
       d.id AS dokument, d.active AS dok_aktivan, d.subtype_code AS dok_podvrsta, d.execution_date AS izvrsnost,
       bc.id AS predmet, bc.active AS predmet_aktivan, bcs.code AS status_predmeta,
       c_st.code AS poslovni_status,
       f.same_address_subject,
       coalesce(hn.name, a.house_number) AS kucni_broj,
       a.full_address,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
           AND ce.code = 'CAT_BROJ_KREVETA')                                AS kreveti,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND coalesce(fc.active, true) = true
           AND ce.code = 'CAT_BROJ_POM_KREVETA')                            AS pomocni_kreveti,
       f.id IN (SELECT id FROM t_verif)                                     AS u_verif,
       f.id IN (SELECT id FROM t_neverif_alt)                               AS u_neverif_alt,
       f.id IN (SELECT id FROM t_neverif_simon)                             AS u_neverif_simon,
       f.id IN (SELECT f_id FROM str.vw_src_facility_actual
                 WHERE f_system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9') AS u_viewu,
       f.id IN (SELECT id FROM t_sadasnji)                                  AS u_sadasnjem_popisu
  FROM str.facility f
  LEFT JOIN str.document d           ON d.id    = f.document_id
  LEFT JOIN str.business_case bc     ON bc.id   = d.business_case_id
  LEFT JOIN str.codebook_element bcs ON bcs.id  = bc.status_type_id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.address a
         ON a.id = CASE WHEN f.same_address_subject = true
                        THEN (SELECT max(x.address_id) FROM str.subject_address x
                               WHERE x.subject_version_id = f.subject_version_id
                                 AND coalesce(x.active, true) = true)
                        ELSE f.address_id END
  LEFT JOIN str.house_number hn ON hn.id = a.house_number_id
 WHERE f.system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9'
 ORDER BY f.id;


-- ---------------------------------------------------------------------------------------------------
-- Q7 · brzina (poslati cijeli ispis planova)
--   a) view izravno, filtriran po objektima OIB-a
--   b) naša kopija uvjeta ograničena na OIB (oblik upita koji bi išao u aplikaciju)
--   Svaki se pokreće za OIB s najviše objekata (top-1) i za test-1.
-- ---------------------------------------------------------------------------------------------------
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT v.f_id
  FROM str.vw_src_facility_actual v
 WHERE v.f_id IN (SELECT f.id FROM str.facility f
                    JOIN str.subject_version sv ON sv.id = f.subject_version_id
                    JOIN str.subject s          ON s.id  = sv.subject_id
                   WHERE s.jips = (SELECT oib FROM t_oibi WHERE oznaka = 'top-1'));

EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT v.f_id
  FROM str.vw_src_facility_actual v
 WHERE v.f_id IN (SELECT f.id FROM str.facility f
                    JOIN str.subject_version sv ON sv.id = f.subject_version_id
                    JOIN str.subject s          ON s.id  = sv.subject_id
                   WHERE s.jips = '06756460531');

EXPLAIN (ANALYZE, BUFFERS)
SELECT facility.id,
       bool_and(facility.created_by::text <> 'optimit') AS verificiran
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
 WHERE facility.id IN (SELECT f.id FROM str.facility f
                         JOIN str.subject_version sv ON sv.id = f.subject_version_id
                         JOIN str.subject s          ON s.id  = sv.subject_id
                        WHERE s.jips = (SELECT oib FROM t_oibi WHERE oznaka = 'top-1'))
   AND facility.active
   AND facility.created_by IS NOT NULL
   AND (verification_source.id IS NULL OR verification_source_status.code::text = 'BCVS_U_IZRADI')
   AND (verification_target.id IS NULL OR verification_target_status.code::text = 'BCVS_ZAVRSENA')
   AND (facility.historical IS NULL OR facility.historical = false)
   AND bc_status_ce.code::text = 'BCST_RJES_IZVRSNO'
   AND document.execution_date IS NOT NULL
   AND document.execution_date < now()
   AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
 GROUP BY facility.id
HAVING count(facility.system_uuid) = 1;

EXPLAIN (ANALYZE, BUFFERS)
SELECT facility.id,
       bool_and(facility.created_by::text <> 'optimit') AS verificiran
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
 WHERE facility.id IN (SELECT f.id FROM str.facility f
                         JOIN str.subject_version sv ON sv.id = f.subject_version_id
                         JOIN str.subject s          ON s.id  = sv.subject_id
                        WHERE s.jips = '06756460531')
   AND facility.active
   AND facility.created_by IS NOT NULL
   AND (verification_source.id IS NULL OR verification_source_status.code::text = 'BCVS_U_IZRADI')
   AND (verification_target.id IS NULL OR verification_target_status.code::text = 'BCVS_ZAVRSENA')
   AND (facility.historical IS NULL OR facility.historical = false)
   AND bc_status_ce.code::text = 'BCST_RJES_IZVRSNO'
   AND document.execution_date IS NOT NULL
   AND document.execution_date < now()
   AND EXISTS (SELECT 1 FROM str.organizational_unit ou
                WHERE ou.id = business_case.jurisdiction_organizational_unit_id)
 GROUP BY facility.id
HAVING count(facility.system_uuid) = 1;

-- kraj · TEMP tablice nestaju zatvaranjem sesije
