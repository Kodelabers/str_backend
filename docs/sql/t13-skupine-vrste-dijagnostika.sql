SELECT 'K0' AS blok, q.* FROM (
SELECT c.table_schema, c.table_name, c.column_name, c.data_type, c.ordinal_position
FROM information_schema.columns c
WHERE (c.table_schema = 'str' AND c.table_name IN ('facility_type', 'codebook_element')) OR (c.table_schema = 'str_rn' AND c.table_name = 'accommodation_type')
ORDER BY c.table_schema, c.table_name, c.ordinal_position
) q;

SELECT 'K1' AS blok, q.* FROM (
SELECT ce.id, ce.code, ce.name, ce.active, (SELECT count(*) FROM str.facility_type ft WHERE ft.type_id = ce.id AND coalesce(ft.active, true) = true) AS aktivnih_zapisa
FROM str.codebook_element ce
WHERE ce.code LIKE 'FT\_%'
ORDER BY ce.code
) q;

SELECT 'K2' AS blok, q.* FROM (
SELECT c_t.code AS skupina_kod, c_t.name AS skupina_naziv, c_s.code AS vrsta_kod, c_s.name AS vrsta_naziv, count(*) AS zapisa, count(*) FILTER (WHERE c_st.code = 'FBS_ACTIVE') AS posluje
FROM str.facility_type ft
JOIN str.facility f ON f.id = ft.facility_id
LEFT JOIN str.codebook_element c_t ON c_t.id = ft.type_id
LEFT JOIN str.codebook_element c_s ON c_s.id = ft.sub_type_id
LEFT JOIN str.codebook_element c_st ON c_st.id = f.business_status_id
WHERE coalesce(ft.active, true) = true
GROUP BY c_t.code, c_t.name, c_s.code, c_s.name
ORDER BY c_t.code, c_s.code
) q;

SELECT 'K3' AS blok, q.* FROM (
SELECT c_s.code AS vrsta_kod, count(DISTINCT c_t.code) AS broj_skupina, string_agg(DISTINCT c_t.code, ', ') AS skupine
FROM str.facility_type ft
LEFT JOIN str.codebook_element c_t ON c_t.id = ft.type_id
LEFT JOIN str.codebook_element c_s ON c_s.id = ft.sub_type_id
WHERE coalesce(ft.active, true) = true AND c_s.code IS NOT NULL
GROUP BY c_s.code
HAVING count(DISTINCT c_t.code) > 1
ORDER BY c_s.code
) q;

SELECT 'K4' AS blok, q.* FROM (
SELECT at.type_id, at.name, at.group_name, at.code, at.registration_number_allowed
FROM str_rn.accommodation_type at
ORDER BY at.type_id
) q;

SELECT 'K5' AS blok, q.* FROM (
SELECT ce.id, ce.code, ce.name, ce.active
FROM str.codebook_element ce
WHERE ce.name ILIKE '%stali ugostitelj%' OR ce.name ILIKE '%OPG%' OR ce.name ILIKE '%poljoprivredn%' OR ce.name ILIKE '%vodi%' OR ce.name ILIKE '%zajednic%'
ORDER BY ce.code
) q;
