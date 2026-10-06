SELECT 'B0a' AS blok, q.* FROM (
SELECT current_database() AS baza, current_user AS korisnik, inet_server_addr() AS server, now() AS vrijeme,
       to_regclass('str.vw_src_facility_actual')     IS NOT NULL AS ima_view_actual,
       to_regclass('str.vw_src_facility_historical') IS NOT NULL AS ima_view_historical,
       md5(pg_get_viewdef(to_regclass('str.vw_src_facility_actual'), true)) AS actual_md5
) q;

SELECT 'B0b' AS blok, q.* FROM (
SELECT 'actual' AS view, pg_get_viewdef(to_regclass('str.vw_src_facility_actual'), true) AS definicija
UNION ALL
SELECT 'historical', pg_get_viewdef(to_regclass('str.vw_src_facility_historical'), true)
) q;

SELECT 'B0c' AS blok, q.* FROM (
SELECT table_name,
       string_agg(column_name || ':' || data_type, ', ' ORDER BY ordinal_position) AS stupci
  FROM information_schema.columns
 WHERE table_schema = 'str'
   AND table_name IN ('vw_src_facility_actual', 'vw_src_facility_historical', 'facility', 'facility_unit',
                      'facility_unit_capacity', 'facility_capacity', 'address', 'subject_address')
 GROUP BY table_name
 ORDER BY table_name
) q;

SELECT 'B1-0' AS blok, q.* FROM (
SELECT count(*) AS zapisa
  FROM str.facility f
 WHERE f.system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9'
) q;

SELECT 'B1-alt' AS blok, q.* FROM (
SELECT f.id, f.system_uuid, f.created_by, f.active, f.same_address_subject,
       a.id AS address_id, 'vlastita' AS koja
  FROM str.facility f
  JOIN str.address a ON a.id = f.address_id
 WHERE a.full_address ILIKE '%vile velebit%'
UNION ALL
SELECT f.id, f.system_uuid, f.created_by, f.active, f.same_address_subject,
       a.id, 'subjekt predmeta'
  FROM str.facility f
  JOIN str.document d         ON d.id  = f.document_id
  JOIN str.business_case bc   ON bc.id = d.business_case_id
  JOIN str.subject_address sa ON sa.subject_version_id = bc.subject_version_id
  JOIN str.address a          ON a.id = sa.address_id
 WHERE f.same_address_subject = true
   AND a.full_address ILIKE '%vile velebit%'
 ORDER BY 2, 1
 LIMIT 50
) q;

SELECT 'B1a' AS blok, q.* FROM (
SELECT f.id, f.name, f.created_by, f.created_date, f.active, f.historical,
       f.same_address_subject, f.address_id, f.subject_version_id AS f_subject_version,
       bc.subject_version_id AS bc_subject_version, bc.id AS predmet, bc.active AS predmet_aktivan,
       bcs.code AS status_predmeta, d.id AS dokument, d.active AS dok_aktivan,
       to_jsonb(d) ->> 'subtype_code' AS dok_podvrsta, d.execution_date AS izvrsnost,
       c_st.code AS poslovni_status, c_sub.code AS podvrsta, f.registration_number,
       f.id = (SELECT max(f2.id) FROM str.facility f2 WHERE f2.system_uuid = f.system_uuid) AS stari_popis_bira,
       to_jsonb(f) - ARRAY['email', 'phone', 'pin', 'personal_document_number', 'web_address'] AS svi_stupci
  FROM str.facility f
  LEFT JOIN str.document d             ON d.id     = f.document_id
  LEFT JOIN str.business_case bc       ON bc.id    = d.business_case_id
  LEFT JOIN str.codebook_element bcs   ON bcs.id   = bc.status_type_id
  LEFT JOIN str.codebook_element c_st  ON c_st.id  = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 WHERE f.system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9'
 ORDER BY f.id
) q;

