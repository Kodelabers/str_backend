# STR Backend — Usporedba s Knjigom testiranja v3.0

**Izvor zahtjeva:** `knjiga_testiranja_STR_v4.docx` (Knjiga testiranja v3.0, MINTS, 17. 06. 2026.) — 17 testnih slučajeva, 12 UC-ova (STR-4.3 nije primjenjivo za RH).
**Usporedba protiv:** stvarnog backend koda (`com.str.backend`), verificiranog čitanjem izvora.
**Datum:** 2026-06-26

> **Ovaj dokument ispravlja i nadopunjuje `STR-BACKEND-VERIFIKACIJA-REZULTAT.md`.** Raniji izvještaj bio je rađen iz teksta prompta (frontend-agent MD), bez same Knjige. Sad je usporedba rađena izravno protiv službene Knjige testiranja. Najvažnije korekcije navedene su u §2.

Legenda: ✅ ispunjeno · 🟡 djelomično / odstupa · ❌ ne postoji · 🔴 blokirano vanjskom ovisnošću (NIAS role / KP / ePečat MINTS / SDEP / platforme / eGOP).

---

## Ažuriranje 2026-06-29 (nakon implementacije)

Tablice ispod su iz 26.06. (snimka prije izmjena). Od tada je u backendu implementirano:

- **1.4 / 4.2** — `verify` razlikuje 3 stanja: ACTIVE (RB + naziv/kategorija/adresa/skupina/vrsta), SUSPENDED (samo status), nepostojeći = opozvani `{valid:false}`. Privatnost scenarija B/D riješena. *(B2/B3 — PII u internom `detail` + javni/interni split — odgođeno do NIAS rola.)*
- **1.5** — `view=WITHDRAWN` i `view=INVALID` filteri; default registra promijenjen na `ACTIVE`.
- **2.1-001** — suspend razlog `INCOMPLETE_DOCUMENTATION` + generiranje akata „Dopis o namjeri" / „Nalog za suspenziju" (PDF, `GET /api/rn/{rn}/documents/{tip}`). Dostava u KP / platforme / urudžba i dalje blokirano.
- **2.1-002** — officer withdraw `?reason=` + „Nalog za povlačenje" (PDF); povlačenje je sada **trajno**.
- **2.2** — **zabranjen prijelaz `WITHDRAWN→ACTIVE`** (reaktivacija samo za suspendirane). *Prigovor je TuStart — izvan STR backenda (uklonjen).*
- **3.2** — Excel/CSV export `platform-activities` (`/api/statistics/platform-activities/xlsx|csv`).
- **3.3** — 18-mj. auto-purge scheduler aktivnosti + revizijski log (`str_rn.admin_audit_log`) za purge i lessor approve/reject.
- **1.3** — korisnički opoziv (`POST /api/lessor/registrations/{rn}/withdraw`, owner-gated) + 18-mj. retencijski scheduler (faza 1: detekcija + audit `RETENTION_DUE`, bez brisanja).

I dalje blokirano / odgođeno: KP + ePečat MINTS, Internetske platforme, eGOP urudžba, SDEP M2M, NIAS role-gating (BX0), te B2/B3 privatnost internog registra (čeka NIAS). RB format ostaje HR+18 (poslovna odluka).

---

## 1. TC-po-TC usporedba (svih 17)

### Grupa STR-1.x — Registracija (TuStart)

