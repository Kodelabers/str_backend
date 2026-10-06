SELECT 'B0a' AS blok, q.* FROM (
SELECT current_database() AS baza, current_user AS korisnik, inet_server_addr() AS server, now() AS vrijeme,
       to_regclass('str.vw_src_facility_actual')     IS NOT NULL AS ima_view_actual,
       to_regclass('str.vw_src_facility_historical') IS NOT NULL AS ima_view_historical,
       md5(pg_get_viewdef(to_regclass('str.vw_src_facility_actual'), true)) AS actual_md5
) q;

SELECT 'B0c' AS blok, q.* FROM (
SELECT table_name, ordinal_position, column_name, data_type
  FROM information_schema.columns
 WHERE table_schema = 'str'
   AND table_name IN ('facility', 'facility_unit', 'address', 'subject_address', 'vw_src_facility_actual')
 ORDER BY table_name, ordinal_position
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
