DROP TABLE IF EXISTS pg_temp.t_b3_ver;

CREATE TEMP TABLE t_b3_ver AS
SELECT f.id, c_sub.code AS podvrsta
  FROM (SELECT DISTINCT f_id FROM str.vw_src_facility_actual) v
  JOIN str.facility f ON f.id = v.f_id
  LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
 WHERE c_st.code = 'FBS_ACTIVE'
   AND c_sub.code IN ('FS_SOBA', 'FS_APARTMAN', 'FS_STUDIO_APARTMAN', 'FS_KUCA_ZA_ODMOR');

ANALYZE t_b3_ver;

SELECT 'T0' AS blok, q.* FROM (
SELECT table_name, ordinal_position, column_name, data_type
  FROM information_schema.columns
 WHERE table_schema = 'str'
   AND table_name IN ('facility_content', 'facility_content_capacity')
 ORDER BY table_name, ordinal_position
) q;

SELECT 'T1' AS blok, q.* FROM (
SELECT 'facility_content' AS izvor, ce.code AS tip, to_jsonb(c) ->> 'active' AS active,
       count(*) AS redaka, count(DISTINCT v.id) AS jedinica, sum(c.quantity) AS ukupno
  FROM t_b3_ver v
  JOIN str.facility_content c       ON c.facility_id = v.id
  LEFT JOIN str.codebook_element ce ON ce.id = c.type_id
 GROUP BY 1, 2, 3
UNION ALL
SELECT 'facility_content_capacity', ce.code, to_jsonb(cc) ->> 'active',
       count(*), count(DISTINCT v.id), sum(cc.quantity)
  FROM t_b3_ver v
  JOIN str.facility_content c            ON c.facility_id = v.id
  JOIN str.facility_content_capacity cc  ON cc.facility_content_id = c.id
  LEFT JOIN str.codebook_element ce      ON ce.id = cc.type_id
 GROUP BY 1, 2, 3
 ORDER BY 1, 2, 3
) q;

SELECT 'T2' AS blok, q.* FROM (
WITH k AS (
    SELECT v.id, v.podvrsta,
           (SELECT sum(fc.quantity) FROM str.facility_capacity fc
              JOIN str.codebook_element ce ON ce.id = fc.type_id
             WHERE fc.facility_id = v.id AND fc.active = true
               AND ce.code = 'CAT_BROJ_KREVETA')                         AS k_objekt,
           (SELECT sum(cc.quantity) FROM str.facility_content c
              JOIN str.facility_content_capacity cc ON cc.facility_content_id = c.id
              JOIN str.codebook_element ce ON ce.id = cc.type_id
             WHERE c.facility_id = v.id
               AND ce.code = 'CAT_BROJ_KREVETA')                         AS k_sadrzaj_sve,
           (SELECT sum(cc.quantity) FROM str.facility_content c
              JOIN str.facility_content_capacity cc ON cc.facility_content_id = c.id
              JOIN str.codebook_element ce ON ce.id = cc.type_id
             WHERE c.facility_id = v.id
               AND coalesce((to_jsonb(c) ->> 'active')::boolean, true)
               AND coalesce((to_jsonb(cc) ->> 'active')::boolean, true)
               AND ce.code = 'CAT_BROJ_KREVETA')                         AS k_sadrzaj_aktivni,
           (SELECT sum(fc.quantity) FROM str.facility_capacity fc
              JOIN str.codebook_element ce ON ce.id = fc.type_id
             WHERE fc.facility_id = v.id AND fc.active = true
               AND ce.code = 'CAT_BROJ_POM_KREVETA')                     AS p_objekt
      FROM t_b3_ver v)
SELECT podvrsta,
       count(*)                                                                    AS jedinica,
       count(*) FILTER (WHERE k_objekt > 0 AND coalesce(k_sadrzaj_aktivni, 0) = 0)   AS samo_objekt,
       count(*) FILTER (WHERE coalesce(k_objekt, 0) = 0 AND k_sadrzaj_aktivni > 0)   AS samo_sadrzaj,
       count(*) FILTER (WHERE k_objekt > 0 AND k_sadrzaj_aktivni > 0)                AS oba,
       count(*) FILTER (WHERE coalesce(k_objekt, 0) = 0
                          AND coalesce(k_sadrzaj_aktivni, 0) = 0)                    AS nijedno,
       count(*) FILTER (WHERE k_sadrzaj_sve IS DISTINCT FROM k_sadrzaj_aktivni)      AS sadrzaj_neaktivni_mijenjaju_zbroj,
       count(*) FILTER (WHERE p_objekt > 0)                                          AS ima_pomocne
  FROM k
 GROUP BY podvrsta
 ORDER BY podvrsta
) q;

