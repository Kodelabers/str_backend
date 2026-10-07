-- =====================================================================================================
-- M-1 · neverificirani objekti, 2. krug: objekti u verifikaciji
--
-- SAMO ČITANJE nad shemom str. Ne stvara ništa, svaki blok je samostalan.
--
-- KAKO POKRENUTI
--   * DBeaver: zalijepiti cijelu skriptu u SQL editor i pokrenuti Alt+X (Execute SQL Script).
--     Svaki blok dobije svoj tab rezultata. Pojedini blok: kursor u njega pa Ctrl+Enter.
--   * CDU test (ondje su objekti iz 1. kruga). Na preprod / CDU preprod prvo izvršiti: SET ROLE str_owner
--   * Ako blok padne, poslati grešku i nastaviti sa sljedećim.
--
-- NALAZ 1. KRUGA (results/m1-nerverificirani, CDU test, 7. 10. 2026.)
--   Oba objekta koja TuStart prikazuje kao neverificirane NISU migrirana (created_by nije optimit).
--   Stvoreni su 7. 10. 2026. u predmetu 803011 (status BCST_U_RJESAVANJU), a taj je predmet CILJ
--   verifikacije u statusu BCVS_U_IZRADI. Naš upit, eTurizmov view i kolegina varijanta ih zato
--   izbacuju (razlog 09). Ovaj krug traži IZVORNI predmet te verifikacije i vidi li ga vlasnik.
--
-- OBJEKTI — isti popis je u V1 i V2:
--   bb6ccf4f-2f63-435e-a004-d5d3f443a9ef
--   f2d71455-a081-4dc9-9e84-e6e67f30a6c9
-- =====================================================================================================


-- V0 · okolina
SELECT 'V0' AS blok, current_database() AS baza, current_user AS korisnik,
       inet_server_addr() AS server, now() AS vrijeme;


-- ---------------------------------------------------------------------------------------------------
-- V1 · Verifikacije u kojima je predmet ovih objekata cilj: izvorni predmet, njegovo stanje i
--      koliko zapisa ima, te je li vlasnik izvora isti kao vlasnik cilja (OIB se ne ispisuje).
--      verifikacija_redak = cijeli redak business_case_verification (sva polja, i datumi).
-- ---------------------------------------------------------------------------------------------------
SELECT 'V1' AS blok, q.*
  FROM (SELECT v.id                                                   AS verifikacija_id,
               ce.code                                                AS status_verifikacije,
               v.unverified_business_case_id                          AS izvor_bc,
               v.verified_business_case_id                            AS cilj_bc,
               bs.active                                              AS izvor_aktivan,
               (SELECT code FROM str.codebook_element WHERE id = bs.status_type_id) AS izvor_status,
               bt.active                                              AS cilj_aktivan,
               (SELECT code FROM str.codebook_element WHERE id = bt.status_type_id) AS cilj_status,
               (SELECT count(*) FROM str.facility f JOIN str.document d ON d.id = f.document_id
                 WHERE d.business_case_id = bs.id)                    AS izvor_zapisa,
               (SELECT count(*) FROM str.facility f JOIN str.document d ON d.id = f.document_id
                 WHERE d.business_case_id = bs.id AND f.created_by = 'optimit') AS izvor_zapisa_optimit,
               (SELECT count(DISTINCT f.system_uuid) FROM str.facility f JOIN str.document d ON d.id = f.document_id
                 WHERE d.business_case_id = bs.id)                    AS izvor_objekata,
               (SELECT count(*) FROM str.facility f JOIN str.document d ON d.id = f.document_id
                 WHERE d.business_case_id = bt.id)                    AS cilj_zapisa,
               (SELECT s.jips FROM str.subject_version sv JOIN str.subject s ON s.id = sv.subject_id
                 WHERE sv.id = bs.subject_version_id)
                 IS NOT DISTINCT FROM
               (SELECT s.jips FROM str.subject_version sv JOIN str.subject s ON s.id = sv.subject_id
                 WHERE sv.id = bt.subject_version_id)                 AS isti_vlasnik_izvor_cilj,
               (SELECT s.jips IS NOT NULL FROM str.subject_version sv JOIN str.subject s ON s.id = sv.subject_id
                 WHERE sv.id = bs.subject_version_id)                 AS izvor_ima_oib,
               to_jsonb(v)                                            AS verifikacija_redak
          FROM str.business_case_verification v
          LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
          LEFT JOIN str.business_case bs    ON bs.id = v.unverified_business_case_id
          LEFT JOIN str.business_case bt    ON bt.id = v.verified_business_case_id
         WHERE v.verified_business_case_id IN
               (SELECT d.business_case_id FROM str.facility f JOIN str.document d ON d.id = f.document_id
                 WHERE f.system_uuid IN ('bb6ccf4f-2f63-435e-a004-d5d3f443a9ef'::uuid,
                                         'f2d71455-a081-4dc9-9e84-e6e67f30a6c9'::uuid))) q
 ORDER BY q.verifikacija_id;


