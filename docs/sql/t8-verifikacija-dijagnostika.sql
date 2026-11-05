SELECT 'V1' AS blok, q.* FROM (
SELECT current_database() AS baza, current_user AS korisnik, session_user AS sesija, now() AS vrijeme, to_regclass('str.verification_request') IS NOT NULL AS ima_verification_request, to_regclass('str.korisnicka_obavijest') IS NOT NULL AS ima_korisnicka_obavijest, CASE WHEN to_regclass('str.verification_request') IS NOT NULL THEN has_table_privilege(to_regclass('str.verification_request')::oid, 'SELECT') END AS vr_select, CASE WHEN to_regclass('str.verification_request') IS NOT NULL THEN has_table_privilege(to_regclass('str.verification_request')::oid, 'INSERT') END AS vr_insert, CASE WHEN to_regclass('str.verification_request') IS NOT NULL THEN has_table_privilege(to_regclass('str.verification_request')::oid, 'UPDATE') END AS vr_update, CASE WHEN to_regclass('str.korisnicka_obavijest') IS NOT NULL THEN has_table_privilege(to_regclass('str.korisnicka_obavijest')::oid, 'SELECT') END AS ko_select, CASE WHEN to_regclass('str.korisnicka_obavijest') IS NOT NULL THEN has_table_privilege(to_regclass('str.korisnicka_obavijest')::oid, 'INSERT') END AS ko_insert, CASE WHEN to_regclass('str.korisnicka_obavijest') IS NOT NULL THEN has_table_privilege(to_regclass('str.korisnicka_obavijest')::oid, 'UPDATE') END AS ko_update
) q;

SELECT 'V2' AS blok, q.* FROM (
SELECT c.table_name, c.ordinal_position, c.column_name, c.data_type, c.udt_name, c.character_maximum_length, c.is_nullable, c.column_default, c.is_identity
FROM information_schema.columns c
WHERE c.table_schema = 'str' AND c.table_name IN ('verification_request', 'korisnicka_obavijest')
ORDER BY c.table_name, c.ordinal_position
) q;

SELECT 'V3' AS blok, q.* FROM (
SELECT c.conrelid::regclass::text AS tablica, c.conname AS ogranicenje, c.contype AS vrsta, pg_get_constraintdef(c.oid) AS definicija
FROM pg_constraint c
WHERE c.conrelid IN (to_regclass('str.verification_request'), to_regclass('str.korisnicka_obavijest'))
ORDER BY 1, 3, 2
) q;

SELECT 'V4' AS blok, q.* FROM (
SELECT c.conrelid::regclass::text AS tablica_koja_pokazuje, c.conname AS ogranicenje, c.confrelid::regclass::text AS tablica_na_koju_pokazuje, pg_get_constraintdef(c.oid) AS definicija
FROM pg_constraint c
WHERE c.contype = 'f' AND c.confrelid IN (to_regclass('str.verification_request'), to_regclass('str.korisnicka_obavijest'))
ORDER BY 1, 2
) q;

SELECT 'V5' AS blok, q.* FROM (
SELECT c.confrelid::regclass::text AS sifrarnik, a.attnum, a.attname AS kolona, format_type(a.atttypid, a.atttypmod) AS tip, a.attnotnull AS not_null
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum > 0 AND NOT a.attisdropped
WHERE c.contype = 'f' AND c.conrelid IN (to_regclass('str.verification_request'), to_regclass('str.korisnicka_obavijest'))
GROUP BY c.confrelid, a.attnum, a.attname, a.atttypid, a.atttypmod, a.attnotnull
ORDER BY 1, 2
) q;

SELECT 'V6' AS blok, q.* FROM (
SELECT c.oid::regclass::text AS tablica, NULL::int AS attnum, NULL::text AS kolona, obj_description(c.oid, 'pg_class') AS komentar
FROM pg_class c
WHERE c.oid IN (to_regclass('str.verification_request'), to_regclass('str.korisnicka_obavijest'))
UNION ALL
SELECT a.attrelid::regclass::text, a.attnum, a.attname, col_description(a.attrelid, a.attnum)
FROM pg_attribute a
WHERE a.attrelid IN (to_regclass('str.verification_request'), to_regclass('str.korisnicka_obavijest')) AND a.attnum > 0 AND NOT a.attisdropped AND col_description(a.attrelid, a.attnum) IS NOT NULL
ORDER BY 1, 2 NULLS FIRST
) q;

