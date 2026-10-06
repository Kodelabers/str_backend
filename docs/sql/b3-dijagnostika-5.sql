DROP TABLE IF EXISTS pg_temp.t_b3_mig;

CREATE TEMP TABLE t_b3_mig AS
WITH jed AS (
    SELECT f.id, f.system_uuid AS su, f.document_id AS doc,
           count(*) OVER (PARTITION BY f.system_uuid, f.document_id) AS n
      FROM str.facility f
     WHERE f.active = true
       AND f.created_by = 'optimit'
       AND coalesce(f.historical, false) = false
       AND f.system_uuid IS NOT NULL),
sad AS (
    SELECT c.facility_id,
           count(DISTINCT c.id)                                                         AS n_sadrzaja,
           count(cc.id)                                                                 AS n_kap_sadrzaja,
           string_agg(DISTINCT ce.code, ',')                                            AS tipovi_sadrzaja,
           string_agg(DISTINCT cce.code, ',')                                           AS tipovi_kap_sadrzaja,
           max(c.quantity)                                                              AS max_kolicina_sadrzaja,
           sum(cc.quantity) FILTER (WHERE cce.code = 'CAT_BROJ_KREVETA')                AS k_sadrzaj_zbroj,
           sum(cc.quantity * coalesce(c.quantity, 1)) FILTER (WHERE cce.code = 'CAT_BROJ_KREVETA') AS k_sadrzaj_umnozak,
           sum(cc.quantity) FILTER (WHERE cce.code = 'CAT_BROJ_POM_KREVETA')            AS p_sadrzaj_zbroj
      FROM str.facility_content c
      JOIN str.facility f2 ON f2.id = c.facility_id AND f2.created_by = 'optimit' AND f2.active = true
      LEFT JOIN str.codebook_element ce  ON ce.id = c.type_id
      LEFT JOIN str.facility_content_capacity cc ON cc.facility_content_id = c.id AND cc.active = true
      LEFT JOIN str.codebook_element cce ON cce.id = cc.type_id
     WHERE c.active = true
     GROUP BY c.facility_id),
kap AS (
    SELECT fc.facility_id,
           count(*) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA')                                   AS n_k,
           sum(fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA')                           AS k_zbroj,
           string_agg(fc.quantity::text, ',' ORDER BY fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_KREVETA') AS k_redovi,
           string_agg(fc.quantity::text, ',' ORDER BY fc.quantity) FILTER (WHERE ce.code = 'CAT_BROJ_POM_KREVETA') AS p_redovi
      FROM str.facility_capacity fc
      JOIN str.facility f3 ON f3.id = fc.facility_id AND f3.created_by = 'optimit' AND f3.active = true
      JOIN str.codebook_element ce ON ce.id = fc.type_id
     WHERE fc.active = true
     GROUP BY fc.facility_id)
SELECT j.id, j.su, j.doc, j.n,
       s.n_sadrzaja, s.n_kap_sadrzaja, s.tipovi_sadrzaja, s.tipovi_kap_sadrzaja, s.max_kolicina_sadrzaja,
       s.k_sadrzaj_zbroj, s.k_sadrzaj_umnozak, s.p_sadrzaj_zbroj,
       k.n_k, k.k_zbroj, k.k_redovi, k.p_redovi
  FROM jed j
  LEFT JOIN sad s ON s.facility_id = j.id
  LEFT JOIN kap k ON k.facility_id = j.id;

ANALYZE t_b3_mig;

SELECT 'U1' AS blok, q.* FROM (
SELECT tipovi_sadrzaja, tipovi_kap_sadrzaja, max_kolicina_sadrzaja,
       count(*) AS jedinica
  FROM t_b3_mig
 GROUP BY 1, 2, 3
 ORDER BY 4 DESC
 LIMIT 30
) q;

SELECT 'U2' AS blok, q.* FROM (
SELECT count(*)                                                                    AS jedinica,
       count(*) FILTER (WHERE k_sadrzaj_umnozak IS NOT NULL)                       AS ima_krevete_u_sadrzaju,
       count(*) FILTER (WHERE k_zbroj IS NOT NULL)                                 AS ima_krevete_u_facility_capacity,
       count(*) FILTER (WHERE k_sadrzaj_umnozak = k_zbroj)                         AS umnozak_jednak,
       count(*) FILTER (WHERE k_sadrzaj_zbroj = k_zbroj)                           AS zbroj_jednak,
       count(*) FILTER (WHERE k_sadrzaj_umnozak IS NOT NULL AND k_zbroj IS NOT NULL
                          AND k_sadrzaj_umnozak <> k_zbroj)                        AS razlicito
  FROM t_b3_mig
 WHERE n = 1
) q;

SELECT 'U3' AS blok, q.* FROM (
SELECT count(*)                                                               AS objekata,
       sum(jedinica)                                                          AS jedinica,
       count(*) FILTER (WHERE jedinica_sa_sadrzajem = jedinica)               AS sve_jedinice_imaju_sadrzaj,
       count(*) FILTER (WHERE razlicitih_k_sadrzaj > 1)                       AS jedinice_razlicite_u_sadrzaju,
       count(*) FILTER (WHERE popis_iz_sadrzaja = k_redovi_prve)              AS sadrzaj_jedinica_jednak_redcima,
       count(*) FILTER (WHERE popis_iz_sadrzaja_zbroj = k_redovi_prve)        AS sadrzaj_zbroj_jednak_redcima,
       count(*) FILTER (WHERE k_redovi_prve IS NULL)                          AS bez_redaka_kreveta
  FROM (SELECT su, doc,
               count(*)                                                       AS jedinica,
               count(*) FILTER (WHERE k_sadrzaj_umnozak IS NOT NULL)          AS jedinica_sa_sadrzajem,
               count(DISTINCT k_sadrzaj_umnozak)                              AS razlicitih_k_sadrzaj,
               string_agg(k_sadrzaj_umnozak::text, ',' ORDER BY k_sadrzaj_umnozak) AS popis_iz_sadrzaja,
               string_agg(k_sadrzaj_zbroj::text, ',' ORDER BY k_sadrzaj_zbroj)     AS popis_iz_sadrzaja_zbroj,
               min(k_redovi)                                                  AS k_redovi_prve
          FROM t_b3_mig
         WHERE n > 1
         GROUP BY su, doc) o
) q;

SELECT 'U4' AS blok, q.* FROM (
SELECT su, id, n, tipovi_sadrzaja, tipovi_kap_sadrzaja, max_kolicina_sadrzaja,
       k_sadrzaj_zbroj, k_sadrzaj_umnozak, p_sadrzaj_zbroj, n_k, k_zbroj, k_redovi, p_redovi
  FROM t_b3_mig
 WHERE id IN (85064, 85065, 85066, 61889, 61890, 61891, 61892, 81942, 81943, 54734, 54735)
 ORDER BY su, id
) q;

SELECT 'U5' AS blok, q.* FROM (
SELECT su, id, n, tipovi_sadrzaja, max_kolicina_sadrzaja, k_sadrzaj_zbroj, k_sadrzaj_umnozak, k_redovi, p_redovi
  FROM t_b3_mig
 WHERE (su, doc) IN (SELECT su, doc FROM t_b3_mig
                      WHERE n BETWEEN 2 AND 5
                      GROUP BY su, doc
                     HAVING count(DISTINCT k_sadrzaj_umnozak) > 1
                      ORDER BY su
                      LIMIT 10)
 ORDER BY su, id
) q;