-- ---------------------------------------------------------------------------------------------------
-- V2 · Objekti IZVORNOG predmeta (iz V1), isti stupci i razlozi kao N2 u 1. krugu.
--      Ako su ovdje migrirani zapisi s razlogom „00 PRIKAZUJE SE”, STR vlasniku prikazuje staru
--      (migriranu) verziju kao neverificiranu, a TuStart novu verziju iz verifikacije.
--      Ako je V2 prazan, izvorni predmet nema zapisa objekata.
-- ---------------------------------------------------------------------------------------------------
SELECT 'V2' AS blok, q.*
  FROM (WITH ids(su) AS (
            SELECT DISTINCT fs.system_uuid
              FROM str.facility fc0
              JOIN str.document dc0                   ON dc0.id = fc0.document_id
              JOIN str.business_case_verification v0 ON v0.verified_business_case_id = dc0.business_case_id
              JOIN str.document ds0                   ON ds0.business_case_id = v0.unverified_business_case_id
              JOIN str.facility fs                    ON fs.document_id = ds0.id
             WHERE fc0.system_uuid IN ('bb6ccf4f-2f63-435e-a004-d5d3f443a9ef'::uuid,
                                       'f2d71455-a081-4dc9-9e84-e6e67f30a6c9'::uuid)
               AND fs.system_uuid IS NOT NULL),
        z AS (
            SELECT f.system_uuid,
                   f.id,
                   CASE WHEN f.created_by IS NULL               THEN '(NULL)'
                        WHEN f.created_by = 'optimit'           THEN 'optimit'
                        WHEN f.created_by ILIKE '%optimit%'     THEN 'slično: ' || f.created_by
                        ELSE 'ostalo' END                                         AS autor,
                   f.created_date,
                   f.active                                                       AS f_aktivan,
                   f.historical,
                   c_st.code                                                      AS poslovni_status,
                   c_sub.code                                                     AS podvrsta,
                   f.document_id,
                   d.active                                                       AS dok_aktivan,
                   d.subtype_code                                                 AS dok_podvrsta,
                   dt.code                                                        AS dok_vrsta,
                   d.execution_date                                               AS izvrsnost,
                   bc.id                                                          AS bc_id,
                   bc.active                                                      AS bc_aktivan,
                   bcs.code                                                       AS bc_status,
                   EXISTS (SELECT 1 FROM str.organizational_unit ou
                            WHERE ou.id = bc.jurisdiction_organizational_unit_id)  AS ima_org_jedinicu,
                   (SELECT string_agg(coalesce(ce.code::text, '-'), ',' ORDER BY v.id)
                      FROM str.business_case_verification v
                      LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
                     WHERE v.unverified_business_case_id = bc.id)                  AS verif_kao_izvor,
                   (SELECT string_agg(coalesce(ce.code::text, '-'), ',' ORDER BY v.id)
                      FROM str.business_case_verification v
                      LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
                     WHERE v.verified_business_case_id = bc.id)                    AS verif_kao_cilj,
                   (SELECT count(*) FROM str.business_case_verification v
                     WHERE v.unverified_business_case_id = bc.id)                  AS n_izvor,
                   (SELECT count(*) FROM str.business_case_verification v
                      JOIN str.codebook_element ce ON ce.id = v.status_id
                     WHERE v.unverified_business_case_id = bc.id
                       AND ce.code = 'BCVS_U_IZRADI')                              AS n_izvor_ok,
                   (SELECT count(*) FROM str.business_case_verification v
                     WHERE v.verified_business_case_id = bc.id)                    AS n_cilj,
                   (SELECT count(*) FROM str.business_case_verification v
                      JOIN str.codebook_element ce ON ce.id = v.status_id
                     WHERE v.verified_business_case_id = bc.id
                       AND ce.code = 'BCVS_ZAVRSENA')                              AS n_cilj_ok,
                   (SELECT count(*) FROM str.business_case_verification v
                      LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
                     WHERE v.verified_business_case_id = bc.id
                       AND ce.code IS DISTINCT FROM 'BCVS_ZAVRSENA')               AS n_cilj_nije_zavrsena,
                   bc.subject_version_id IS NOT NULL                              AS ima_vlasnika_predmeta,
                   (SELECT s.jips FROM str.subject_version sv JOIN str.subject s ON s.id = sv.subject_id
                     WHERE sv.id = bc.subject_version_id)
                     IS NOT DISTINCT FROM
                   (SELECT s.jips FROM str.subject_version sv JOIN str.subject s ON s.id = sv.subject_id
                     WHERE sv.id = f.subject_version_id)                          AS isti_vlasnik_predmet_i_zapis,
                   f.id IN (SELECT vw.f_id FROM str.vw_src_facility_actual vw
                             WHERE vw.f_system_uuid = f.system_uuid)               AS u_viewu_etf
              FROM ids
              JOIN str.facility f                        ON f.system_uuid = ids.su
              LEFT JOIN str.document d                   ON d.id = f.document_id
              LEFT JOIN str.sif_podvrsta_dokumenta ds    ON ds.code = d.subtype_code
              LEFT JOIN str.sif_vrsta_dokumenata dt      ON dt.code = ds.vrsta_dokumenata_code
              LEFT JOIN str.business_case bc             ON bc.id = d.business_case_id
              LEFT JOIN str.codebook_element bcs         ON bcs.id = bc.status_type_id
              LEFT JOIN str.codebook_element c_st        ON c_st.id = f.business_status_id
              LEFT JOIN str.facility_type ft
                     ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                                  WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
              LEFT JOIN str.codebook_element c_sub       ON c_sub.id = ft.sub_type_id),
        -- broj redaka koji bi u aplikaciji prošao join s verifikacijom (HAVING count(su) = 1)
        z1 AS (
            SELECT z.*,
                   (CASE WHEN n_izvor = 0 THEN 1 ELSE n_izvor_ok END)
                 * (CASE WHEN n_cilj  = 0 THEN 1 ELSE n_cilj_ok  END)             AS redaka_verifikacije,
                   (autor = 'optimit'
                    OR (bc_status = 'BCST_RJES_IZVRSNO'
                        AND izvrsnost IS NOT NULL AND izvrsnost < now()))         AS predmet_gotov_ili_migriran
              FROM z),
        z2 AS (
            SELECT z1.*,
                   coalesce(f_aktivan, false)
               AND NOT coalesce(historical, false)
               AND autor <> '(NULL)'
               AND coalesce(dok_aktivan, false)
               AND coalesce(dok_vrsta IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU'), false)
               AND coalesce(bc_aktivan, false)
               AND ima_org_jedinicu
               AND redaka_verifikacije = 1
               AND coalesce(predmet_gotov_ili_migriran, false)                    AS osnova_ok,
                   (autor = 'optimit' AND n_cilj_nije_zavrsena + (n_cilj = 0)::int > 0) AS simon_varijanta
              FROM z1),
        z3 AS (
            SELECT z2.*,
                   CASE WHEN osnova_ok
                        THEN max(created_date) OVER (PARTITION BY system_uuid, osnova_ok, bc_id) END AS predmet_zadnji
              FROM z2),
        z4 AS (
            SELECT z3.*,
                   CASE WHEN osnova_ok
                        THEN dense_rank() OVER (PARTITION BY system_uuid, osnova_ok
                                                ORDER BY predmet_zadnji DESC NULLS LAST, bc_id DESC) END AS predmet_rang
              FROM z3)
        SELECT CASE
                   WHEN NOT coalesce(f_aktivan, false)                                  THEN '01 facility.active nije true'
                   WHEN coalesce(historical, false)                                     THEN '02 historical = true'
                   WHEN autor = '(NULL)'                                                THEN '03 created_by je NULL'
                   WHEN NOT coalesce(dok_aktivan, false)                                THEN '04 nema aktivnog dokumenta'
                   WHEN NOT coalesce(dok_vrsta IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU'), false)
                                                                                        THEN '05 vrsta dokumenta nije rješenje/potvrda'
                   WHEN NOT coalesce(bc_aktivan, false)                                 THEN '06 predmet nije aktivan'
                   WHEN NOT ima_org_jedinicu                                            THEN '07 predmet bez org. jedinice'
                   WHEN n_izvor > 0 AND n_izvor_ok = 0                                  THEN '08 izvor verifikacije, status nije U_IZRADI'
                   WHEN n_cilj > 0 AND n_cilj_ok = 0                                    THEN '09 cilj verifikacije, status nije ZAVRSENA'
                   WHEN redaka_verifikacije <> 1                                        THEN '10 više redaka verifikacije (HAVING = 1)'
                   WHEN NOT coalesce(predmet_gotov_ili_migriran, false)                 THEN '11 nije optimit, predmet nije gotov'
                   WHEN predmet_rang <> 1                                               THEN '12 skriven iza novijeg predmeta'
                   WHEN poslovni_status IS DISTINCT FROM 'FBS_ACTIVE'                   THEN '13 poslovni status nije FBS_ACTIVE'
                   WHEN podvrsta IS NULL OR podvrsta NOT IN ('FS_SOBA', 'FS_APARTMAN',
                                                             'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')
                                                                                        THEN '14 vrsta nije privatni smještaj'
                   WHEN NOT ima_vlasnika_predmeta                                       THEN '15 predmet nema vlasnika'
                   ELSE '00 PRIKAZUJE SE'
               END AS razlog,
               z4.*
          FROM z4) q
 ORDER BY q.system_uuid, q.created_date DESC NULLS LAST, q.id;


-- ---------------------------------------------------------------------------------------------------
-- V3 · Koliko je na ovoj okolini zapisa kao ova dva: u predmetu koji je CILJ verifikacije koja nije
--      završena. Po autoru i po tome bi li inače prošli ostale uvjete (aktivan, nije povijesni,
--      FBS_ACTIVE, privatni smještaj). To je skup koji TuStart, izgleda, zove neverificiranim.
-- ---------------------------------------------------------------------------------------------------
SELECT 'V3' AS blok, q.*
  FROM (SELECT ce.code                                                          AS status_verifikacije,
               CASE WHEN f.created_by = 'optimit' THEN 'optimit'
                    WHEN f.created_by IS NULL THEN '(NULL)' ELSE 'ostalo' END   AS autor,
               count(DISTINCT v.id)                                             AS verifikacija,
               count(*)                                                         AS zapisa,
               count(DISTINCT f.system_uuid)                                    AS objekata,
               count(*) FILTER (WHERE f.active = true
                                  AND coalesce(f.historical, false) = false
                                  AND c_st.code = 'FBS_ACTIVE'
                                  AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN',
                                                     'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')) AS zapisa_za_popis,
               min(f.created_date)                                              AS najstariji,
               max(f.created_date)                                              AS najnoviji
          FROM str.business_case_verification v
          LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
          JOIN str.document d               ON d.business_case_id = v.verified_business_case_id
          JOIN str.facility f               ON f.document_id = d.id
          LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
          LEFT JOIN str.facility_type ft
                 ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                              WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
          LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
         GROUP BY 1, 2) q
 ORDER BY q.status_verifikacije, q.autor;


-- ---------------------------------------------------------------------------------------------------
-- V4 · Kakvi su izvorni predmeti verifikacija. U 1. krugu (N4b) 460 verifikacija ima izvor bez
--      ijednog migriranog zapisa — ovdje se vidi imaju li ti izvori uopće zapise objekata, jesu li
--      aktivni i dijele li objekte (system_uuid) s ciljem. dijeli_objekt = verifikacija zadržava
--      isti system_uuid, pa bi naš rang predmeta novu verziju vezao uz staru.
-- ---------------------------------------------------------------------------------------------------
SELECT 'V4' AS blok, q.*
  FROM (SELECT ce.code                                                          AS status_verifikacije,
               EXISTS (SELECT 1 FROM str.facility f JOIN str.document d ON d.id = f.document_id
                        WHERE d.business_case_id = v.unverified_business_case_id
                          AND f.created_by = 'optimit')                         AS izvor_ima_optimit,
               EXISTS (SELECT 1 FROM str.facility f JOIN str.document d ON d.id = f.document_id
                        WHERE d.business_case_id = v.unverified_business_case_id) AS izvor_ima_zapise,
               bs.active                                                        AS izvor_aktivan,
               EXISTS (SELECT 1
                         FROM str.facility fs JOIN str.document ds ON ds.id = fs.document_id
                         JOIN str.facility ft2 ON ft2.system_uuid = fs.system_uuid
                         JOIN str.document dt2 ON dt2.id = ft2.document_id
                        WHERE ds.business_case_id = v.unverified_business_case_id
                          AND dt2.business_case_id = v.verified_business_case_id) AS dijeli_objekt,
               count(*)                                                         AS verifikacija
          FROM str.business_case_verification v
          LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
          LEFT JOIN str.business_case bs    ON bs.id = v.unverified_business_case_id
         GROUP BY 1, 2, 3, 4, 5) q
 ORDER BY q.status_verifikacije, q.verifikacija DESC;
