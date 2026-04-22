# MASTER FUNKCIONALNA SPECIFIKACIJA: STR BACKEND MODUL (v1.6)
**Datum:** 21. travnja 2026.  
**Uloga:** Senior Backend Engineering (Kodelab / Infodom)
**Tehnološki okvir:** Java 21 (LTS), Spring Boot 3.2+, PostgreSQL

---

## 1. TEHNIČKI OKVIR I ARHITEKTURA
Sustav se temelji na mikroservisnoj logici unutar zajedničke baze s jasnim razdvajanjem ovlasti.

### 1.1 Strategija Baze Podataka
- **Shema `core` (Read-Only):** Backend isključivo čita matične podatke (vlasnici, objekti, rješenja). Nikakve `WRITE` operacije nisu dopuštene.
- **Shema `str` (Read-Write):** Backend je isključivi vlasnik. Sadrži tablice za registraciju, snapshote i audit logove.
- **Migracije:** `spring.jpa.hibernate.ddl-auto=none`. Sve promjene se rade ručnim SQL skriptama.

### 1.2 Java 21 Standardi
- Koristiti **Java Records** za sve DTO objekte (nepromjenjivost).
- Koristiti **Pattern Matching** za `switch` izraze i `instanceof`.
- Optimizacija za **Virtual Threads** (Project Loom).

---

## 2. MODEL PODATAKA (STR SHEMA)

### 2.1 Tablica `str.sso` (Smještajni Objekt)
Glavni entitet registracije koji proširuje core podatke.
- `uuid_sso`: UUID (Primary Key, veza na `core.objekt`).
- `registracijski_broj`: VARCHAR(18), UNIQUE. Format: `HR` + 8 nasumičnih znamenki.
- `kapacitet_kreveta`, `kapacitet_gostiju`: Integer.
- `ponuda`: Enum (`DIO`, `CJELINA`).
- `kat`, `broj_stana`: String (za stambene zgrade).
- `status`: Enum (`U_OBRADI`, `AKTIVAN`, `SUSPENDIRAN`, `POVUCEN`).

### 2.2 Tablica `str.iznajmljivac`
Snapshot podataka iznajmljivača u trenutku registracije.
- `oib`: CHAR(11).
- `naziv_prezime`: VARCHAR.
- `adresa_prebivalista`: VARCHAR.
- `is_domacin`: Boolean (rezultat GO-1 obrade).

---

## 3. LOGIKA GRUPNIH OBRADA (VALIDACIJSKI ENGINE)
Backend mora implementirati sekvencijalni validacijski sustav koji izvršava svih 5 provjera prije aktivacije RB-a.

### GO-1: Status Domaćina (Host Verification)
- **Logika:** Usporedba JLS (županija/grad) iz `adresa_prebivalista` iznajmljivača i lokacije objekta iz `core` sheme.
- **Rezultat:** Postavlja `is_domacin` (Boolean). Određuje porezni/regulatorni tretman.

### GO-2: Tip Građevine (Building Context)
- **Logika:** Provjera broja stambenih jedinica na adresi putem MPGI registra.
- **Pravilo:** Ako je broj jedinica **> 3**, objekt se klasificira kao "stan u zgradi", što automatski aktivira obvezu za **GO-4**.

### GO-3: Legalnost Objekta (Legality Check)
- **Logika:** Provjera statusa rješenja u `core` shemi i akata o uporabi u MPGI.
- **Pravilo:** Ako objekt nije "Legalan", sustav blokira daljnji proces uz trigger greške korisniku.

### GO-4: Suglasnost Suvlasnika (Co-owner Consent)
- **Logika:** Provjera postojanja i validnosti digitalne suglasnosti suvlasnika u DGU registru.
- **Pravilo:** Obvezno za sve objekte označene u **GO-2**. Bez suglasnosti, status ostaje `U_OBRADI`.

### GO-5: Provjera Kapaciteta (Capacity Audit)
- **Logika:** Usporedba unesenih polja `max_kreveta/gostiju` s maksimalnim vrijednostima iz rješenja u `core`.
- **Pravilo:** Unos ne smije premašiti kapacitet definiran u izvornom rješenju o kategorizaciji.

---

## 4. STATUSNI MODEL (STATE MACHINE)

| Status | Uvjet (Trigger) | Sljedeći korak |
| :--- | :--- | :--- |
| **INICIIRAN** | Dohvaćeni podaci iz Core-a | Korisnički unos polja |
| **VALIDACIJA** | Pokrenuti GO-1 do GO-5 | Analiza rezultata |
| **U_OBRADI** | Čeka se GO-4 ili Digitalni potpis | Callback potvrda |
| **AKTIVAN** | Svi GO prošli + Potpis potvrđen | SDEP vidljivost |
| **SUSPENDIRAN** | Istek suglasnosti ili inspekcija | Blokada