SELECT 'B1a2' AS blok, q.* FROM (
SELECT r.id, r.verificiran, r.bc_id, r.bc_sv, r.predmet_zadnji, r.predmet_rang
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
                                  FROM (SELECT DISTINCT system_uuid FROM str.facility
                                         WHERE system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9') o
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
 ORDER BY r.id
) q;

SELECT 'B1b' AS blok, q.* FROM (
WITH fx AS (
    SELECT f.id, f.address_id, f.subject_version_id AS f_sv, bc.subject_version_id AS bc_sv
      FROM str.facility f
      LEFT JOIN str.document d       ON d.id  = f.document_id
      LEFT JOIN str.business_case bc ON bc.id = d.business_case_id
     WHERE f.system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9'),
uloge AS (
    SELECT fx.id AS facility_id, '1 objekt (facility.address_id)' AS uloga,
           NULL::bigint AS sa_id, NULL::text AS sa_active, NULL::text AS sa_tip_id, fx.address_id,
           NULL::boolean AS bira_aplikacija, NULL::boolean AS bira_view
      FROM fx
    UNION ALL
    SELECT fx.id, '2 subjekt predmeta (bc.subject_version_id)', sa.id,
           to_jsonb(sa) ->> 'active', to_jsonb(sa) ->> 'address_type_id', sa.address_id,
           sa.address_id = (SELECT max(x.address_id) FROM str.subject_address x
                             WHERE x.subject_version_id = fx.bc_sv AND coalesce(x.active, true) = true),
           coalesce(sa.active, false)
      FROM fx JOIN str.subject_address sa ON sa.subject_version_id = fx.bc_sv
    UNION ALL
    SELECT fx.id, '3 subjekt zapisa (facility.subject_version_id, stari popis)', sa.id,
           to_jsonb(sa) ->> 'active', to_jsonb(sa) ->> 'address_type_id', sa.address_id,
           sa.address_id = (SELECT max(x.address_id) FROM str.subject_address x
                             WHERE x.subject_version_id = fx.f_sv AND coalesce(x.active, true) = true),
           NULL
      FROM fx JOIN str.subject_address sa ON sa.subject_version_id = fx.f_sv)
SELECT u.facility_id, u.uloga, u.sa_id, u.sa_active, u.sa_tip_id, ce.code AS sa_tip_sifra,
       u.bira_aplikacija, u.bira_view, u.address_id,
       a.full_address,
       a.house_number AS kbr_tekst,  hn.name  AS kbr_iz_id,  a.house_number_id,
       a.street       AS ulica_tekst, stt.name AS ulica_iz_id, a.street_id,
       a.settlement   AS naselje_tekst, se.name AS naselje_iz_id, a.settlement_id,
       coalesce(mu.name, a.municipality) AS opcina, coalesce(co.name, a.county) AS zupanija,
       a.postal_code, se.postal_code AS postanski_naselja,
       to_jsonb(a) ->> 'active' AS adresa_active
  FROM uloge u
  LEFT JOIN str.address a          ON a.id   = u.address_id
  LEFT JOIN str.house_number hn    ON hn.id  = a.house_number_id
  LEFT JOIN str.street stt         ON stt.id = a.street_id
  LEFT JOIN str.settlement se      ON se.id  = a.settlement_id
  LEFT JOIN str.municipality mu    ON mu.id  = a.municipality_id
  LEFT JOIN str.county co          ON co.id  = a.county_id
  LEFT JOIN str.codebook_element ce ON ce.id::text = u.sa_tip_id
 ORDER BY u.facility_id, u.uloga, u.sa_id
) q;

SELECT 'B1c' AS blok, q.* FROM (
SELECT fc.facility_id, 'facility_capacity' AS izvor, fc.id AS redak, NULL::bigint AS facility_unit_id,
       ce.code AS tip, fc.quantity, fc.active
  FROM str.facility_capacity fc
  LEFT JOIN str.codebook_element ce ON ce.id = fc.type_id
 WHERE fc.facility_id IN (SELECT id FROM str.facility
                           WHERE system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9')
UNION ALL
SELECT fu.facility_id, 'facility_unit_capacity', (to_jsonb(fuc) ->> 'id')::bigint, fu.id,
       ce.code, fuc.quantity, fuc.active
  FROM str.facility_unit fu
  JOIN str.facility_unit_capacity fuc ON fuc.facility_unit_id = fu.id
  LEFT JOIN str.codebook_element ce   ON ce.id = fuc.type_id
 WHERE fu.facility_id IN (SELECT id FROM str.facility
                           WHERE system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9')
 ORDER BY 1, 2, 5, 3
) q;

SELECT 'B1d' AS blok, q.* FROM (
SELECT fu.facility_id, to_jsonb(fu) AS facility_unit, tip.code AS tip
  FROM str.facility_unit fu
  LEFT JOIN str.codebook_element tip ON tip.id = fu.type_id
 WHERE fu.facility_id IN (SELECT id FROM str.facility
                           WHERE system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9')
 ORDER BY fu.facility_id, fu.id
) q;

SELECT 'B1e' AS blok, q.* FROM (
SELECT f.id, f.name, f.same_address_subject,
       a_sad.full_address                    AS adresa_danas,
       coalesce(hn_sad.name, a_sad.house_number)     AS kbr_danas,
       a_st.full_address                     AS adresa_stari_popis,
       coalesce(hn_st.name, a_st.house_number)       AS kbr_stari_popis,
       coalesce(cap.k_strogo, ucap.k_strogo) AS popis_kreveti,
       coalesce(cap.p_strogo, ucap.p_strogo) AS popis_pomocni,
       coalesce(cap.k_coal, ucap.k_coal)     AS claim_kreveti,
       coalesce(cap.p_coal, ucap.p_coal)     AS claim_pomocni,
       CASE WHEN coalesce(cap.k_coal, ucap.k_coal) > 0
            THEN coalesce(cap.k_coal, ucap.k_coal) + greatest(coalesce(cap.p_coal, ucap.p_coal, 0), 0)
       END                                   AS claim_max_gostiju,
       cap.k_sve                             AS sve_kreveti,
       cap.p_sve                             AS sve_pomocni,
       ucap.k_sve                            AS jedinice_kreveti_sve,
       ucap.p_sve                            AS jedinice_pomocni_sve
  FROM str.facility f
  LEFT JOIN str.document d       ON d.id  = f.document_id
  LEFT JOIN str.business_case bc ON bc.id = d.business_case_id
  LEFT JOIN str.address a_sad
         ON a_sad.id = CASE WHEN f.same_address_subject = true
                            THEN (SELECT max(x.address_id) FROM str.subject_address x
                                   WHERE x.subject_version_id = bc.subject_version_id
                                     AND coalesce(x.active, true) = true)
                            ELSE f.address_id END
  LEFT JOIN str.house_number hn_sad ON hn_sad.id = a_sad.house_number_id
  LEFT JOIN str.address a_st
         ON a_st.id = CASE WHEN f.same_address_subject = true
                           THEN (SELECT max(x.address_id) FROM str.subject_address x
                                  WHERE x.subject_version_id = f.subject_version_id
                                    AND coalesce(x.active, true) = true)
                           ELSE f.address_id END
  LEFT JOIN str.house_number hn_st ON hn_st.id = a_st.house_number_id
  LEFT JOIN LATERAL (
       SELECT sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA' AND fc.active = true)                  AS k_strogo,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA' AND fc.active = true)              AS p_strogo,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA' AND coalesce(fc.active, true) = true)     AS k_coal,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA' AND coalesce(fc.active, true) = true) AS p_coal,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA')                                       AS k_sve,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA')                                   AS p_sve
         FROM str.facility_capacity fc
         JOIN str.codebook_element ce ON ce.id = fc.type_id
        WHERE fc.facility_id = f.id) cap ON true
  LEFT JOIN LATERAL (
       SELECT sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA'
                                          AND fu.active = true AND fuc.active = true)                         AS k_strogo,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA'
                                          AND fu.active = true AND fuc.active = true)                         AS p_strogo,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA'
                                          AND coalesce(fu.active, true) AND coalesce(fuc.active, true))       AS k_coal,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA'
                                          AND coalesce(fu.active, true) AND coalesce(fuc.active, true))       AS p_coal,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA')                                   AS k_sve,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA')                               AS p_sve
         FROM str.facility_unit fu
         JOIN str.facility_unit_capacity fuc ON fuc.facility_unit_id = fu.id
         JOIN str.codebook_element ce        ON ce.id = fuc.type_id
        WHERE fu.facility_id = f.id) ucap ON true
 WHERE f.system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9'
 ORDER BY f.id
) q;

