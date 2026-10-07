-- =====================================================================================================
-- M-1 · neverificirani objekti: zašto STR ne prikazuje objekt koji je u TuStartu neverificiran
--
-- SAMO ČITANJE nad shemama str i str_rn. Ne stvara ništa (ni TEMP tablice) — svaki blok je samostalan.
--
-- KAKO POKRENUTI
--   * DBeaver: zalijepiti cijelu skriptu u SQL editor i pokrenuti Alt+X (Execute SQL Script).
--     Svaki blok dobije svoj tab rezultata. Pojedini blok: kursor u njega pa Ctrl+Enter.
--   * Na okolini na kojoj TuStart prikazuje objekte kao neverificirane (javiti koja je).
--   * Na preprod / CDU preprod prvo izvršiti: SET ROLE str_owner
--   * Blokove redom. Ako jedan padne, poslati grešku i nastaviti sa sljedećim.
--   * N5a/N5b čitaju str_rn — ako padnu s „relation str_rn… does not exist”, samo to javiti.
--
-- OBJEKTI (TuStart: neverificiran) — popis je upisan u N1, N2, N3 i N5b. Kad stigne cijeli popis,
-- zamijeniti ga u sva četiri bloka (N4 i N4b ne ovise o popisu):
--   bb6ccf4f-2f63-435e-a004-d5d3f443a9ef
--   f2d71455-a081-4dc9-9e84-e6e67f30a6c9
--
-- Pravilo koje se provjerava je ono iz StrFacilityRepository (RANGIRANE_JEDINICE_OD/DO + PRIKAZ_ZA_OIB),
-- opisano u docs/ETURIZAM-OBJEKTI.md. Osobni podaci se ne ispisuju: created_by samo kao kategorija,
-- vlasnik samo kao redni broj.
-- =====================================================================================================


-- N0 · okolina
SELECT 'N0' AS blok, current_database() AS baza, current_user AS korisnik,
       inet_server_addr() AS server, now() AS vrijeme;


-- ---------------------------------------------------------------------------------------------------
-- N1 · Je li TuStartov id uopće system_uuid? Traži obje vrijednosti u svim uuid / *uid* stupcima
--      tablica sheme str. Ispisuje samo stupce u kojima je vrijednost nađena.
--      Ako je nađena samo izvan facility.system_uuid, ostali blokovi traže krivi stupac — stati i javiti.
-- ---------------------------------------------------------------------------------------------------
SELECT 'N1' AS blok, q.*
  FROM (SELECT c.table_name, c.column_name, c.data_type, v.trazeni,
               (xpath('/row/n/text()',
                      query_to_xml(format('SELECT count(*) AS n FROM %I.%I WHERE %I::text = %L',
                                          c.table_schema, c.table_name, c.column_name, v.trazeni),
                                   false, true, '')))[1]::text::bigint AS pogodaka
          FROM information_schema.columns c
          JOIN information_schema.tables t
            ON t.table_schema = c.table_schema AND t.table_name = c.table_name
           AND t.table_type = 'BASE TABLE'
         CROSS JOIN (VALUES ('bb6ccf4f-2f63-435e-a004-d5d3f443a9ef'),
                            ('f2d71455-a081-4dc9-9e84-e6e67f30a6c9')) v(trazeni)
         WHERE c.table_schema = 'str'
           AND (c.data_type = 'uuid' OR c.column_name ~* '(uuid|uid|process_instance)')) q
 WHERE q.pogodaka > 0
 ORDER BY q.trazeni, q.table_name, q.column_name;


-- ---------------------------------------------------------------------------------------------------
-- N2 · Svaki zapis (verzija) objekta, svaki uvjet zasebno, i PRVI uvjet na kojem zapis ispada.
--      Redoslijed razloga prati upit u aplikaciji:
--        01–11  osnova (uvjeti eTurizmova viewa + naše proširenje za migrirane)
--        12     skriven iza novijeg predmeta istog objekta (predmet_rang)
--        13–15  filtri popisa: poslovni status, vrsta, vlasnik predmeta
--      Zapis s razlogom „00 PRIKAZUJE SE” STR prikazuje vlasniku predmeta.
--      simon_varijanta = kolegin prijedlog (created_by = 'optimit' + cilj verifikacije nije završen),
--      za usporedbu: ako je ona true, a naš razlog 08–10, problem je u uvjetima verifikacije.
-- ---------------------------------------------------------------------------------------------------
SELECT 'N2' AS blok, q.*
  FROM (WITH ids(su) AS (VALUES ('bb6ccf4f-2f63-435e-a004-d5d3f443a9ef'::uuid),
                                ('f2d71455-a081-4dc9-9e84-e6e67f30a6c9'::uuid)),
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
-- N3 · Kontrola: DOSLOVAN fragment iz aplikacije (RANGIRANE_JEDINICE_OD/DO) za ista dva objekta.
--      Mora se slagati s N2 (zapisi s razlogom 00 i 12–15 ovdje postoje, 01–11 ne postoje).
--      Ako se ne slaže, N2 krivo oponaša aplikaciju — javiti, ne zaključivati iz N2.
-- ---------------------------------------------------------------------------------------------------
SELECT 'N3' AS blok, r.su, r.id, r.verificiran, r.bc_id, r.predmet_zadnji, r.predmet_rang,
       r.predmet_jedinica, c_st.code AS poslovni_status, c_sub.code AS podvrsta,
       (r.predmet_rang = 1
        AND c_st.code = 'FBS_ACTIVE'
        AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR')) AS na_popisu
  FROM (SELECT a.*,
               dense_rank() OVER (PARTITION BY a.su
                                  ORDER BY a.predmet_zadnji DESC NULLS LAST, a.bc_id DESC) AS predmet_rang,
               count(*) OVER (PARTITION BY a.su, a.bc_id) AS predmet_jedinica
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
                                  FROM (SELECT DISTINCT system_uuid FROM str.facility
                                         WHERE system_uuid IN ('bb6ccf4f-2f63-435e-a004-d5d3f443a9ef'::uuid,
                                                               'f2d71455-a081-4dc9-9e84-e6e67f30a6c9'::uuid)) o
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
                        HAVING count(su) = 1) x) a) r
  JOIN str.facility f ON f.id = r.id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 ORDER BY r.su, r.predmet_rang, r.id;


