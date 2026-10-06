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

SELECT 'C0a' AS blok, q.* FROM (
SELECT n AS redak, l AS tekst
  FROM regexp_split_to_table(pg_get_viewdef(to_regclass('str.vw_src_facility_actual'), true), E'\n')
       WITH ORDINALITY AS t(l, n)
 ORDER BY n
) q;

SELECT 'C0b' AS blok, q.* FROM (
SELECT table_name, ordinal_position, column_name, data_type
  FROM information_schema.columns
 WHERE table_schema = 'str'
   AND table_name IN ('facility', 'facility_unit', 'address')
 ORDER BY table_name, ordinal_position
) q;

SELECT 'K1' AS blok, q.* FROM (
SELECT j.verificiran, 'facility_capacity' AS izvor, ce.code AS tip, ce.name AS naziv, fc.active,
       count(*) AS redaka, count(DISTINCT j.id) AS jedinica, sum(fc.quantity) AS ukupno
  FROM t_b3_jed j
  JOIN str.facility_capacity fc      ON fc.facility_id = j.id
  LEFT JOIN str.codebook_element ce ON ce.id = fc.type_id
 WHERE coalesce(j.k_coal, j.uk_coal) IS NULL
 GROUP BY 1, 2, 3, 4, 5
UNION ALL
SELECT j.verificiran, 'facility_unit_capacity', ce.code, ce.name, fuc.active,
       count(*), count(DISTINCT j.id), sum(fuc.quantity)
  FROM t_b3_jed j
  JOIN str.facility_unit fu            ON fu.facility_id = j.id
  JOIN str.facility_unit_capacity fuc  ON fuc.facility_unit_id = fu.id
  LEFT JOIN str.codebook_element ce    ON ce.id = fuc.type_id
 WHERE coalesce(j.k_coal, j.uk_coal) IS NULL
 GROUP BY 1, 2, 3, 4, 5
 ORDER BY 1 DESC, 2, 7 DESC
) q;

SELECT 'K1b' AS blok, q.* FROM (
SELECT verificiran,
       count(*)                                            AS bez_kreveta,
       count(*) FILTER (WHERE NOT coalesce(ima_fc, false)
                          AND NOT coalesce(ima_fuc, false)) AS bez_ijednog_retka,
       count(*) FILTER (WHERE coalesce(p_coal, up_coal) > 0) AS ima_samo_pomocne
  FROM t_b3_jed
 WHERE coalesce(k_coal, uk_coal) IS NULL
 GROUP BY verificiran
 ORDER BY verificiran DESC
) q;

SELECT 'K1c' AS blok, q.* FROM (
SELECT j.verificiran, ce.code AS tip, ce.name AS naziv,
       count(DISTINCT j.id) AS jedinica, count(*) AS redaka
  FROM t_b3_jed j
  JOIN str.facility_capacity fc     ON fc.facility_id = j.id AND fc.active = true
  LEFT JOIN str.codebook_element ce ON ce.id = fc.type_id
 GROUP BY 1, 2, 3
 ORDER BY 1 DESC, 4 DESC
) q;

SELECT 'K2' AS blok, q.* FROM (
WITH r AS (
    SELECT j.id, ce.code AS tip,
           count(*)                              AS redaka,
           count(DISTINCT fc.quantity)           AS razlicitih_iznosa,
           count(DISTINCT fc.created_date::date) AS razlicitih_dana,
           sum(fc.quantity)                      AS zbroj,
           max(fc.quantity)                      AS najveci
      FROM t_b3_jed j
      JOIN str.facility_capacity fc ON fc.facility_id = j.id AND fc.active = true
      JOIN str.codebook_element ce  ON ce.id = fc.type_id
                                   AND ce.code IN ('CAT_BROJ_KREVETA', 'CAT_BROJ_POM_KREVETA')
     WHERE NOT j.verificiran
     GROUP BY j.id, ce.code
    HAVING count(*) > 1)
SELECT tip, redaka,
       count(*)                                         AS jedinica,
       count(*) FILTER (WHERE razlicitih_iznosa = 1)    AS isti_iznos,
       count(*) FILTER (WHERE razlicitih_dana = 1)      AS isti_dan,
       min(zbroj) AS min_zbroj, max(zbroj) AS max_zbroj
  FROM r
 GROUP BY tip, redaka
 ORDER BY tip, redaka
) q;

