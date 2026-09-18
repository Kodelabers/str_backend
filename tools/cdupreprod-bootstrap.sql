-- Bootstrap sheme za CDU predprodukciju (172.20.8.212:5432/eturizam).
--
-- ZAŠTO POSTOJI: baza se resetira svake noći. Ako reset briše i našu shemu, nitko je ne kreira
-- natrag — `LocalDatabaseConfig` radi samo na profilima `local` i `mock`, a na `cdupreprod`
-- Liquibase očekuje da shema iz `spring.liquibase.default-schema` već postoji i inače pada na
-- dizanju konteksta. Ova skripta je taj nedostajući korak.
--
-- IDEMPOTENTNA je — smije se pustiti svako jutro, i ručno, i dvaput.
--
-- Pokretanje (iz tools/cdupreprod-nightly.sh, ili ručno):
--   psql "postgresql://<user>@172.20.8.212:5432/eturizam" -v ON_ERROR_STOP=1 -f cdupreprod-bootstrap.sql
--
-- POZOR: imena sheme i role su PRETPOSTAVKA (str_rn / str_owner, kao na CDU testu i InfoDom
-- preprodu). Uputa je stigla s `currentSchema=xxx` i `role=xxx_owner`. Kad DBA potvrdi imena,
-- ispravi ih ovdje i u .env.cdupreprod (CDUPREPROD_DB_URL).

\set schema_name str_rn
\set owner_role  str_owner

-- 1. Shema. AUTHORIZATION je bitan: objekti moraju pripasti roli, ne osobnom useru — inače
--    aplikacija, koja se preko `options=-c role=str_owner` predstavlja kao rola, svoje tablice
--    nakon idućeg reseta više ne vidi kao vlasnik.
--    Traži CREATE pravo na bazi. Ako padne s "permission denied for database eturizam", shemu
--    mora kreirati DBA — to nije nešto što zaobilazimo konfiguracijom.
CREATE SCHEMA IF NOT EXISTS :schema_name AUTHORIZATION :owner_role;

-- 2. Ostatak sheme (tablice, indeksi, spring_session, changelog) radi Liquibase pri dizanju
--    backenda. Ovdje se namjerno ništa više ne kreira — dva izvora istine za DDL su gore
--    zabranjena (ddl-auto=none, sve kroz numerirane changesete).

-- 3. Provjera stanja — ide u jutarnji log da se odmah vidi zašto nešto ne radi.
--    Grantove na TUĐE sheme ne možemo dati sami; njih daju vlasnici (gis_owner za rpj_dgu i
--    eturizam_test, tustart_owner za str). Ako reset briše i ACL-ove, ovaj ispis je prvo mjesto
--    gdje će se to vidjeti.
SELECT current_user                                        AS spojen_kao,
       has_schema_privilege(:'schema_name', 'USAGE')       AS str_rn_usage,
       has_schema_privilege(:'schema_name', 'CREATE')      AS str_rn_create,
       has_schema_privilege('str', 'USAGE')                AS str_usage,
       has_schema_privilege('rpj_dgu', 'USAGE')            AS rpj_dgu_usage,
       has_schema_privilege('eturizam_test', 'USAGE')      AS eturizam_test_usage;

-- Bez USAGE na rpj_dgu / eturizam_test aplikacija se digne normalno, a padne samo adresna
-- kaskada u registracijskom formularu (županija → općina → naselje → ulica → kućni broj).
-- Tiha greška koja se inače otkrije tek kao prijavljeni bug. Zahtjev za DBA glasi:
--
--   -- izvršava gis_owner (ili superuser); samo SELECT, te su sheme za nas read-only
--   GRANT USAGE  ON SCHEMA rpj_dgu       TO str_owner;
--   GRANT USAGE  ON SCHEMA eturizam_test TO str_owner;
--   GRANT SELECT ON ALL TABLES IN SCHEMA rpj_dgu       TO str_owner;
--   GRANT SELECT ON ALL TABLES IN SCHEMA eturizam_test TO str_owner;
--   ALTER DEFAULT PRIVILEGES FOR ROLE gis_owner IN SCHEMA rpj_dgu       GRANT SELECT ON TABLES TO str_owner;
--   ALTER DEFAULT PRIVILEGES FOR ROLE gis_owner IN SCHEMA eturizam_test GRANT SELECT ON TABLES TO str_owner;
--
--   -- izvršava tustart_owner: popis objekata, OIB lookup i padajući izbornik država
--   GRANT USAGE  ON SCHEMA str TO str_owner;
--   GRANT SELECT ON str.facility, str.subject, str.subject_version, str.subject_address,
--                    str.address, str.country TO str_owner;
--
--   -- samo ako se testira tuStart handoff (upis izdanog RB-a natrag u eTurizam):
--   GRANT UPDATE (registration_number) ON str.facility TO str_owner;
--
-- Ako reset svake noći vraća ACL-ove iz dumpa, gornji grantovi moraju ući u NJIHOVU reset
-- skriptu — mi ih iz ove skripte ne možemo dati (nismo vlasnici tih shema).