SELECT 'B1f' AS blok, q.* FROM (
SELECT DISTINCT 'actual' AS view,
       j ->> 'f_id' AS f_id, j ->> 'f_historical' AS f_historical,
       j ->> 'f_capacity_id' AS cap_id, j ->> 'f_capacity_type' AS cap_tip,
       j ->> 'f_capacity_type_quantity' AS cap_kol,
       j ->> 'f_unit_id' AS unit_id, j ->> 'f_unit_capacity_type' AS ucap_tip,
       j ->> 'f_unit_capacity_type_quantity' AS ucap_kol,
       j ->> 'f_adresa_objekta' AS adresa, j ->> 'f_ulica_objekta' AS ulica,
       j ->> 'f_kucni_broj_objekta' AS kbr, j ->> 'f_naselje_objekta' AS naselje
  FROM (SELECT to_jsonb(v) AS j FROM str.vw_src_facility_actual v
         WHERE v.f_system_uuid::text = '12bcff39-74e9-4696-b5f0-4444216ee8e9') x
 ORDER BY 2, 4, 6
) q;

DROP TABLE IF EXISTS pg_temp.t_b3_rang, pg_temp.t_b3_jed, pg_temp.t_b3_adr, pg_temp.t_b3_view, pg_temp.t_b3_usp;

CREATE TEMP TABLE t_b3_rang AS
SELECT a.*,
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
                          FROM str.facility facility
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
                         WHERE facility.system_uuid IS NOT NULL
                           AND facility.active = true
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
                HAVING count(su) = 1) x) a;

CREATE INDEX ON t_b3_rang (id);