| TC | Što Knjiga očekuje (sažeto) | Backend | Status | Referenca / gap |
|----|------------------------------|---------|:------:|-----------------|
| **1.1-001** | Postojeća SJ, uspjeh: auto dodjela RB + **potvrda u KP potpisana ePečatom MINTS** + status „verificiran". | RB se dodjeljuje kroz GO pipeline; dostava ide email/eGOP (stub), ne KP/MINTS. „Verificiran" nije jasan koncept. | 🟡🔴 | `RegistrationService` · KP/MINTS = bloker (BX1) · „verificiran" otvoreno |
| **1.1-002** | Objekt **odjavljen** (ovlaštenje='NE'): RB se **svejedno dodjeljuje** + obavijest u KP da mora ishoditi ovlaštenje + „naznaka da ovlaštenje nije valjano". | GO-1 postavlja host flag ali **ne blokira** → RB se izdaje. KP obavijest nema. Naznaka „nevaljano ovlaštenje" se ne prikazuje. | 🟡🔴 | `Go1HostStatus` (ne odbija) ✅ izdavanje · KP = bloker (BX1) |
| **1.1-003** | Objekt **neverificiran**: RB na zadnji UUID + **servisna obavijest nadležnom službeniku** (u revizijskom logu) + nakon verifikacije status „verificiran". | Nema mehanizma servisne obavijesti službeniku; nema „verifikacija" toka. | ❌🔴 | nema notifikacija · bloker NIAS role + obavijesti |
| **1.1-004** | „Imam ovlaštenje, ali objekt nije na popisu": **materijalni unos rade referenti** kroz alate eTurizma 1, status „na obradi". | Nema te grane ni referentnog materijalnog unosa; submission ostaje `IN_PROCESSING` bez tooling-a. | ❌ | nema referentnog toka (eTurizam 1) |
| **1.2-001** | Nova SJ, uspjeh: auto RB + **potvrda u KP/ePečat MINTS** + informacija o ovlaštenju. | RB se dodjeljuje (grana „Objekt postoji? NE"); KP/MINTS nema. | 🟡🔴 | `RegistrationService` ✅ RB · KP/MINTS bloker |
| **1.2-002** | Validacija: prazna polja → greške; **0 kreveta → „kapacitet min. 1"**; ispravno → RB. | Bean-validation **postoji**: `@NotBlank`/`@NotNull` + `@Min(1)` na `maxBeds`/`maxGuests`, OIB `\d{11}`. | ✅ | `RegistrationRequest.java:14-29` (uskladiti tekst poruka na FE) |
| **1.3-001** | **Korisnički opoziv** (Moji objekti → Zahtjev za opoziv): status → **„Brisan"** + potvrda u KP + **auto-brisanje podataka nakon 18 mj.** | Postoji samo `POST /api/rn/{rn}/withdraw` (→`WITHDRAWN`), **bez** korisničkog self-service opoziva, **bez gatinga**, status se zove `WITHDRAWN` (ne „Brisan"), **nema 18-mj. schedulera**, KP nema. | 🟡❌🔴 | `RnController.java:75-78` · 18mj scheduler ❌ · KP bloker · opoziv≠withdraw |

### Grupa STR-1.x — Javni registar / nevažeći

| TC | Što Knjiga očekuje | Backend | Status | Referenca / gap |
|----|---------------------|---------|:------:|-----------------|
| **1.4-001** | 4 scenarija: (A) aktivan → prikaži RB+naziv+kategorija+adresa+skupina+vrsta; (B) nepostojeći → „nije pronađen"; (C) **suspendiran → status „Suspendiran" (nevažeći)**; (D) **opozvan → isti odgovor kao nepostojeći** (nevidljiv). **Javni popis = samo aktivni+verificirani.** | `verify` vraća `true` samo za ACTIVE, `false` za sve ostalo → **ne razlikuje** suspendiran (C) od nepostojećeg/opozvanog. `GET /api/rn` s `view=ALL` **izlaže** SUSPENDED/WITHDRAWN; `detail` **ne radi 404 za opozvan** i **izlaže OIB/email**. | 🟡❌ | `VerifyController.java:24-31` (B) ✅ ali (C) 🟡 · `RnController.java:39-58` lista/detalj ❌ privatnost |
| **1.5-001** | Interni popis nevažećih (suspendiran/opozvan), **izvor SDEP**, filter po statusu, **opozvan ostaje 18 mj.** | `GET /api/rn/inactive` vraća SUSPENDED+WITHDRAWN; izvor je interni (ne SDEP), **nema 18-mj. retencije**, **interni portal → treba role-gating**. | 🟡🔴 | `RnController.java:33-35` · retencija ❌ · auth = NIAS bloker |

### Grupa STR-2.x — Interno sučelje (voditelj postupka)

| TC | Što Knjiga očekuje | Backend | Status | Referenca / gap |
|----|---------------------|---------|:------:|-----------------|
| **2.1-001** | Suspenzija: **Dopis o namjeri → KP** → (bez ispravka) **Nalog za suspenziju** → status „Suspendiran" + **Internetske platforme primaju Nalog** + Obavijest u KP + **urudžbiranje** u isti predmet. Akcija dostupna **voditelju**. | `POST /api/rn/{rn}/suspend` mijenja status + upisuje log. **Bez Dopisa/Naloga, bez KP, bez obavijesti platformama, bez urudžbe, bez gatinga.** | ❌🔴 | `RnService.suspend` · dokumenti (B11) · KP (BX1) · platforme (BX3) · urudžba eGOP (BX2) · voditelj = NIAS |
| **2.1-002** | Povlačenje: **Nalog za povlačenje** → status **„Brisan"** + platforme primaju Nalog + Obavijest u KP. Trajno (bez reaktivacije bez novog zahtjeva). | `POST /api/rn/{rn}/withdraw` → `WITHDRAWN` + log. Bez Naloga/KP/platformi/gatinga. | ❌🔴 | `RnController.java:75-78` · isti blokeri kao 2.1-001 |
| **2.2-001** | Reaktivacija: toggle „Suspendiraj"↔„Reaktiviraj"; **prigovor preko TuStart vidljiv voditelju**; **samo suspendirani** (ne opozvani). | `reactivate` postoji, ali **dopušta i `WITHDRAWN→ACTIVE`** (suprotno Knjizi). Nema toka prigovora/vidljivosti voditelju, nema KP, nema gatinga. | 🟡❌🔴 | `RnStatus.canTransitionTo` (treba zabraniti WITHDRAWN→ACTIVE) · prigovor/voditelj (B12) · NIAS |

