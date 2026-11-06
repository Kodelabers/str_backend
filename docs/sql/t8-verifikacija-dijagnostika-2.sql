SELECT 'W0' AS blok, q.* FROM (
SELECT current_database() AS baza, current_user AS korisnik, now() AS sada_tz, localtimestamp AS sada_lokalno, current_setting('TimeZone') AS zona, (SELECT max(vr.created_date) FROM str.verification_request vr) AS zadnji_zahtjev
) q;

SELECT 'W1' AS blok, q.* FROM (
SELECT vr.id AS zahtjev_id, vr.active, d.id AS document_id, d.subtype_code, d.verified AS doc_verified, d.created_by = 'optimit' AS doc_migriran, d.main_document_id IS NULL AS glavni_dokument, d.business_case_id IS NOT NULL AS ima_predmet, d.jurisdiction_organizational_unit_id AS nadlezna_jedinica, d.registration_number IS NOT NULL AS doc_ima_rb, (SELECT count(*) FROM str.facility f WHERE f.document_id = d.id) AS objekata, (SELECT count(*) FROM str.facility f WHERE f.document_id = d.id AND f.created_by = 'optimit') AS objekata_migriranih, (SELECT count(*) FROM str.facility f WHERE f.document_id = d.id AND f.active = TRUE) AS objekata_aktivnih, (SELECT count(*) FROM str.application_user u WHERE u.username = vr.created_by) AS podnositelj_je_korisnik, (SELECT bool_or(u.internal) FROM str.application_user u WHERE u.username = vr.created_by) AS podnositelj_interni
FROM str.verification_request vr
JOIN str.document d ON d.id = vr.source_document_id
ORDER BY vr.id
) q;

SELECT 'W2' AS blok, q.* FROM (
SELECT vr.id AS zahtjev_id, vr.created_date AS zahtjev_stvoren, count(DISTINCT a.accommodation_id) AS str_smjestaja, count(DISTINCT r.rn) AS str_rb
FROM str.verification_request vr
JOIN str.facility f ON f.document_id = vr.source_document_id
LEFT JOIN str_rn.accommodation a ON a.facility_id = f.id::text
LEFT JOIN str_rn.registration_number r ON r.accommodation_id = a.accommodation_id
GROUP BY vr.id, vr.created_date
ORDER BY vr.id
) q;

SELECT 'W3' AS blok, q.* FROM (
SELECT vr.id AS zahtjev_id, d.jurisdiction_organizational_unit_id AS jedinica_id, io.name AS organizacija, ce.code AS vrsta_organizacije, (SELECT count(*) FROM str.application_user u WHERE u.organizational_unit_id = ou.id AND u.active = TRUE AND u.internal = TRUE) AS korisnika_u_jedinici, (SELECT count(*) FROM str.application_user u WHERE u.internal_organization_id = io.id AND u.active = TRUE AND u.internal = TRUE) AS korisnika_u_organizaciji
FROM str.verification_request vr
JOIN str.document d ON d.id = vr.source_document_id
LEFT JOIN str.organizational_unit ou ON ou.id = d.jurisdiction_organizational_unit_id
LEFT JOIN str.internal_organization io ON io.id = ou.internal_organization_id
LEFT JOIN str.codebook_element ce ON ce.id = io.type_id
ORDER BY vr.id
) q;

SELECT 'W4' AS blok, q.* FROM (
SELECT ce.code AS vrsta_organizacije, ur.roles_sys_name AS rola, count(DISTINCT u.id) AS korisnika
FROM str.application_user u
JOIN str.internal_organization io ON io.id = u.internal_organization_id AND io.active = TRUE
JOIN str.codebook_element ce ON ce.id = io.type_id
JOIN str.application_user_roles ur ON ur.users_id = u.id
WHERE u.active = TRUE AND u.internal = TRUE AND ce.code IN ('IOT_ZUPANIJA', 'IOT_MINTS')
GROUP BY ce.code, ur.roles_sys_name
ORDER BY 1, 3 DESC
) q;

SELECT 'W5' AS blok, q.* FROM (
SELECT c.name AS zupanija, c.external_code AS sifra, count(DISTINCT io.id) AS organizacija, count(DISTINCT u.id) AS sluzbenika, count(DISTINCT u.id) FILTER (WHERE EXISTS (SELECT 1 FROM str.application_user_roles ur WHERE ur.users_id = u.id AND ur.roles_sys_name = 'RN_MANAGEMENT')) AS s_rn_management, count(DISTINCT u.id) FILTER (WHERE u.email IS NOT NULL AND btrim(u.email) <> '') AS s_emailom
FROM str.application_user u
JOIN str.internal_organization io ON io.id = u.internal_organization_id AND io.active = TRUE
JOIN str.codebook_element ce ON ce.id = io.type_id AND ce.code = 'IOT_ZUPANIJA'
JOIN str.organizational_unit ou ON ou.internal_organization_id = io.id AND ou.active = TRUE
JOIN str.settlement_jurisdiction sj ON sj.organizational_unit_id = ou.id AND sj.active = TRUE
JOIN str.settlement s ON s.id = sj.settlement_id
JOIN str.municipality m ON m.id = s.municipality_id
JOIN str.county c ON c.id = m.county_id
WHERE u.active = TRUE AND u.internal = TRUE
GROUP BY c.name, c.external_code
ORDER BY c.name
) q;

SELECT 'W6' AS blok, q.* FROM (
SELECT count(*) AS str_smjestaja_s_objektom, count(f.id) AS pronadeno_u_str_facility, count(f.document_id) AS s_dokumentom, count(*) FILTER (WHERE f.created_by = 'optimit') AS migriranih, count(DISTINCT f.document_id) FILTER (WHERE f.created_by = 'optimit') AS dokumenata_migriranih, count(DISTINCT vr.id) AS vec_ima_zahtjev, count(DISTINCT d.jurisdiction_organizational_unit_id) AS razlicitih_nadleznih_jedinica, count(*) FILTER (WHERE f.document_id IS NOT NULL AND d.jurisdiction_organizational_unit_id IS NULL) AS dokument_bez_nadlezne_jedinice
FROM str_rn.accommodation a
LEFT JOIN str.facility f ON f.id::text = a.facility_id
LEFT JOIN str.document d ON d.id = f.document_id
LEFT JOIN str.verification_request vr ON vr.source_document_id = f.document_id
WHERE a.facility_id IS NOT NULL
) q;

SELECT 'W7' AS blok, q.* FROM (
SELECT d.subtype_code, count(*) AS dokumenata_objekata
FROM str_rn.accommodation a
JOIN str.facility f ON f.id::text = a.facility_id
JOIN str.document d ON d.id = f.document_id
GROUP BY d.subtype_code
ORDER BY 2 DESC
) q;