SELECT 'K2b' AS blok, q.* FROM (
SELECT fc.facility_id, fc.id, ce.code AS tip, fc.quantity, fc.active, fc.created_date, fc.created_by
  FROM str.facility_capacity fc
  JOIN str.codebook_element ce ON ce.id = fc.type_id
 WHERE fc.facility_id IN (SELECT j.id
                            FROM t_b3_jed j
                           WHERE NOT j.verificiran AND j.n_k_aktivnih > 1
                           ORDER BY j.id
                           LIMIT 10)
 ORDER BY fc.facility_id, ce.code, fc.id
) q;

SELECT 'K3' AS blok, q.* FROM (
SELECT fc.facility_id, fc.id, ce.code AS tip, fc.quantity, fc.active, fc.created_date, fc.last_modified_date
  FROM str.facility_capacity fc
  JOIN str.codebook_element ce ON ce.id = fc.type_id
 WHERE fc.facility_id IN (241681, 243054, 243335, 243354, 243594)
 ORDER BY fc.facility_id, ce.code, fc.id
) q;

SELECT 'A1' AS blok, q.* FROM (
SELECT count(*)                                                      AS jedinica,
       count(*) FILTER (WHERE a.street_id IS NOT NULL)               AS ima_street_id,
       count(*) FILTER (WHERE nullif(btrim(a.street), '') IS NOT NULL) AS ima_ulicu_tekst,
       count(*) FILTER (WHERE a.house_number_id IS NOT NULL)         AS ima_house_number_id,
       count(*) FILTER (WHERE nullif(btrim(a.house_number), '') IS NOT NULL) AS ima_kbr_tekst,
       count(*) FILTER (WHERE a.settlement_id IS NOT NULL)           AS ima_settlement_id,
       count(*) FILTER (WHERE a.municipality_id IS NOT NULL)         AS ima_municipality_id,
       count(*) FILTER (WHERE a.postal_code IS NOT NULL OR se.postal_code IS NOT NULL) AS ima_postanski
  FROM t_b3_jed j
  JOIN t_b3_adr ad ON ad.id = j.id
  JOIN str.address a ON a.id = j.app_addr
  LEFT JOIN str.settlement se ON se.id = a.settlement_id
 WHERE j.verificiran
   AND (ad.app_puna IS NULL OR btrim(ad.app_puna) = '')
) q;

SELECT 'A1a' AS blok, q.* FROM (
SELECT j.verificiran,
       CASE WHEN nullif(btrim(a.full_address), '') IS NOT NULL THEN '1 puna adresa'
            WHEN coalesce(se.name, a.settlement) IS NOT NULL
              OR coalesce(mu.name, a.municipality) IS NOT NULL  THEN '2 samo naselje, opcina'
            WHEN j.app_addr IS NULL                             THEN '4 crtica (jedinica nema adresu)'
            ELSE                                                     '3 crtica (adresa bez naselja i opcine)' END AS prikaz_danas,
       count(*)                                                                  AS jedinica,
       count(*) FILTER (WHERE coalesce(stt.name, a.street) IS NOT NULL)          AS ima_ulicu,
       count(*) FILTER (WHERE coalesce(hn.name, a.house_number) IS NOT NULL)     AS ima_kbr,
       count(*) FILTER (WHERE coalesce(a.postal_code, se.postal_code) IS NOT NULL) AS ima_postanski,
       count(*) FILTER (WHERE coalesce(co.name, a.county) IS NOT NULL)           AS ima_zupaniju,
       count(*) FILTER (WHERE lower(se.name) = lower(mu.name))                   AS naselje_jednako_opcini
  FROM t_b3_jed j
  LEFT JOIN str.address a       ON a.id   = j.app_addr
  LEFT JOIN str.street stt      ON stt.id = a.street_id
  LEFT JOIN str.house_number hn ON hn.id  = a.house_number_id
  LEFT JOIN str.settlement se   ON se.id  = a.settlement_id
  LEFT JOIN str.municipality mu ON mu.id  = a.municipality_id
  LEFT JOIN str.county co       ON co.id  = a.county_id
 GROUP BY 1, 2
 ORDER BY 1 DESC, 2
) q;