### Grupa STR-3.x — Aktivnosti (SDEP)

| TC | Što Knjiga očekuje | Backend | Status | Referenca / gap |
|----|---------------------|---------|:------:|-----------------|
| **3.1-001** | **Automatski** zakazani uvoz + ručni izvanredni + **kontrola integriteta** + **izvješće o pogrešci i obavijest** + kvartalno za male platforme. | Samo ručni `POST /api/activity/ingest`. Nema schedulera, kontrole integriteta ni izvješća; nema M2M poziva platformi. | 🟡🔴 | `AccommodationActivityController` /ingest ✅ ručni · auto/integritet/izvješće ❌ · SDEP M2M (BX4) |
| **3.2-001** | Pregled: sažetne kartice + filter po **platformi/RB/adresi/datumu** + drawer razrada + **izvoz u Excel (obavezno)**. Korisnik = voditelj/admin. | `GET /api/statistics/platform-activities` vraća `content`+`summary`; filteri `platformId/od/toDate/county/q/rn`. **Nema filtera po adresi (samo county/q), nema Excel exporta**, bez gatinga. | 🟡🔴 | `StatisticsController.java:74-87` · Excel (B9) · adresa-filter 🟡 · auth = NIAS |
| **3.3-001** | **Auto-brisanje >18 mj.** + ručno admin brisanje **uz revizijski log**. | Ručni `DELETE /api/activity/purge` (polje `purgeAfter=18mj` postoji), **bez schedulera, bez audit zapisa, bez gatinga**. | 🟡❌🔴 | `AccommodationActivityEntity` · scheduler (B5) · audit (B6) · admin = NIAS |

### Grupa STR-4.x — SDEP sučelje

| TC | Što Knjiga očekuje | Backend | Status | Referenca / gap |
|----|---------------------|---------|:------:|-----------------|
| **4.1-001** | SDEP prima podatke **M2M (API) i ručno**; kontrola integriteta; **SDEP ne pohranjuje RB**. | Samo ručni `/ingest` JSON. Nema M2M API standarda EU. (Arhitektonski: ovaj servis JEST TuRegistar i pohranjuje RB — „SDEP ne pohranjuje RB" se odnosi na SDEP komponentu, ne ovaj modul.) | 🟡🔴 | SDEP M2M = bloker (BX4) |
| **4.2-001** | Nasumične provjere: aktivan→valjan, **suspendiran→nevažeći**, nepostojeći→nije pronađen; ručno; **obavijest NT-u i iznajmljivaču**. | `GET /api/verify/{rn}` daje 2-stanja (`true`/`false`) — **ne razlikuje suspendiran od nepostojećeg**; nema obavijesti NT/iznajmljivaču. | 🟡🔴 | `VerifyController` (treba 3-stanja — vidi 1.4-001 C) · obavijesti = bloker |

---

## 2. Ključne korekcije ranijeg izvještaja (nakon čitanja Knjige)

1. **Format RB-a — DEVIJACIJA, ne „frontend je u pravu".**
   Knjiga koristi **`HR202500000001`** kao kanonski aktivni RB = `HR` + **godina (2025)** + 8-znamenkasta sekvenca = **HR + 12 znamenki**. Backend generira **`HR` + 18 znamenki** (county/group/type/random, regex `^HR\d{18}$`), a `HR202500000001` (12 znamenki) bi **pao** na toj regex provjeri. → **Backend format ne odgovara spec-u.** (Raniji izvještaj C1 pogrešno je tvrdio da je HR+18 kanonski.) Treba poslovna odluka: uskladiti generator na `HR`+godina+sekvenca, ili spec ažurirati. *(Nepostojeći primjer u Knjizi: `XX-9999-ZZ-99999999`.)*

2. **Status „Brisan" + opoziv ≠ povlačenje.**
   Knjiga koristi statuse **Aktivan / Suspendiran / Brisan**. Backend: `ACTIVE / SUSPENDED / WITHDRAWN` (+ interni `IN_PROCESSING`) — „Brisan" se preslikava na `WITHDRAWN` (terminologija). Bitnije: Knjiga razlikuje **korisnički opoziv** (STR-1.3, samoposlužni iz „Moji objekti") od **admin povlačenja** (STR-2.1, voditelj) — oba završavaju u „Brisan", ali su odvojeni tokovi. **Backend ima samo jedan `withdraw` endpoint, bez korisničkog opoziva i bez razlikovanja.**

