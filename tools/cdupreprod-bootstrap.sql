-- Provjera sheme za CDU predprodukciju (172.20.8.212:5432/eturizam), prije dizanja backenda.
--
-- KAKO RADI NOĆNI RESET (potvrdio DBA 18.09.2026.):
--   „tablice će bit dropane, ostat će shema i role. Kod startanja STR-a treba rekreirati tablice
--   i popuniti inicijalnim podacima ako su potrebni."
--   Dakle `str_rn` ostaje, a prazni se. Liquibase pri jutarnjem restartu gradi tablice od nule
--   (changelog je također dropan) i seed changeseti bez konteksta pune inicijalne podatke.
--
-- ZAŠTO SKRIPTA IPAK NE KREIRA SHEMU:
--   izmjereno 18.09.2026., `has_database_privilege(current_database(),'CREATE')` = FALSE
--   i kao `shorttermrental` i kao `str_owner`. Da shema ikad ipak nestane, ne bismo je mogli
--   vratiti — zato je provjera ostala kao zaštita, iako po dogovoru taj slučaj ne bi trebao doći.
--
-- Skripta samo PROVJERAVA i, u dva slučaja, **namjerno puca** s uputom. To je ispravan ishod:
-- bolje da jutarnji oporavak stane ovdje, nego da restarta backend u bazu iz koje ionako ne
-- može poslužiti ni jedan upit.
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

-- 1b. Je li stanje sheme KONZISTENTNO?
--     Reset koji dropa "sve tablice" briše i Liquibaseov `databasechangelog` — tada je shema
--     prazna i Liquibase uredno gradi sve od nule. Problem je asimetričan slučaj: podatkovne
--     tablice obrisane, a `databasechangelog` ostao. Liquibase tada zaključi da je sve već
--     primijenjeno, NE kreira ništa, a backend se digne nad praznom shemom i svaki upit puca —
--     pritom u logu nema nijedne greške na startu, pa se kvar otkrije tek kad tester otvori
--     formular.
--     Ovo je jedini popravak koji je U NAŠIM RUKAMA (vlasnici smo str_rn): obrisati changelog
--     tablice da Liquibase ponovno odradi cijeli changelog. Namjerno se NE izvršava automatski —
--     DROP je destruktivan, a skripta se vrti bez nadzora.
DO $$
DECLARE
    ima_changelog boolean;
    ima_tablice   boolean;
BEGIN
    SELECT EXISTS (SELECT 1 FROM pg_tables
                    WHERE schemaname = 'str_rn' AND tablename = 'databasechangelog')
      INTO ima_changelog;
    SELECT EXISTS (SELECT 1 FROM pg_tables
                    WHERE schemaname = 'str_rn' AND tablename = 'registration_number')
      INTO ima_tablice;

    IF ima_changelog AND NOT ima_tablice THEN
        RAISE EXCEPTION
            'NEKONZISTENTNA SHEMA: databasechangelog postoji, ali registration_number ne. '
            'Reset je obrisao podatkovne tablice a changelog ostavio, pa Liquibase nece nista '
            'kreirati i backend bi radio nad praznom shemom. Popravak (mi smo vlasnici str_rn): '
            'DROP TABLE IF EXISTS str_rn.databasechangelog, str_rn.databasechangeloglock; '
            'pa ponovno pokreni oporavak. Ako se ponavlja svako jutro, trazi od DBA da drop '
            'pokriva SVE tablice u str_rn (vidi DEPLOY-CDU-PREPROD.md A1/6b).';
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
