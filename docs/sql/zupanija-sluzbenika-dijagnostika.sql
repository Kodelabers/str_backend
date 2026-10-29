SELECT 'Z1' AS blok, q.* FROM (
SELECT c.table_name, c.ordinal_position, c.column_name, c.data_type, c.is_nullable
FROM information_schema.columns c
WHERE c.table_schema = 'str'
AND (c.table_name LIKE 'application_user%'
OR c.table_name LIKE '%organi%'
OR c.table_name LIKE '%jurisdiction%'
OR c.table_name = 'county')
ORDER BY c.table_name, c.ordinal_position
) q;

SELECT 'Z2' AS blok, q.* FROM (
SELECT tc.table_name, kcu.column_name, ccu.table_schema AS ref_schema, ccu.table_name AS ref_table, ccu.column_name AS ref_column
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name AND kcu.constraint_schema = tc.constraint_schema
JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name AND ccu.constraint_schema = tc.constraint_schema
WHERE tc.constraint_type = 'FOREIGN KEY'
AND tc.table_schema = 'str'
AND (tc.table_name LIKE 'application_user%'
OR tc.table_name LIKE '%organi%'
OR tc.table_name LIKE '%jurisdiction%'
OR ccu.table_name LIKE '%organi%'
OR ccu.table_name LIKE '%jurisdiction%')
ORDER BY tc.table_name, kcu.column_name
) q;

SELECT 'Z3' AS blok, q.* FROM (
SELECT t.table_name, t.table_type
FROM information_schema.tables t
WHERE t.table_schema = 'str'
AND (t.table_name LIKE '%organi%' OR t.table_name LIKE '%jurisdiction%')
ORDER BY t.table_name
) q;

SELECT 'Z4' AS blok, q.* FROM (
SELECT count(*) AS internal_rn_management, count(DISTINCT u.id) AS users
FROM str.application_user u
JOIN str.application_user_roles ur ON ur.users_id = u.id
WHERE ur.roles_sys_name = 'RN_MANAGEMENT' AND u.internal = TRUE AND u.active = TRUE
) q;