-- ---------------------------------------------------------------------------------------------------
-- N4 · Lijevak za CIJELI registar migriranih zapisa (created_by = 'optimit'): koliko ih ostane nakon
--      svakog uvjeta, kumulativno. Pokazuje gubi li se na ovoj okolini većina migriranih na istom
--      mjestu (onda nije problem samo ova dva objekta). Ne računa rang predmeta (12) — to je u N2.
--      Može trajati desetak sekundi.
-- ---------------------------------------------------------------------------------------------------
SELECT 'N4' AS blok, q.*
  FROM (WITH m AS (
            SELECT f.id, f.active, f.historical, f.business_status_id,
                   d.active  AS dok_aktivan,
                   dt.code   AS dok_vrsta,
                   bc.id     AS bc_id,
                   bc.active AS bc_aktivan,
                   bc.subject_version_id,
                   bc.jurisdiction_organizational_unit_id AS ou_id
              FROM str.facility f
              LEFT JOIN str.document d                ON d.id = f.document_id
              LEFT JOIN str.sif_podvrsta_dokumenta ds ON ds.code = d.subtype_code
              LEFT JOIN str.sif_vrsta_dokumenata dt   ON dt.code = ds.vrsta_dokumenata_code
              LEFT JOIN str.business_case bc          ON bc.id = d.business_case_id
             WHERE f.created_by = 'optimit'
               AND f.system_uuid IS NOT NULL),
        k AS (
            SELECT m.*,
                   coalesce(m.active, false)                                                   AS u01,
                   NOT coalesce(m.historical, false)                                           AS u02,
                   coalesce(m.dok_aktivan, false)                                              AS u04,
                   coalesce(m.dok_vrsta IN ('DOT_RJESENJE', 'DOT_POTVRDA_O_UPISU'), false)     AS u05,
                   coalesce(m.bc_aktivan, false)                                               AS u06,
                   EXISTS (SELECT 1 FROM str.organizational_unit ou WHERE ou.id = m.ou_id)     AS u07,
                   NOT EXISTS (SELECT 1 FROM str.business_case_verification v
                                 LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
                                WHERE v.unverified_business_case_id = m.bc_id)
                   OR EXISTS (SELECT 1 FROM str.business_case_verification v
                                JOIN str.codebook_element ce ON ce.id = v.status_id
                               WHERE v.unverified_business_case_id = m.bc_id
                                 AND ce.code = 'BCVS_U_IZRADI')                                AS u08,
                   NOT EXISTS (SELECT 1 FROM str.business_case_verification v
                                WHERE v.verified_business_case_id = m.bc_id)
                   OR EXISTS (SELECT 1 FROM str.business_case_verification v
                                JOIN str.codebook_element ce ON ce.id = v.status_id
                               WHERE v.verified_business_case_id = m.bc_id
                                 AND ce.code = 'BCVS_ZAVRSENA')                                AS u09,
                   (SELECT code FROM str.codebook_element WHERE id = m.business_status_id) = 'FBS_ACTIVE' AS u13,
                   m.subject_version_id IS NOT NULL                                            AS u15
              FROM m)
        SELECT count(*)                                                                    AS migriranih_zapisa,
               count(*) FILTER (WHERE u01)                                                 AS n01_active,
               count(*) FILTER (WHERE u01 AND u02)                                         AS n02_nije_historical,
               count(*) FILTER (WHERE u01 AND u02 AND u04)                                 AS n04_dokument_aktivan,
               count(*) FILTER (WHERE u01 AND u02 AND u04 AND u05)                         AS n05_vrsta_dokumenta,
               count(*) FILTER (WHERE u01 AND u02 AND u04 AND u05 AND u06)                 AS n06_predmet_aktivan,
               count(*) FILTER (WHERE u01 AND u02 AND u04 AND u05 AND u06 AND u07)         AS n07_org_jedinica,
               count(*) FILTER (WHERE u01 AND u02 AND u04 AND u05 AND u06 AND u07 AND u08) AS n08_izvor_verifikacije,
               count(*) FILTER (WHERE u01 AND u02 AND u04 AND u05 AND u06 AND u07 AND u08
                                  AND u09)                                                 AS n09_cilj_verifikacije,
               count(*) FILTER (WHERE u01 AND u02 AND u04 AND u05 AND u06 AND u07 AND u08
                                  AND u09 AND u13)                                         AS n13_fbs_active,
               count(*) FILTER (WHERE u01 AND u02 AND u04 AND u05 AND u06 AND u07 AND u08
                                  AND u09 AND u13 AND u15)                                 AS n15_ima_vlasnika
          FROM k) q;