ANALYZE t_b3_rang;

CREATE TEMP TABLE t_b3_jed AS
SELECT r.id, r.su, r.verificiran, r.bc_sv,
       f.name,
       f.same_address_subject                                                   AS sas,
       f.address_id                                                             AS own_addr,
       (SELECT max(x.address_id) FROM str.subject_address x
         WHERE x.subject_version_id = r.bc_sv AND coalesce(x.active, true) = true) AS subj_addr_app,
       (SELECT max(x.address_id) FROM str.subject_address x
         WHERE x.subject_version_id = r.bc_sv AND x.active = true)               AS subj_addr_strogo,
       CASE WHEN f.same_address_subject = true
            THEN (SELECT max(x.address_id) FROM str.subject_address x
                   WHERE x.subject_version_id = r.bc_sv AND coalesce(x.active, true) = true)
            ELSE f.address_id END                                               AS app_addr,
       EXISTS (SELECT 1 FROM str.facility_unit fu WHERE fu.facility_id = f.id)   AS ima_facility_unit,
       cap.ima_fc, cap.n_null, cap.n_false, cap.n_k_aktivnih, cap.n_p_aktivnih,
       cap.k_strogo, cap.p_strogo, cap.k_coal, cap.p_coal, cap.k_sve, cap.p_sve,
       ucap.ima_fuc,
       ucap.k_strogo AS uk_strogo, ucap.p_strogo AS up_strogo,
       ucap.k_coal   AS uk_coal,   ucap.p_coal   AS up_coal
  FROM t_b3_rang r
  JOIN str.facility f ON f.id = r.id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
  LEFT JOIN LATERAL (
       SELECT count(*) > 0                                                                               AS ima_fc,
              count(*) FILTER (WHERE fc.active IS NULL
                                 AND ce.code IN ('CAT_BROJ_KREVETA', 'CAT_BROJ_POM_KREVETA'))            AS n_null,
              count(*) FILTER (WHERE fc.active = false
                                 AND ce.code IN ('CAT_BROJ_KREVETA', 'CAT_BROJ_POM_KREVETA'))            AS n_false,
              count(*) FILTER (WHERE fc.active = true AND ce.code = 'CAT_BROJ_KREVETA')                  AS n_k_aktivnih,
              count(*) FILTER (WHERE fc.active = true AND ce.code = 'CAT_BROJ_POM_KREVETA')              AS n_p_aktivnih,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA' AND fc.active = true)                  AS k_strogo,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA' AND fc.active = true)              AS p_strogo,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA' AND coalesce(fc.active, true) = true)     AS k_coal,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA' AND coalesce(fc.active, true) = true) AS p_coal,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA')                                       AS k_sve,
              sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA')                                   AS p_sve
         FROM str.facility_capacity fc
         JOIN str.codebook_element ce ON ce.id = fc.type_id
        WHERE fc.facility_id = f.id) cap ON true
  LEFT JOIN LATERAL (
       SELECT count(*) > 0 AS ima_fuc,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA'
                                          AND fu.active = true AND fuc.active = true)                         AS k_strogo,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA'
                                          AND fu.active = true AND fuc.active = true)                         AS p_strogo,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA'
                                          AND coalesce(fu.active, true) AND coalesce(fuc.active, true))       AS k_coal,
              sum(fuc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA'
                                          AND coalesce(fu.active, true) AND coalesce(fuc.active, true))       AS p_coal
         FROM str.facility_unit fu
         JOIN str.facility_unit_capacity fuc ON fuc.facility_unit_id = fu.id
         JOIN str.codebook_element ce        ON ce.id = fuc.type_id
        WHERE fu.facility_id = f.id) ucap ON true
 WHERE r.predmet_rang = 1
   AND c_st.code = 'FBS_ACTIVE'
   AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR');

CREATE INDEX ON t_b3_jed (id);

CREATE INDEX ON t_b3_jed (su);

ANALYZE t_b3_jed;

CREATE TEMP TABLE t_b3_adr AS
SELECT j.id,
       a.full_address                                AS app_puna,
       coalesce(hn.name, a.house_number)             AS app_kbr,
       hn.name                                       AS app_kbr_iz_id,
       a.house_number                                AS app_kbr_tekst,
       coalesce(stt.name, a.street)                  AS app_ulica,
       stt.name                                      AS app_ulica_iz_id,
       a.street                                      AS app_ulica_tekst,
       coalesce(se.name, a.settlement)               AS app_naselje,
       to_jsonb(a) ->> 'active'                      AS app_adresa_active,
       o.full_address                                AS own_puna,
       coalesce(ohn.name, o.house_number)            AS own_kbr,
       coalesce(ose.name, o.settlement)              AS own_naselje
  FROM t_b3_jed j
  LEFT JOIN str.address a        ON a.id   = j.app_addr
  LEFT JOIN str.house_number hn  ON hn.id  = a.house_number_id
  LEFT JOIN str.street stt       ON stt.id = a.street_id
  LEFT JOIN str.settlement se    ON se.id  = a.settlement_id
  LEFT JOIN str.address o        ON o.id   = j.own_addr
  LEFT JOIN str.house_number ohn ON ohn.id = o.house_number_id
  LEFT JOIN str.settlement ose   ON ose.id = o.settlement_id;