SELECT 'A1c' AS blok, q.* FROM (
SELECT j.id, j.su, j.sas, j.own_addr, j.app_addr, j.subj_addr_app,
       (SELECT count(*) FROM t_b3_jed j2
         WHERE j2.su = j.su AND j2.id <> j.id AND j2.app_addr IS NOT NULL) AS druge_jedinice_s_adresom,
       to_jsonb(a) - ARRAY['created_by', 'last_modified_by']             AS adresa_redak
  FROM t_b3_jed j
  LEFT JOIN str.address a ON a.id = j.app_addr
  LEFT JOIN str.settlement se   ON se.id  = a.settlement_id
  LEFT JOIN str.municipality mu ON mu.id  = a.municipality_id
 WHERE j.verificiran
   AND nullif(btrim(a.full_address), '') IS NULL
   AND coalesce(se.name, a.settlement) IS NULL
   AND coalesce(mu.name, a.municipality) IS NULL
 ORDER BY j.id
 LIMIT 20
) q;

SELECT 'A1b' AS blok, q.* FROM (
SELECT j.id, j.su, j.name,
       a.full_address,
       coalesce(stt.name, a.street)        AS ulica,
       coalesce(hn.name, a.house_number)   AS kbr,
       coalesce(se.name, a.settlement)     AS naselje,
       coalesce(mu.name, a.municipality)   AS opcina,
       coalesce(a.postal_code, se.postal_code) AS postanski
  FROM t_b3_jed j
  JOIN str.address a            ON a.id   = j.app_addr
  LEFT JOIN str.street stt      ON stt.id = a.street_id
  LEFT JOIN str.house_number hn ON hn.id  = a.house_number_id
  LEFT JOIN str.settlement se   ON se.id  = a.settlement_id
  LEFT JOIN str.municipality mu ON mu.id  = a.municipality_id
 WHERE j.verificiran
 ORDER BY (a.full_address IS NULL OR btrim(a.full_address) = '') DESC, j.id
 LIMIT 15
) q;

SELECT 'A2' AS blok, q.* FROM (
SELECT j.id, a.full_address, a.street, a.house_number,
       coalesce(se.name, a.settlement)   AS naselje,
       coalesce(mu.name, a.municipality) AS opcina,
       coalesce(a.postal_code, se.postal_code) AS postanski
  FROM t_b3_jed j
  JOIN t_b3_adr ad ON ad.id = j.id
  JOIN str.address a            ON a.id   = j.app_addr
  LEFT JOIN str.settlement se   ON se.id  = a.settlement_id
  LEFT JOIN str.municipality mu ON mu.id  = a.municipality_id
 WHERE NOT j.verificiran
   AND ad.app_naselje IS NOT NULL AND ad.app_puna IS NOT NULL
   AND strpos(lower(ad.app_puna), lower(btrim(ad.app_naselje))) = 0
 ORDER BY j.id
 LIMIT 20
) q;

SELECT 'A2b' AS blok, q.* FROM (
SELECT count(*)                                                                         AS jedinica,
       count(*) FILTER (WHERE strpos(lower(a.full_address), lower(btrim(coalesce(mu.name, a.municipality)))) > 0) AS sadrzi_opcinu,
       count(*) FILTER (WHERE a.full_address ~ '[0-9]{5}')                              AS sadrzi_postanski,
       count(*) FILTER (WHERE a.full_address ~ '[0-9]')                                 AS sadrzi_znamenku
  FROM t_b3_jed j
  JOIN t_b3_adr ad ON ad.id = j.id
  JOIN str.address a            ON a.id   = j.app_addr
  LEFT JOIN str.municipality mu ON mu.id  = a.municipality_id
 WHERE NOT j.verificiran
   AND ad.app_naselje IS NOT NULL AND ad.app_puna IS NOT NULL
   AND strpos(lower(ad.app_puna), lower(btrim(ad.app_naselje))) = 0
) q;
