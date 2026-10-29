SELECT 'Y0' AS blok, q.* FROM (
SELECT c.table_name, c.column_name, c.data_type
FROM information_schema.columns c
WHERE c.table_schema = 'str' AND c.table_name IN ('settlement', 'municipality')
ORDER BY c.table_name, c.ordinal_position
) q;

SELECT 'Y1' AS blok, q.* FROM (
SELECT io.type_id, ce.code AS type_code, ce.name AS type_name, count(*) AS organizations, count(io.rkp) AS with_rkp, count(io.pin) AS with_pin
FROM str.internal_organization io
LEFT JOIN str.codebook_element ce ON ce.id = io.type_id
GROUP BY io.type_id, ce.code, ce.name
ORDER BY io.type_id
) q;

SELECT 'Y2' AS blok, q.* FROM (
SELECT u.id AS user_id, u.internal_organization_id, io.name AS organization, ce.code AS org_type, io.rkp IS NOT NULL AS has_rkp, u.organizational_unit_id, ou.name AS unit, ou.internal_organization_id AS unit_org_id, ou.parent_id AS unit_parent_id, sc.name AS unit_seat_county, sc.external_code AS unit_seat_zu, (SELECT count(*) FROM str.possible_organizational_unit p WHERE p.application_user_id = u.id AND p.active = TRUE) AS possible_units
FROM str.application_user u
JOIN str.application_user_roles ur ON ur.users_id = u.id AND ur.roles_sys_name = 'RN_MANAGEMENT'
LEFT JOIN str.internal_organization io ON io.id = u.internal_organization_id
LEFT JOIN str.codebook_element ce ON ce.id = io.type_id
LEFT JOIN str.organizational_unit ou ON ou.id = u.organizational_unit_id
LEFT JOIN str.municipality sm ON sm.id = ou.municipality_id
LEFT JOIN str.county sc ON sc.id = sm.county_id
WHERE u.internal = TRUE AND u.active = TRUE
ORDER BY u.id
) q;

SELECT 'Y3' AS blok, q.* FROM (
SELECT u.id AS user_id, count(DISTINCT sj.settlement_id) AS jurisdiction_settlements, count(DISTINCT c.id) AS jurisdiction_counties, string_agg(DISTINCT c.name || ' (' || coalesce(c.external_code, '-') || ')', ', ') AS counties
FROM str.application_user u
JOIN str.application_user_roles ur ON ur.users_id = u.id AND ur.roles_sys_name = 'RN_MANAGEMENT'
LEFT JOIN str.settlement_jurisdiction sj ON sj.organizational_unit_id = u.organizational_unit_id AND sj.active = TRUE
LEFT JOIN str.settlement s ON s.id = sj.settlement_id
LEFT JOIN str.municipality m ON m.id = s.municipality_id
LEFT JOIN str.county c ON c.id = m.county_id
WHERE u.internal = TRUE AND u.active = TRUE
GROUP BY u.id
ORDER BY u.id
) q;

SELECT 'Y4' AS blok, q.* FROM (
SELECT io.id AS org_id, io.name AS organization, ce.code AS org_type, count(DISTINCT ou.id) AS units, count(DISTINCT sj.settlement_id) AS jurisdiction_settlements, count(DISTINCT c.id) AS jurisdiction_counties, min(c.name) AS first_county
FROM str.internal_organization io
LEFT JOIN str.codebook_element ce ON ce.id = io.type_id
LEFT JOIN str.organizational_unit ou ON ou.internal_organization_id = io.id AND ou.active = TRUE
LEFT JOIN str.settlement_jurisdiction sj ON sj.organizational_unit_id = ou.id AND sj.active = TRUE
LEFT JOIN str.settlement s ON s.id = sj.settlement_id
LEFT JOIN str.municipality m ON m.id = s.municipality_id
LEFT JOIN str.county c ON c.id = m.county_id
WHERE io.active = TRUE
GROUP BY io.id, io.name, ce.code
ORDER BY io.name
) q;