CREATE INDEX ON t_b3_adr (id);

ANALYZE t_b3_adr;

CREATE TEMP TABLE t_b3_view AS
SELECT DISTINCT (j ->> 'f_id')::bigint                        AS f_id,
       j ->> 'f_capacity_id'                                  AS cap_id,
       j ->> 'f_capacity_type'                                AS cap_tip,
       (j ->> 'f_capacity_type_quantity')::numeric            AS cap_kol,
       j ->> 'f_unit_id'                                      AS unit_id,
       j ->> 'f_unit_capacity_type'                           AS ucap_tip,
       (j ->> 'f_unit_capacity_type_quantity')::numeric       AS ucap_kol,
       j ->> 'f_adresa_objekta'                               AS adresa,
       j ->> 'f_kucni_broj_objekta'                           AS kbr
  FROM (SELECT to_jsonb(v) AS j FROM str.vw_src_facility_actual v) x;

CREATE INDEX ON t_b3_view (f_id);

ANALYZE t_b3_view;

SELECT 'B2a' AS blok, q.* FROM (
SELECT (SELECT count(*) FROM t_b3_rang)                                          AS aktualnih_zapisa_svih,
       (SELECT count(*) FROM t_b3_jed WHERE verificiran)                         AS jedinica_verif,
       (SELECT count(DISTINCT su) FROM t_b3_jed WHERE verificiran)               AS objekata_verif,
       (SELECT count(*) FROM t_b3_jed WHERE NOT verificiran)                     AS jedinica_neverif,
       (SELECT count(DISTINCT su) FROM t_b3_jed WHERE NOT verificiran)           AS objekata_neverif,
       (SELECT count(DISTINCT f_id) FROM t_b3_view)                              AS view_zapisa,
       (SELECT count(*) FROM t_b3_view WHERE adresa IS NOT NULL)                 AS view_redaka_s_adresom
) q;

SELECT 'H1' AS blok, q.* FROM (
SELECT j.verificiran,
       count(*)                                                                    AS jedinica,
       count(DISTINCT j.su)                                                        AS objekata,
       count(*) FILTER (WHERE j.sas IS NULL)                                       AS sas_null,
       count(*) FILTER (WHERE j.sas)                                               AS sas_jedinica,
       count(DISTINCT j.su) FILTER (WHERE j.sas)                                   AS sas_objekata,
       count(*) FILTER (WHERE j.sas AND j.own_addr IS NOT NULL)                    AS sas_s_vlastitom_adresom,
       count(*) FILTER (WHERE j.sas AND j.own_addr IS NOT NULL
                          AND ad.own_puna IS DISTINCT FROM ad.app_puna)            AS sas_puna_razlicita,
       count(*) FILTER (WHERE j.sas AND j.own_addr IS NOT NULL
                          AND ad.own_kbr IS NOT NULL AND ad.app_kbr IS NOT NULL
                          AND lower(btrim(ad.own_kbr)) <> lower(btrim(ad.app_kbr))) AS sas_kbr_razlicit,
       count(*) FILTER (WHERE j.sas AND j.own_addr IS NOT NULL
                          AND ad.own_naselje IS NOT NULL AND ad.app_naselje IS NOT NULL
                          AND lower(btrim(ad.own_naselje)) <> lower(btrim(ad.app_naselje))) AS sas_naselje_razlicito,
       count(*) FILTER (WHERE j.sas AND j.app_addr IS NULL)                        AS sas_bez_adrese
  FROM t_b3_jed j
  JOIN t_b3_adr ad ON ad.id = j.id
 GROUP BY j.verificiran
 ORDER BY j.verificiran DESC
) q;