SELECT 'V7' AS blok, q.* FROM (
SELECT i.tablename AS tablica, i.indexname AS indeks, i.indexdef AS definicija
FROM pg_indexes i
WHERE i.schemaname = 'str' AND i.tablename IN ('verification_request', 'korisnicka_obavijest')
ORDER BY 1, 2
) q;

SELECT 'V8' AS blok, q.* FROM (
SELECT t.tgrelid::regclass::text AS tablica, t.tgname AS okidac, t.tgenabled AS ukljucen, pg_get_triggerdef(t.oid) AS definicija, p.proname AS funkcija, pg_get_functiondef(p.oid) AS izvor_funkcije
FROM pg_trigger t
JOIN pg_proc p ON p.oid = t.tgfoid
WHERE NOT t.tgisinternal AND t.tgrelid IN (to_regclass('str.verification_request'), to_regclass('str.korisnicka_obavijest'))
ORDER BY 1, 2
) q;

SELECT 'V9' AS blok, q.* FROM (
SELECT n.nspname AS shema, p.proname AS funkcija, p.prokind AS vrsta, p.prosrc ILIKE '%verification_request%' AS spominje_verification_request, p.prosrc ILIKE '%korisnicka_obavijest%' AS spominje_korisnicka_obavijest
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND (p.prosrc ILIKE '%verification_request%' OR p.prosrc ILIKE '%korisnicka_obavijest%')
ORDER BY 1, 2
) q;

SELECT 'V10' AS blok, q.* FROM (
SELECT DISTINCT v.oid::regclass::text AS pogled, d.refobjid::regclass::text AS tablica, v.relkind AS vrsta
FROM pg_depend d
JOIN pg_rewrite r ON r.oid = d.objid
JOIN pg_class v ON v.oid = r.ev_class
WHERE d.refobjid IN (to_regclass('str.verification_request'), to_regclass('str.korisnicka_obavijest')) AND v.oid <> d.refobjid
ORDER BY 1
) q;

SELECT 'V11' AS blok, q.* FROM (
SELECT c.oid::regclass::text AS tablica, CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE a.grantee::regrole::text END AS primatelj, a.privilege_type AS pravo, a.is_grantable AS moze_dalje
FROM pg_class c
CROSS JOIN LATERAL aclexplode(c.relacl) a
WHERE c.oid IN (to_regclass('str.verification_request'), to_regclass('str.korisnicka_obavijest'))
ORDER BY 1, 2, 3
) q;

SELECT 'V12' AS blok, q.* FROM (
SELECT (SELECT count(*) FROM str.verification_request) AS redaka_verification_request, (SELECT count(*) FROM str.korisnicka_obavijest) AS redaka_korisnicka_obavijest
) q;

SELECT 'V13' AS blok, q.* FROM (
SELECT row_to_json(t)::text AS red
FROM (SELECT * FROM str.verification_request ORDER BY 1 DESC LIMIT 20) t
) q;

SELECT 'V14' AS blok, q.* FROM (
SELECT row_to_json(t)::text AS red
FROM (SELECT * FROM str.korisnicka_obavijest ORDER BY 1 DESC LIMIT 20) t
) q;

SELECT 'V15' AS blok, q.* FROM (
SELECT e.key AS kolona, left(e.value, 200) AS vrijednost, count(*) AS broj
FROM str.verification_request t
CROSS JOIN LATERAL jsonb_each_text(to_jsonb(t)) e
WHERE e.key IN (
SELECT x.kolona FROM (
SELECT e2.key AS kolona, count(DISTINCT e2.value) AS razlicitih
FROM str.verification_request t2
CROSS JOIN LATERAL jsonb_each_text(to_jsonb(t2)) e2
GROUP BY e2.key
) x WHERE x.razlicitih <= 30
)
GROUP BY e.key, left(e.value, 200)
ORDER BY 1, 3 DESC
) q;

SELECT 'V16' AS blok, q.* FROM (
SELECT e.key AS kolona, left(e.value, 200) AS vrijednost, count(*) AS broj
FROM str.korisnicka_obavijest t
CROSS JOIN LATERAL jsonb_each_text(to_jsonb(t)) e
WHERE e.key IN (
SELECT x.kolona FROM (
SELECT e2.key AS kolona, count(DISTINCT e2.value) AS razlicitih
FROM str.korisnicka_obavijest t2
CROSS JOIN LATERAL jsonb_each_text(to_jsonb(t2)) e2
GROUP BY e2.key
) x WHERE x.razlicitih <= 30
)
GROUP BY e.key, left(e.value, 200)
ORDER BY 1, 3 DESC
) q;