3. **Povučena izmišljena stavka F1 (`type`→`typeId`).** Knjiga je black-box (ne navodi nazive API parametara), pa ta tvrdnja nema osnove ni u Knjizi ni u kodu — **brišem je.**

4. **Potvrđeno pozitivno:** validacija obaveznih polja i min. kapaciteta (STR-1.2-002) **postoji** server-side (`@NotBlank/@NotNull/@Min(1)`), kao i regex RB-a u `verify`. Nije sve gap.

5. **NIAS role potvrđene samom Knjigom.** Knjiga referira uloge **Voditelj postupka, Službena osoba, Administrator, nadležni ured/ispostava, Referenti**, a §6 navodi „NIAS testna okolina — testni korisnici svih uloga". → Tvoja premisa (role dolaze iz NIAS-a, do tada bloker/TODO) je ispravna i dokumentirana.

---

## 3. Blokeri (vanjske ovisnosti) — potvrđeno Knjigom

| Bloker | Knjiga to traži u | Status u kodu |
|--------|-------------------|---------------|
| **NIAS role** (voditelj/službenik/admin/referent) | 1.1-003, 1.5, 2.1, 2.2, 3.2, 3.3 | `permitAll()`, samo `ROLE_LESSOR` — nema spec → `TODO(auth)` |
| **KP (komunikacijski pretinac)** | 1.1-001/002, 1.2-001, 1.3, 2.1-001/002, 2.2 | nema klijenta (§6 Knjige: „Test KP") |
| **ePečat MINTS** | 1.1-001, 1.2-001 | nema klijenta (§6: „Testni certifikat") |
| **SDEP (M2M + nasumične provjere + obavijesti)** | 3.1, 4.1, 4.2 | samo ručni `/ingest`; nema M2M ni schedulera (§6: „Mock SDEP endpoint") |
| **Obavijest Internetskim platformama** | 2.1-001/002, 4.2 | nema kanala |
| **eGOP urudžbiranje** (neupravni predmet) | 2.1 („urudžbiranje u isti predmet") | `StubEgopClient` + 27 otvorenih pitanja |

---

## 4. Sažetak po statusu

| Status | TC-ovi |
|--------|--------|
| ✅ ispunjeno | **1.2-002** (validacija) |
| 🟡 djelomično / odstupa | 1.1-001, 1.1-002, 1.2-001, 1.3-001, 1.4-001, 1.5-001, 2.2-001, 3.1-001, 3.2-001, 3.3-001, 4.1-001, 4.2-001 |
| ❌ ne postoji (jezgra) | 1.1-003, 1.1-004, 2.1-001, 2.1-002 |
| 🔴 bloker prisutan u | 1.1-001/002/003, 1.2-001, 1.3, 1.4 (privatnost dijelom čista), 1.5, 2.1, 2.2, 3.1, 3.2, 3.3, 4.1, 4.2 |

**Čiste funkcionalnosti (bez blokera, odmah izvedive)** koje izlaze iz ove usporedbe: privatnost javnog registra + 404 za opozvan (1.4), 3-stanja `verify` (1.4-C / 4.2), zabrana `WITHDRAWN→ACTIVE` (2.2), 18-mj. scheduleri (1.3, 3.3), audit zapis ručnog brisanja (3.3), Excel export aktivnosti (3.2), generiranje Dopis/Nalog PDF (2.1). Mapiranje na zadatke v. `STR-NEDOSTAJUCE-FUNKCIONALNOSTI.md` (stavke B2/B3/B5/B6/B7/B8/B9/B11).

> **Napomena:** ovo je usporedba backenda protiv Knjige. UI koraci (ekrani, „Moji objekti", drawer, toggle akcija) su frontend odgovornost i nisu ovdje verificirani jer frontend repo nije u ovom repozitoriju.