SELECT 'H2' AS blok, q.* FROM (
WITH po_jed AS (
    SELECT j.id, j.verificiran, j.subj_addr_app, j.subj_addr_strogo,
           count(sa.id)                                                       AS n,
           count(sa.id) FILTER (WHERE sa.active = true)                       AS n_true,
           count(sa.id) FILTER (WHERE sa.active IS NULL)                      AS n_null,
           count(sa.id) FILTER (WHERE sa.active = false)                      AS n_false,
           count(DISTINCT to_jsonb(sa) ->> 'address_type_id')                 AS n_tipova,
           count(DISTINCT a.full_address) FILTER (WHERE coalesce(sa.active, true)) AS n_puna_app,
           count(DISTINCT a.full_address) FILTER (WHERE sa.active = true)     AS n_puna_view
      FROM t_b3_jed j
      LEFT JOIN str.subject_address sa ON sa.subject_version_id = j.bc_sv
      LEFT JOIN str.address a          ON a.id = sa.address_id
     WHERE j.sas
     GROUP BY j.id, j.verificiran, j.subj_addr_app, j.subj_addr_strogo)
SELECT verificiran,
       count(*)                                                   AS sas_jedinica,
       count(*) FILTER (WHERE n = 0)                              AS bez_ijedne_adrese,
       count(*) FILTER (WHERE n > 1)                              AS vise_redaka,
       count(*) FILTER (WHERE n_true > 1)                         AS vise_aktivnih,
       count(*) FILTER (WHERE n_null > 0)                         AS ima_active_null,
       count(*) FILTER (WHERE n_false > 0)                        AS ima_active_false,
       count(*) FILTER (WHERE n_tipova > 1)                       AS vise_tipova_adrese,
       count(*) FILTER (WHERE n_puna_app > 1)                     AS vise_razlicitih_adresa,
       count(*) FILTER (WHERE n_puna_view > 1)                    AS vise_adresa_view,
       count(*) FILTER (WHERE subj_addr_app IS DISTINCT FROM subj_addr_strogo) AS app_ne_strogo
  FROM po_jed
 GROUP BY verificiran
 ORDER BY verificiran DESC
) q;

SELECT 'H2b' AS blok, q.* FROM (
SELECT to_jsonb(sa) ->> 'address_type_id' AS tip_id, ce.code AS tip_sifra, ce.name AS tip_naziv,
       sa.active, count(*) AS adresa
  FROM (SELECT DISTINCT bc_sv FROM t_b3_jed WHERE sas) s
  JOIN str.subject_address sa ON sa.subject_version_id = s.bc_sv
  LEFT JOIN str.codebook_element ce ON ce.id::text = to_jsonb(sa) ->> 'address_type_id'
 GROUP BY 1, 2, 3, 4
 ORDER BY 5 DESC
) q;

SELECT 'H3' AS blok, q.* FROM (
WITH po_obj AS (
    SELECT j.su,
           bool_or(j.verificiran)                         AS verificiran,
           count(*)                                       AS jedinica,
           count(DISTINCT coalesce(j.app_addr, -1))       AS n_address_id,
           count(DISTINCT coalesce(ad.app_puna, ''))      AS n_puna,
           count(DISTINCT coalesce(ad.app_kbr, ''))       AS n_kbr,
           count(DISTINCT coalesce(ad.app_naselje, ''))   AS n_naselje
      FROM t_b3_jed j
      JOIN t_b3_adr ad ON ad.id = j.id
     GROUP BY j.su
    HAVING count(*) > 1)
SELECT verificiran,
       count(*)                                     AS objekata_s_vise_jedinica,
       sum(jedinica)                                AS jedinica,
       count(*) FILTER (WHERE n_address_id > 1)     AS razlicit_address_id,
       count(*) FILTER (WHERE n_puna > 1)           AS razlicita_puna_adresa,
       count(*) FILTER (WHERE n_kbr > 1)            AS razlicit_kucni_broj,
       count(*) FILTER (WHERE n_naselje > 1)        AS razlicito_naselje
  FROM po_obj
 GROUP BY verificiran
 ORDER BY verificiran DESC
) q;

SELECT 'H3b' AS blok, q.* FROM (
SELECT j.su, j.id, j.verificiran, ad.app_kbr AS kucni_broj, j.id = min(j.id) OVER (PARTITION BY j.su) AS prva_jedinica
  FROM t_b3_jed j
  JOIN t_b3_adr ad ON ad.id = j.id
 WHERE j.su IN (SELECT j2.su
                  FROM t_b3_jed j2 JOIN t_b3_adr a2 ON a2.id = j2.id
                 GROUP BY j2.su
                HAVING count(DISTINCT coalesce(a2.app_kbr, '')) > 1
                 ORDER BY j2.su
                 LIMIT 10)
 ORDER BY j.su, j.id
) q;

