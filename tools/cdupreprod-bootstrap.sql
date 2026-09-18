-- Provjera sheme za CDU predprodukciju (172.20.8.212:5432/eturizam), prije dizanja backenda.
--
-- ZAŠTO NE KREIRA SHEMU, iako se baza resetira svake noći:
--   izmjereno 18.09.2026., `has_database_privilege(current_database(),'CREATE')` = FALSE
--   i kao `shorttermrental` i kao `str_owner`. Nemamo pravo kreirati shemu u toj bazi.
--   `CREATE SCHEMA` bi zato pao — a u najgorem slučaju pao bi na provjeri prava i onda kad
--   shema POSTOJI, pa bi rušio jutarnju skriptu svaki dan bez stvarnog razloga.
--
-- Zato ova skripta samo PROVJERAVA i, ako sheme nema, **namjerno puca** s uputom. To je
-- ispravan ishod: bolje da jutarnji oporavak stane ovdje, nego da restarta backend u bazu bez
-- sheme, gdje Liquibase ionako pada pri dizanju konteksta.
--
-- Izmjereno stanje 18.09.2026. (user `shorttermrental`, uz `SET ROLE str_owner`):
--   str_rn        postoji, vlasnik str_owner, 0 tablica, CREATE na shemi = t  → Liquibase prolazi
--   str           USAGE = t, SELECT na facility/subject/country = t
--   rpj_dgu       USAGE = t, SELECT na zupanije = t
--   eturizam_test USAGE = t, SELECT na ar_ulice = t
--   str.facility  UPDATE = f  → RB se ne upisuje natrag u eTurizam (tuStart handoff)
-- Dakle grantovi su VEĆ na mjestu; blokada koja je zaustavila InfoDom predprodukciju ovdje
-- ne postoji. Otvoreno je samo preživljavaju li ti grantovi noćni reset.
--
-- IDEMPOTENTNA je — smije se pustiti svako jutro, i ručno, i dvaput.
--
-- Pokretanje (iz tools/cdupreprod-nightly.sh, ili ručno):
--   psql -h 172.20.8.212 -U shorttermrental -d eturizam -v ON_ERROR_STOP=1 -f cdupreprod-bootstrap.sql

\set schema_name str_rn
\set owner_role  str_owner

-- 1. Postoji li shema? Ako ne postoji, stani odmah i reci što treba napraviti i tko.
DO $$
DECLARE
    ima_shemu boolean;
BEGIN
    SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'str_rn') INTO ima_shemu;
    IF NOT ima_shemu THEN
        RAISE EXCEPTION
            'Shema str_rn ne postoji, a nemamo CREATE na bazi — ne možemo je vratiti. '
            'Noćni reset ju je obrisao. Traži od DBA jedno od: (1) izuzmi str_rn iz reseta, '
            '(2) neka reset skripta radi CREATE SCHEMA str_rn AUTHORIZATION str_owner uz grantove, '
            '(3) daj nam CREATE na bazi eturizam. Backend se do tada NE SMIJE dizati.';
    END IF;
END $$;

-- 2. Tablice u shemi radi Liquibase pri dizanju backenda — ovdje se namjerno ne kreira ništa
--    (ddl-auto=none, sav DDL ide kroz numerirane changesete).

-- 3. Stanje prava — ide u jutarnji log da se odmah vidi zašto nešto ne radi.
--    Grantove na TUĐE sheme ne možemo dati sami (vlasnici su gis_owner i tustart_owner), pa je
--    ovaj ispis prvo mjesto na kojem se vidi ako ih je reset pomeo.
SELECT current_user                                   AS spojen_kao,
       session_user                                   AS prijavljen_kao,
       has_schema_privilege(:'schema_name', 'CREATE') AS liquibase_smije,
       (SELECT count(*) FROM pg_tables
         WHERE schemaname = :'schema_name')           AS tablica_u_str_rn,
       has_table_privilege('str.facility', 'SELECT')  AS str_facility,
       has_table_privilege('rpj_dgu.zupanije', 'SELECT')      AS rpj_dgu,
       has_table_privilege('eturizam_test.ar_ulice', 'SELECT') AS eturizam_test;

-- `spojen_kao` mora biti str_owner, a `prijavljen_kao` shorttermrental — tako radi i aplikacija
-- (options=-c role=str_owner u JDBC URL-u). Ako su jednaki, rola nije postavljena i Liquibase
-- će kreirati objekte u krivom vlasništvu.
--
-- Ako neki od SELECT stupaca padne na `f`, grantove vraća vlasnik sheme, ne mi:
--   -- gis_owner:
--   GRANT USAGE ON SCHEMA rpj_dgu, eturizam_test TO str_owner;
--   GRANT SELECT ON ALL TABLES IN SCHEMA rpj_dgu       TO str_owner;
--   GRANT SELECT ON ALL TABLES IN SCHEMA eturizam_test TO str_owner;
--   -- tustart_owner:
--   GRANT USAGE ON SCHEMA str TO str_owner;
--   GRANT SELECT ON str.facility, str.subject, str.subject_version, str.subject_address,
--                    str.address, str.country TO str_owner;
--   -- samo ako se testira tuStart handoff (izmjereno: UPDATE = f):
--   GRANT UPDATE (registration_number) ON str.facility TO str_owner;
