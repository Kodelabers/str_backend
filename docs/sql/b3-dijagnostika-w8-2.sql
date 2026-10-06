SELECT 'W1' AS blok, q.* FROM (
SELECT c.facility_id, c.id AS sadrzaj_id, ce.code AS sadrzaj_tip, c.quantity AS broj_jednakih, c.active AS sadrzaj_active,
       cce.code AS kapacitet_tip, cc.quantity AS kreveta, cc.active AS kapacitet_active
  FROM str.facility_content c
  LEFT JOIN str.codebook_element ce          ON ce.id = c.type_id
  LEFT JOIN str.facility_content_capacity cc ON cc.facility_content_id = c.id
  LEFT JOIN str.codebook_element cce         ON cce.id = cc.type_id
 WHERE c.facility_id IN (1100263, 1440696, 1444993, 1444999, 1445081)
 ORDER BY c.facility_id, c.id, cce.code
) q;

SELECT 'W2' AS blok, q.* FROM (
SELECT a.facility_id,
       to_jsonb(a) - ARRAY['oib', 'email', 'phone', 'contact_email', 'contact_phone', 'lessor_oib'] AS accommodation
  FROM str_rn.accommodation a
 WHERE a.facility_id IN ('1100263', '1440696', '1444993', '1444999', '1445081')
 ORDER BY a.facility_id
) q;

SELECT 'W3' AS blok, q.* FROM (
SELECT a.facility_id, r.status, to_jsonb(r) - ARRAY['oib', 'lessor_oib'] AS registracijski_broj
  FROM str_rn.registration_number r
  JOIN str_rn.accommodation a ON a.accommodation_id = r.accommodation_id
 WHERE a.facility_id IN ('1100263', '1440696', '1444993', '1444999', '1445081')
 ORDER BY a.facility_id
) q;