SELECT 'T3' AS blok, q.* FROM (
SELECT v.id, v.podvrsta, c.id AS sadrzaj_id, ce.code AS sadrzaj_tip, c.quantity AS sadrzaj_kolicina,
       to_jsonb(c) ->> 'active' AS sadrzaj_active,
       cce.code AS kapacitet_tip, cc.quantity AS kapacitet_kolicina,
       to_jsonb(cc) ->> 'active' AS kapacitet_active,
       (SELECT string_agg(ce2.code || '=' || fc.quantity, ', ' ORDER BY fc.id)
          FROM str.facility_capacity fc
          JOIN str.codebook_element ce2 ON ce2.id = fc.type_id
         WHERE fc.facility_id = v.id AND fc.active = true)  AS facility_capacity_aktivni
  FROM t_b3_ver v
  JOIN str.facility_content c                 ON c.facility_id = v.id
  LEFT JOIN str.codebook_element ce           ON ce.id = c.type_id
  LEFT JOIN str.facility_content_capacity cc  ON cc.facility_content_id = c.id
  LEFT JOIN str.codebook_element cce          ON cce.id = cc.type_id
 WHERE v.id IN (SELECT x.id FROM t_b3_ver x
                 WHERE x.podvrsta IN ('FS_APARTMAN', 'FS_KUCA_ZA_ODMOR')
                   AND EXISTS (SELECT 1 FROM str.facility_content c2
                                JOIN str.facility_content_capacity cc2 ON cc2.facility_content_id = c2.id
                               WHERE c2.facility_id = x.id)
                 ORDER BY x.id
                 LIMIT 12)
 ORDER BY v.id, c.id, cce.code
) q;

SELECT 'T4' AS blok, q.* FROM (
SELECT f.created_by = 'optimit' AS migriran,
       count(DISTINCT f.id)  AS zapisa_sa_sadrzajem,
       count(c.id)           AS sadrzaja,
       count(cc.facility_content_id) AS kapaciteta_sadrzaja
  FROM str.facility f
  JOIN str.facility_content c                ON c.facility_id = f.id
  LEFT JOIN str.facility_content_capacity cc ON cc.facility_content_id = c.id
 WHERE f.active = true
 GROUP BY 1
 ORDER BY 1
) q;