SELECT 'H4' AS blok, q.* FROM (
SELECT verificiran,
       count(*)                                                                         AS jedinica,
       count(*) FILTER (WHERE n_null > 0)                                               AS ima_active_null,
       count(*) FILTER (WHERE n_false > 0)                                              AS ima_active_false,
       count(*) FILTER (WHERE n_k_aktivnih > 1)                                         AS vise_aktivnih_kreveti,
       count(*) FILTER (WHERE n_p_aktivnih > 1)                                         AS vise_aktivnih_pomocni,
       count(*) FILTER (WHERE ima_fc AND ima_fuc)                                       AS oba_izvora,
       count(*) FILTER (WHERE NOT coalesce(ima_fc, false) AND ima_fuc)                  AS samo_jedinicni,
       count(*) FILTER (WHERE coalesce(k_strogo, uk_strogo) IS DISTINCT FROM coalesce(k_coal, uk_coal)) AS kreveti_popis_ne_claim,
       count(*) FILTER (WHERE coalesce(p_strogo, up_strogo) IS DISTINCT FROM coalesce(p_coal, up_coal)) AS pomocni_popis_ne_claim,
       count(*) FILTER (WHERE coalesce(k_strogo, uk_strogo) IS NULL
                          AND coalesce(k_coal, uk_coal) > 0)                            AS crtica_popis_zakljucano_forma,
       count(*) FILTER (WHERE coalesce(k_coal, uk_coal) IS NULL)                        AS bez_kreveta,
       count(*) FILTER (WHERE k_sve IS DISTINCT FROM k_strogo)                          AS kreveti_bez_filtra_drukciji
  FROM t_b3_jed
 GROUP BY verificiran
 ORDER BY verificiran DESC
) q;

SELECT 'H5' AS blok, q.* FROM (
WITH po_obj AS (
    SELECT su,
           bool_or(verificiran)                                          AS verificiran,
           count(*)                                                      AS jedinica,
           count(*) FILTER (WHERE nullif(btrim(name), '') IS NOT NULL)   AS s_nazivom,
           count(DISTINCT nullif(btrim(name), ''))                       AS razlicitih_naziva,
           bool_and(btrim(name) ~ '^[0-9]+$')                            AS nazivi_su_brojevi,
           bool_or(ima_facility_unit)                                    AS ima_facility_unit
      FROM t_b3_jed
     GROUP BY su
    HAVING count(*) > 1)
SELECT verificiran,
       count(*)                                                                       AS objekata_s_vise_jedinica,
       count(*) FILTER (WHERE s_nazivom = jedinica AND razlicitih_naziva = jedinica)  AS fe_prikazuje_naziv,
       count(*) FILTER (WHERE NOT (s_nazivom = jedinica AND razlicitih_naziva = jedinica)) AS fe_prikazuje_redni_broj,
       count(*) FILTER (WHERE nazivi_su_brojevi)                                      AS nazivi_su_brojevi,
       count(*) FILTER (WHERE ima_facility_unit)                                      AS ima_facility_unit
  FROM po_obj
 GROUP BY verificiran
 ORDER BY verificiran DESC
) q;

SELECT 'H6' AS blok, q.* FROM (
SELECT j.verificiran,
       count(*)                                                                       AS jedinica,
       count(*) FILTER (WHERE ad.app_puna IS NULL OR btrim(ad.app_puna) = '')         AS puna_prazna,
       count(*) FILTER (WHERE ad.app_kbr_iz_id IS NOT NULL AND ad.app_kbr_tekst IS NOT NULL
                          AND lower(btrim(ad.app_kbr_iz_id)) <> lower(btrim(ad.app_kbr_tekst)))   AS kbr_id_ne_tekst,
       count(*) FILTER (WHERE ad.app_ulica_iz_id IS NOT NULL AND ad.app_ulica_tekst IS NOT NULL
                          AND lower(btrim(ad.app_ulica_iz_id)) <> lower(btrim(ad.app_ulica_tekst))) AS ulica_id_ne_tekst,
       count(*) FILTER (WHERE ad.app_kbr IS NOT NULL AND ad.app_puna IS NOT NULL
                          AND strpos(lower(ad.app_puna), lower(btrim(ad.app_kbr))) = 0)  AS kbr_nije_u_punoj,
       count(*) FILTER (WHERE ad.app_ulica IS NOT NULL AND ad.app_puna IS NOT NULL
                          AND strpos(lower(ad.app_puna), lower(btrim(ad.app_ulica))) = 0) AS ulica_nije_u_punoj,
       count(*) FILTER (WHERE ad.app_naselje IS NOT NULL AND ad.app_puna IS NOT NULL
                          AND strpos(lower(ad.app_puna), lower(btrim(ad.app_naselje))) = 0) AS naselje_nije_u_punoj,
       count(*) FILTER (WHERE ad.app_adresa_active = 'false')                         AS adresa_neaktivna,
       count(*) FILTER (WHERE ad.app_kbr IS NOT NULL)                                 AS ima_kbr
  FROM t_b3_jed j
  JOIN t_b3_adr ad ON ad.id = j.id
 GROUP BY j.verificiran
 ORDER BY j.verificiran DESC
) q;