-- ---------------------------------------------------------------------------------------------------
-- N4b · Statusi verifikacije u kojima su migrirani predmeti (izvor / cilj). Pokazuje postoje li
--       statusi osim U_IZRADI i ZAVRSENA — takvi migrirani predmet naš uvjet izbacuje (razlog 08).
-- ---------------------------------------------------------------------------------------------------
SELECT 'N4b' AS blok, q.*
  FROM (SELECT CASE WHEN v.unverified_business_case_id IN (SELECT d.business_case_id FROM str.document d
                                                            JOIN str.facility f ON f.document_id = d.id
                                                           WHERE f.created_by = 'optimit')
                    THEN 'migrirani je IZVOR' ELSE 'izvor nije migrirani' END AS uloga,
               ce.code AS status, ce.name AS naziv, count(*) AS verifikacija
          FROM str.business_case_verification v
          LEFT JOIN str.codebook_element ce ON ce.id = v.status_id
         GROUP BY 1, 2, 3) q
 ORDER BY q.uloga, q.verifikacija DESC;


-- ---------------------------------------------------------------------------------------------------
-- N5 · Kako bi popis izgledao vlasniku predmeta ovih objekata: koliko jedinica vidi, koliko
--      neverificiranih, i jesu li ova dva objekta među njima. Vlasnik je samo redni broj.
--      Vrste se čitaju iz str_rn.accommodation_type kao u aplikaciji. Ako je n_sifara 0, popis je
--      prazan za sve (v. WARN u NiasFacilityService).
-- ---------------------------------------------------------------------------------------------------
SELECT 'N5a' AS blok, count(*) AS n_sifara, string_agg(code, ', ' ORDER BY code) AS sifre
  FROM str_rn.accommodation_type
 WHERE code IS NOT NULL;


-- N5b · DOSLOVAN upit popisa (PRIKAZ_ZA_OIB) za svakog vlasnika predmeta ovih objekata.
--       ciljni_na_popisu > 0 → backend ih vraća, a tada je problem u prikazu (frontend / okolina /
--       verzija backenda), ne u upitu.
SELECT 'N5b' AS blok, vl.vlasnik,
       count(p.id)                                                          AS jedinica_na_popisu,
       count(DISTINCT p.su)                                                 AS objekata_na_popisu,
       count(p.id) FILTER (WHERE p.verificiran)                             AS verificiranih,
       count(p.id) FILTER (WHERE NOT p.verificiran)                         AS neverificiranih,
       count(p.id) FILTER (WHERE p.su IN ('bb6ccf4f-2f63-435e-a004-d5d3f443a9ef',
                                          'f2d71455-a081-4dc9-9e84-e6e67f30a6c9')) AS ciljni_na_popisu
  FROM (SELECT dense_rank() OVER (ORDER BY o.jips) AS vlasnik, o.jips
          FROM (SELECT DISTINCT s.jips
                  FROM str.facility f
                  JOIN str.document d         ON d.id  = f.document_id
                  JOIN str.business_case bc   ON bc.id = d.business_case_id
                  JOIN str.subject_version sv ON sv.id = bc.subject_version_id
                  JOIN str.subject s          ON s.id  = sv.subject_id
                 WHERE f.system_uuid IN ('bb6ccf4f-2f63-435e-a004-d5d3f443a9ef'::uuid,
                                         'f2d71455-a081-4dc9-9e84-e6e67f30a6c9'::uuid)
                   AND s.jips IS NOT NULL) o) vl
  LEFT JOIN LATERAL (
        SELECT r.id, r.su, r.verificiran
          FROM (SELECT a.*,
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
                                          FROM (SELECT DISTINCT f.system_uuid
                                                  FROM str.subject s
                                                  JOIN str.subject_version sv ON sv.subject_id = s.id
                                                  JOIN str.business_case bc   ON bc.subject_version_id = sv.id
                                                  JOIN str.document d         ON d.business_case_id = bc.id
                                                  JOIN str.facility f         ON f.document_id = d.id
                                                 WHERE s.jips = vl.jips
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
                                HAVING count(su) = 1) x) a) r
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
                            WHERE s.jips = vl.jips)
           AND c_st.code = 'FBS_ACTIVE'
           AND c_sub.code IN (SELECT code FROM str_rn.accommodation_type WHERE code IS NOT NULL)
      ) p ON true
 GROUP BY vl.vlasnik
 ORDER BY vl.vlasnik;