SELECT 'T5' AS blok, q.* FROM (
WITH izbor AS (
    (SELECT x.id, 1 AS r, 'verificirani apartman, kreveti u sadrzaju' AS razlog
       FROM t_b3_ver x
      WHERE x.podvrsta = 'FS_APARTMAN'
        AND EXISTS (SELECT 1 FROM str.facility_content c2
                      JOIN str.facility_content_capacity cc2 ON cc2.facility_content_id = c2.id
                     WHERE c2.facility_id = x.id)
      ORDER BY x.id LIMIT 2)
    UNION ALL
    (SELECT x.id, 2, 'verificirana kuca za odmor, kreveti u sadrzaju'
       FROM t_b3_ver x
      WHERE x.podvrsta = 'FS_KUCA_ZA_ODMOR'
        AND EXISTS (SELECT 1 FROM str.facility_content c2
                      JOIN str.facility_content_capacity cc2 ON cc2.facility_content_id = c2.id
                     WHERE c2.facility_id = x.id)
      ORDER BY x.id LIMIT 1)
    UNION ALL
    (SELECT x.id, 3, 'verificirana soba, kreveti u facility_capacity'
       FROM t_b3_ver x
      WHERE x.podvrsta = 'FS_SOBA'
        AND EXISTS (SELECT 1 FROM str.facility_capacity fc2
                      JOIN str.codebook_element ce2 ON ce2.id = fc2.type_id
                     WHERE fc2.facility_id = x.id AND fc2.active = true AND ce2.code = 'CAT_BROJ_KREVETA')
      ORDER BY x.id LIMIT 1)
    UNION ALL
    (SELECT x.id, 4, 'verificirani, prazna puna adresa, ima ulicu i kbr'
       FROM t_b3_ver x
       JOIN str.facility f2 ON f2.id = x.id
       JOIN str.address a2  ON a2.id = f2.address_id
      WHERE nullif(btrim(a2.full_address), '') IS NULL
        AND a2.street_id IS NOT NULL AND a2.house_number_id IS NOT NULL
      ORDER BY x.id LIMIT 1)
    UNION ALL
    SELECT f3.id, 5, 'migrirani objekt s 3 jedinice (svaka nosi retke 2, 3, 4)'
      FROM str.facility f3
     WHERE f3.id IN (85064, 85065, 85066))
SELECT i.razlog, f.id AS facility_id, f.system_uuid, f.name AS naziv, c_sub.code AS podvrsta,
       bc.id AS predmet_id, to_jsonb(bc) ->> 'classification' AS klasa_predmeta,
       f.document_id, to_jsonb(d) ->> 'registration_number' AS urudzbeni_broj,
       a.full_address,
       coalesce(stt.name, a.street)            AS ulica,
       coalesce(hn.name, a.house_number)       AS kbr,
       coalesce(a.postal_code, se.postal_code) AS postanski,
       coalesce(se.name, a.settlement)         AS naselje,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND fc.active = true
           AND ce.code = 'CAT_BROJ_KREVETA')                AS kreveti_facility_capacity,
       (SELECT string_agg(fc.quantity::text, ',' ORDER BY fc.id) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND fc.active = true
           AND ce.code = 'CAT_BROJ_KREVETA')                AS kreveti_po_redu,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND fc.active = true
           AND ce.code = 'CAT_BROJ_POM_KREVETA')            AS pomocni_facility_capacity,
       (SELECT sum(cc.quantity) FROM str.facility_content c
          JOIN str.facility_content_capacity cc ON cc.facility_content_id = c.id
          JOIN str.codebook_element ce ON ce.id = cc.type_id
         WHERE c.facility_id = f.id
           AND ce.code = 'CAT_BROJ_KREVETA')                AS kreveti_sadrzaj
  FROM izbor i
  JOIN str.facility f            ON f.id   = i.id
  LEFT JOIN str.document d       ON d.id   = f.document_id
  LEFT JOIN str.business_case bc ON bc.id  = d.business_case_id
  LEFT JOIN str.facility_type ft
         ON ft.id = (SELECT max(x.id) FROM str.facility_type x
                      WHERE x.facility_id = f.id AND coalesce(x.active, true) = true)
  LEFT JOIN str.codebook_element c_sub ON c_sub.id = ft.sub_type_id
  LEFT JOIN str.address a        ON a.id   = f.address_id
  LEFT JOIN str.street stt       ON stt.id = a.street_id
  LEFT JOIN str.house_number hn  ON hn.id  = a.house_number_id
  LEFT JOIN str.settlement se    ON se.id  = a.settlement_id
 ORDER BY i.r, f.id
) q;
