DROP TABLE IF EXISTS pg_temp.t_b3_rang, pg_temp.t_b3_jed, pg_temp.t_b3_adr, pg_temp.t_b3_view, pg_temp.t_b3_usp, pg_temp.t_b3_cap;

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

CREATE TEMP TABLE t_b3_cap AS
SELECT j.id, j.su, r.n_predmet,
       count(*) OVER (PARTITION BY j.su)                                          AS n_popis,
       count(fc.id) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA')                   AS n_k,
       count(fc.id) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA')               AS n_p,
       coalesce(string_agg(fc.quantity::text, ',' ORDER BY fc.quantity)
                FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA'), '')                  AS k_skup,
       coalesce(string_agg(fc.quantity::text, ',' ORDER BY fc.quantity)
                FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA'), '')              AS p_skup,
       string_agg(fc.quantity::text, ',' ORDER BY fc.id)
                FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA')                       AS k_po_redu,
       string_agg(fc.quantity::text, ',' ORDER BY fc.id)
                FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA')                   AS p_po_redu,
       min(fc.id)                                                                 AS prvi_fc_id,
       max(fc.id)                                                                 AS zadnji_fc_id
  FROM t_b3_jed j
  JOIN (SELECT id, count(*) OVER (PARTITION BY su, bc_id) AS n_predmet
          FROM t_b3_rang
         WHERE predmet_rang = 1) r        ON r.id = j.id
  LEFT JOIN str.facility_capacity fc      ON fc.facility_id = j.id AND fc.active = true
  LEFT JOIN str.codebook_element ce       ON ce.id = fc.type_id
 WHERE NOT j.verificiran
 GROUP BY j.id, j.su, r.n_predmet;

ANALYZE t_b3_cap;

SELECT 'R1' AS blok, q.* FROM (
SELECT CASE WHEN n_popis = 1 THEN 'objekt s 1 jedinicom' ELSE 'objekt s vise jedinica' END AS vrsta_objekta,
       count(*)                                                        AS jedinica,
       count(*) FILTER (WHERE n_k = 0)                                 AS kreveti_0_redaka,
       count(*) FILTER (WHERE n_k = 1)                                 AS kreveti_1_redak,
       count(*) FILTER (WHERE n_k > 1)                                 AS kreveti_vise_redaka,
       count(*) FILTER (WHERE n_k > 1 AND n_k = n_predmet)             AS redaka_jednako_zapisa_predmeta,
       count(*) FILTER (WHERE n_k > 1 AND n_k = n_popis)               AS redaka_jednako_jedinica_popisa,
       count(*) FILTER (WHERE n_p > 1 AND n_p = n_predmet)             AS pom_redaka_jednako_zapisa_predmeta,
       count(*) FILTER (WHERE n_predmet <> n_popis)                    AS predmet_ima_jos_zapisa
  FROM t_b3_cap
 GROUP BY 1
 ORDER BY 1
) q;

SELECT 'R1b' AS blok, q.* FROM (
SELECT count(*)                                                   AS objekata_s_vise_jedinica,
       count(*) FILTER (WHERE skupova_k = 1)                      AS svi_isti_kreveti,
       count(*) FILTER (WHERE skupova_k = jedinica)               AS svaki_svoj_kreveti,
       count(*) FILTER (WHERE skupova_p = 1)                      AS svi_isti_pomocni
  FROM (SELECT su, count(*) AS jedinica,
               count(DISTINCT k_skup) AS skupova_k,
               count(DISTINCT p_skup) AS skupova_p
          FROM t_b3_cap
         WHERE n_popis > 1
         GROUP BY su) o
) q;

SELECT 'R1c' AS blok, q.* FROM (
SELECT su, id, n_popis, n_predmet, n_k, k_po_redu, p_po_redu, prvi_fc_id, zadnji_fc_id
  FROM t_b3_cap
 WHERE su IN (SELECT su FROM t_b3_cap
               WHERE n_popis BETWEEN 2 AND 6
               GROUP BY su
               ORDER BY su
               LIMIT 5)
 ORDER BY su, id
) q;

SELECT 'R1d' AS blok, q.* FROM (
SELECT c.su, c.id, c.n_predmet, c.n_k, c.k_po_redu, c.p_po_redu,
       (SELECT count(*) FROM str.facility f2 WHERE f2.system_uuid = c.su::uuid) AS svih_zapisa_uuid
  FROM (SELECT * FROM t_b3_cap
         WHERE n_popis = 1 AND n_k > 1
         ORDER BY id
         LIMIT 5) c
 ORDER BY c.id
) q;

SELECT 'R2' AS blok, q.* FROM (
SELECT CASE WHEN coalesce(j.k_coal, j.uk_coal) IS NULL THEN 'bez kreveta' ELSE 'ima krevete' END AS skupina,
       'facility_content' AS izvor, ce.code AS tip, count(DISTINCT j.id) AS jedinica, count(*) AS redaka,
       sum(c.quantity) AS ukupno
  FROM t_b3_jed j
  JOIN str.facility_content c       ON c.facility_id = j.id
  LEFT JOIN str.codebook_element ce ON ce.id = c.type_id
 WHERE j.verificiran
 GROUP BY 1, 2, 3
 ORDER BY 1, 2, 4 DESC
) q;

SELECT 'R2b' AS blok, q.* FROM (
SELECT extract(year FROM f.created_date)::int AS godina, c_sub.code AS podvrsta,
       count(*) AS jedinica,
       count(*) FILTER (WHERE coalesce(j.k_coal, j.uk_coal) > 0) AS ima_krevete,
       count(*) FILTER (WHERE coalesce(j.p_coal, j.up_coal) > 0) AS ima_pomocne
  FROM t_b3_jed j
  JOIN str.facility f ON f.id = j.id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 WHERE j.verificiran
 GROUP BY 1, 2
 ORDER BY 1, 2
) q;

SELECT 'R3' AS blok, q.* FROM (
SELECT j.verificiran,
       count(*)                                                                AS jedinica,
       count(*) FILTER (WHERE f.parent_facility_id IS NOT NULL)                AS ima_roditelja,
       count(*) FILTER (WHERE j.id IN (SELECT c.parent_facility_id FROM str.facility c
                                        WHERE c.parent_facility_id IS NOT NULL)) AS je_roditelj,
       count(*) FILTER (WHERE p.id IS NOT NULL AND p.system_uuid = j.su::uuid) AS roditelj_isti_uuid,
       count(*) FILTER (WHERE p.id IS NOT NULL AND p.active)                   AS roditelj_aktivan
  FROM t_b3_jed j
  JOIN str.facility f      ON f.id = j.id
  LEFT JOIN str.facility p ON p.id = f.parent_facility_id
 GROUP BY j.verificiran
 ORDER BY j.verificiran DESC
) q;

SELECT 'R3b' AS blok, q.* FROM (
SELECT count(*)                                                     AS zapisa,
       count(*) FILTER (WHERE parent_facility_id IS NOT NULL)       AS ima_roditelja,
       count(*) FILTER (WHERE parent_facility_id IS NOT NULL
                          AND created_by = 'optimit')               AS ima_roditelja_migrirani,
       count(DISTINCT parent_facility_id)                           AS razlicitih_roditelja
  FROM str.facility
 WHERE active = true
) q;

SELECT 'R4' AS blok, q.* FROM (
SELECT CASE WHEN j.app_addr IS NULL THEN 'nema address_id'
            ELSE 'prazan redak adrese' END                              AS slucaj,
       coalesce(j.sas::text, 'NULL')                                    AS same_address_subject,
       count(*)                                                         AS jedinica,
       count(*) FILTER (WHERE j.subj_addr_app IS NOT NULL)              AS subjekt_ima_adresu,
       count(*) FILTER (WHERE sa.settlement_id IS NOT NULL)             AS adresa_subjekta_ima_naselje,
       count(*) FILTER (WHERE sa.house_number_id IS NOT NULL
                           OR nullif(btrim(sa.full_address), '') IS NOT NULL) AS adresa_subjekta_ima_kbr_ili_punu
  FROM t_b3_jed j
  LEFT JOIN str.address a  ON a.id  = j.app_addr
  LEFT JOIN str.address sa ON sa.id = j.subj_addr_app
 WHERE j.verificiran
   AND nullif(btrim(a.full_address), '') IS NULL
   AND a.settlement_id IS NULL AND a.municipality_id IS NULL
   AND nullif(btrim(a.settlement), '') IS NULL AND nullif(btrim(a.municipality), '') IS NULL
 GROUP BY 1, 2
 ORDER BY 1, 2
) q;

SELECT 'R2c' AS blok, q.* FROM (
SELECT CASE WHEN coalesce(j.k_coal, j.uk_coal) IS NULL THEN 'bez kreveta' ELSE 'ima krevete' END AS skupina,
       ce.code AS tip, count(DISTINCT j.id) AS jedinica, count(*) AS redaka, sum(cc.quantity) AS ukupno
  FROM t_b3_jed j
  JOIN str.facility_content c            ON c.facility_id = j.id
  JOIN str.facility_content_capacity cc  ON cc.facility_content_id = c.id
  LEFT JOIN str.codebook_element ce      ON ce.id = cc.type_id
 WHERE j.verificiran
 GROUP BY 1, 2
 ORDER BY 1, 3 DESC
) q;