CREATE TEMP TABLE t_b3_usp AS
WITH vk AS (
    SELECT f_id,
           sum(cap_kol) FILTER (WHERE cap_tip = 'CAT_BROJ_KREVETA')     AS k,
           sum(cap_kol) FILTER (WHERE cap_tip = 'CAT_BROJ_POM_KREVETA') AS p
      FROM (SELECT DISTINCT f_id, cap_id, cap_tip, cap_kol FROM t_b3_view WHERE cap_id IS NOT NULL) x
     GROUP BY f_id),
vu AS (
    SELECT f_id,
           sum(ucap_kol) FILTER (WHERE ucap_tip = 'CAT_BROJ_KREVETA')     AS k,
           sum(ucap_kol) FILTER (WHERE ucap_tip = 'CAT_BROJ_POM_KREVETA') AS p
      FROM (SELECT DISTINCT f_id, unit_id, ucap_tip, ucap_kol FROM t_b3_view
             WHERE unit_id IS NOT NULL AND ucap_tip IS NOT NULL) x
     GROUP BY f_id),
va AS (
    SELECT f_id,
           array_agg(DISTINCT adresa) FILTER (WHERE adresa IS NOT NULL) AS adrese,
           array_agg(DISTINCT kbr)    FILTER (WHERE kbr IS NOT NULL)    AS kbrs
      FROM t_b3_view
     GROUP BY f_id)
SELECT j.id, j.su, j.sas,
       row_number() OVER (ORDER BY j.id) <= 200             AS u_uzorku,
       ad.app_puna, ad.app_kbr, va.adrese, va.kbrs,
       coalesce(j.k_strogo, j.uk_strogo)                    AS popis_k,
       coalesce(j.p_strogo, j.up_strogo)                    AS popis_p,
       coalesce(j.k_coal, j.uk_coal)                        AS claim_k,
       coalesce(j.p_coal, j.up_coal)                        AS claim_p,
       coalesce(vk.k, vu.k)                                 AS view_k,
       coalesce(vk.p, vu.p)                                 AS view_p
  FROM t_b3_jed j
  JOIN t_b3_adr ad ON ad.id = j.id
  JOIN va          ON va.f_id = j.id
  LEFT JOIN vk     ON vk.f_id = j.id
  LEFT JOIN vu     ON vu.f_id = j.id
 WHERE j.verificiran;

SELECT 'S1S2' AS blok, q.* FROM (
SELECT CASE WHEN u_uzorku THEN 'S1 uzorak 200' ELSE 'S2 ostatak' END              AS skup,
       count(*)                                                                   AS jedinica,
       count(*) FILTER (WHERE adrese IS NULL)                                     AS view_bez_adrese,
       count(*) FILTER (WHERE cardinality(adrese) > 1)                            AS view_vise_adresa,
       count(*) FILTER (WHERE app_puna = ANY (adrese))                            AS adresa_u_viewu,
       count(*) FILTER (WHERE adrese IS NOT NULL AND NOT (coalesce(app_puna, '') = ANY (adrese))) AS adresa_nije_u_viewu,
       count(*) FILTER (WHERE kbrs IS NOT NULL AND NOT (coalesce(app_kbr, '') = ANY (kbrs)))      AS kbr_nije_u_viewu,
       count(*) FILTER (WHERE popis_k IS NOT DISTINCT FROM view_k)                AS kreveti_popis_jednako,
       count(*) FILTER (WHERE claim_k IS NOT DISTINCT FROM view_k)                AS kreveti_claim_jednako,
       count(*) FILTER (WHERE popis_p IS NOT DISTINCT FROM view_p)                AS pomocni_popis_jednako,
       count(*) FILTER (WHERE claim_p IS NOT DISTINCT FROM view_p)                AS pomocni_claim_jednako
  FROM t_b3_usp
 GROUP BY u_uzorku
 ORDER BY u_uzorku DESC
) q;

SELECT 'S3' AS blok, q.* FROM (
SELECT id, su, sas,
       adrese IS NOT NULL AND NOT (coalesce(app_puna, '') = ANY (adrese)) AS adresa_razlicita,
       cardinality(adrese)                                                AS view_adresa,
       app_kbr, kbrs AS view_kbr,
       popis_k, claim_k, view_k, popis_p, claim_p, view_p
  FROM t_b3_usp
 WHERE (adrese IS NOT NULL AND NOT (coalesce(app_puna, '') = ANY (adrese)))
    OR popis_k IS DISTINCT FROM view_k
    OR claim_k IS DISTINCT FROM view_k
    OR popis_p IS DISTINCT FROM view_p
    OR claim_p IS DISTINCT FROM view_p
 ORDER BY id
 LIMIT 20
) q;
