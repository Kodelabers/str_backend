SELECT 'N1' AS blok, ce.code AS vrsta_organizacije, count(DISTINCT io.id) AS organizacija, count(DISTINCT ou.id) AS aktivnih_jedinica
  FROM str.internal_organization io
  LEFT JOIN str.codebook_element ce ON ce.id = io.type_id
  LEFT JOIN str.organizational_unit ou ON ou.internal_organization_id = io.id AND ou.active = TRUE
 WHERE io.active = TRUE
 GROUP BY ce.code
 ORDER BY ce.code;

SELECT 'N2' AS blok, c.id AS county_id, c.name AS str_naziv, c.external_code, z.zu_rb, z.zu_ime AS rpj_naziv
  FROM str.county c
  LEFT JOIN rpj_dgu.zupanije z ON CAST(z.zu_rb AS varchar) = ltrim(c.external_code, '0')
 ORDER BY c.id;

SELECT 'N3' AS blok, c.id AS county_id, c.name AS zupanija, ce.code AS vrsta_organizacije,
       count(DISTINCT io.id) AS nadleznih_organizacija, count(DISTINCT u.id) AS primatelja
  FROM str.county c
  JOIN str.municipality m ON m.county_id = c.id
  JOIN str.settlement s ON s.municipality_id = m.id
  JOIN str.settlement_jurisdiction sj ON sj.settlement_id = s.id AND sj.active = TRUE
  JOIN str.organizational_unit ou ON ou.id = sj.organizational_unit_id AND ou.active = TRUE
  JOIN str.internal_organization io ON io.id = ou.internal_organization_id AND io.active = TRUE
  LEFT JOIN str.codebook_element ce ON ce.id = io.type_id
  LEFT JOIN str.application_user u ON u.internal_organization_id = io.id AND u.active = TRUE AND u.internal = TRUE
 GROUP BY c.id, c.name, ce.code
 ORDER BY c.id, ce.code;

SELECT 'N4' AS blok, ce.code AS vrsta_organizacije,
       count(*) AS aktivnih_internih,
       count(*) FILTER (WHERE u.organizational_unit_id IS NULL) AS bez_jedinice,
       count(*) FILTER (WHERE u.internal_organization_id IS NULL) AS bez_organizacije
  FROM str.application_user u
  LEFT JOIN str.internal_organization io ON io.id = u.internal_organization_id
  LEFT JOIN str.codebook_element ce ON ce.id = io.type_id
 WHERE u.active = TRUE AND u.internal = TRUE
 GROUP BY ce.code
 ORDER BY ce.code;

SELECT 'N5' AS blok, a.county AS zupanija_str, count(*) AS novih_objekata_s_rjesenjem
  FROM str_rn.categorization_decision d
  JOIN str_rn.registration_number r ON r.rn = d.rn
  JOIN str_rn.accommodation a ON a.accommodation_id = r.accommodation_id
 WHERE a.facility_id IS NULL
 GROUP BY a.county
 ORDER BY a.county;
