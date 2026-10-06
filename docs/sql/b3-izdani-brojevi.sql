DROP TABLE IF EXISTS pg_temp.t_b3_rb;

CREATE TEMP TABLE t_b3_rb AS
WITH rb AS (
    SELECT r.status, a.facility_id::bigint AS facility_id,
           to_jsonb(r) - ARRAY['oib', 'lessor_oib'] AS rb_redak,
           (to_jsonb(a) ->> 'max_beds')::int        AS max_beds_uz_rb,
           (to_jsonb(a) ->> 'max_guests')::int      AS max_guests_uz_rb
      FROM str_rn.registration_number r
      JOIN str_rn.accommodation a ON a.accommodation_id = r.accommodation_id
     WHERE a.facility_id ~ '^[0-9]+$')
SELECT rb.status, rb.facility_id, rb.rb_redak, rb.max_beds_uz_rb, rb.max_guests_uz_rb,
       f.created_by = 'optimit' AS migriran,
       f.system_uuid,
       (SELECT count(*) FROM str.facility x
         WHERE x.system_uuid = f.system_uuid AND x.document_id = f.document_id
           AND x.active = true AND coalesce(x.historical, false) = false) AS jedinica_u_predmetu,
       coalesce(
           (SELECT sum(fc.quantity) FROM str.facility_capacity fc
              JOIN str.codebook_element ce ON ce.id = fc.type_id
             WHERE fc.facility_id = f.id AND fc.active = true AND ce.code = 'CAT_BROJ_KREVETA'),
           (SELECT sum(fcc.quantity * c.quantity) FROM str.facility_content c
              JOIN str.facility_content_capacity fcc ON fcc.facility_content_id = c.id AND fcc.active = true
              JOIN str.codebook_element ce ON ce.id = fcc.type_id
             WHERE c.facility_id = f.id AND c.active = true AND ce.code = 'CAT_BROJ_KREVETA')) AS kreveti_objekta,
       (SELECT sum(fc.quantity) FROM str.facility_capacity fc
          JOIN str.codebook_element ce ON ce.id = fc.type_id
         WHERE fc.facility_id = f.id AND fc.active = true AND ce.code = 'CAT_BROJ_POM_KREVETA') AS pomocni_objekta
  FROM rb
  JOIN str.facility f ON f.id = rb.facility_id;

SELECT 'I1' AS blok, q.* FROM (
SELECT status,
       count(*)                                                                  AS brojeva,
       count(*) FILTER (WHERE migriran AND jedinica_u_predmetu > 1)              AS migrirani_vise_jedinica,
       count(*) FILTER (WHERE migriran AND jedinica_u_predmetu > 1
                          AND max_beds_uz_rb = kreveti_objekta + coalesce(pomocni_objekta, 0)) AS upisan_kapacitet_objekta,
       count(*) FILTER (WHERE migriran AND jedinica_u_predmetu > 1
                          AND max_beds_uz_rb IS DISTINCT FROM kreveti_objekta + coalesce(pomocni_objekta, 0)) AS upisano_drugo
  FROM t_b3_rb
 GROUP BY status
 ORDER BY status
) q;

SELECT 'I2' AS blok, q.* FROM (
SELECT status, facility_id, system_uuid, jedinica_u_predmetu, kreveti_objekta, pomocni_objekta,
       max_beds_uz_rb, max_guests_uz_rb, rb_redak
  FROM t_b3_rb
 WHERE migriran AND jedinica_u_predmetu > 1
 ORDER BY status, facility_id
) q;
