# STR — Backlog nedostajućih funkcionalnosti

**Izvori:** `docs/STR-BACKEND-VERIFIKACIJA-REZULTAT.md` (stvarni kod), Knjiga testiranja STR v4 (TC/UC), `docs/DOKUMENTACIJA.md §16`, `docs/eGOP-otvorena-pitanja.md`.
**Datum:** 2026-06-25

## Metodologija

Funkcionalnosti su izvedene iz razlike između (a) onoga što Knjiga testiranja / frontend pretpostavljaju i (b) onoga što backend stvarno radi. Svaka stavka je označena:

- **Strana odgovornosti:** `BE` (backend) · `FE` (frontend) · `BE+FE` (obje)
- **Bloker status:**
  - 🔴 **BLOKER** — ovisi o vanjskoj integraciji koja **nije** implementirana (samo stub ili nepostojeća). Ne može se dovršiti dok vanjski sustav / kredencijali / šifrarnici ne budu dostupni.
  - 🟢 **SLOBODNO** — nema vanjske ovisnosti, može se implementirati odmah (čisto interna logika / Spring / baza).
  - 🟡 **DJELOMIČNO** — jezgra se može odraditi sad, ali puna funkcionalnost (dostava/urudžba/potvrda) je blokirana.

> **Napomena o opsegu:** Backend je verificiran čitanjem izvora. **Frontend repo nije dostupan u ovom repozitoriju**, pa su FE stavke izvedene iz *dokumentiranih frontend pretpostavki* u verifikacijskom izvještaju, ne iz izravne inspekcije FE koda. FE stavke treba potvrditi na frontendu.

---

## Status nakon implementacije (2026-06-29)

Implementirano u backendu (prethodna sesija + tekuća), pa sljedeće stavke više nisu otvorene:

- ✅ **B7** — `verify` razlikuje aktivan / suspendiran / nepostojeći (3 stanja).
- ✅ **B8** — `reactivate` zabranjuje `WITHDRAWN→ACTIVE` (`RnStatus.canTransitionTo`).
- ✅ **B5** — `AccommodationActivityPurgeJob` (`@Scheduled`) poziva postojeći `purgeExpired()`.
- ✅ **B6** — `str_rn.admin_audit_log` + `AdminAuditService`; ožičeno u activity purge i lessor approve/reject. *Pouzdani identitet aktora i dalje čeka NIAS (BX0).*
- ✅ **B9** — Excel/CSV export `platform-activities`.
- ✅ **B11** — generiranje „Dopis o namjeri" / „Nalog" PDF (`RnDocumentService`); dostava (KP/platforme/urudžba) ostaje BX1/BX2/BX3.
- 🟡 **B4** — 18-mj. retencijski scheduler za opozvane RB-ove: **faza 1 (detekcija + audit)** gotova; stvarno brisanje/anonimizacija (faza 2) čeka potvrdu opsega + KP (BX1).
- ⏸️ **B2/B3** — privatnost internog registra (PII/404) **odgođeno do NIAS rola** (ide u BX0 paket).

**Novi otvoreni TODO-i (iz koda; ne mogu se sad dovršiti):**
- **BX0 (NIAS):** role-gate novih `permitAll` endpointa koji izlažu osobne podatke — `GET /api/rn/{rn}/documents/{tip}` (akti) i `GET /api/statistics/platform-activities/{xlsx,csv}` (imena/adrese). Inline `TODO(auth/BX0)`.
- **B4 faza 2:** stvarno brisanje/anonimizacija + KP potvrda; detekcija preskače opozvane RB-ove s `valid_to = NULL` (legacy) → prije faze 2 backfill `valid_to` iz revizijskog loga.
- **B11 dostava:** `RnDocumentService` samo generira PDF; dostava u KP + obavijest platformama + urudžba (BX1/BX2/BX3) ostaju.
- **Perf (nizak prioritet):** `PlatformActivityQuery.queryAll` nema LIMIT — razmotriti cap/streaming ako volumen naraste.

Ostalo (BX0–BX8, te FE stavke F1–F8) nepromijenjeno — vidi tablice ispod.

---

## 1. Backend — 🟢 SLOBODNO / „čiste funkcionalnosti" (implementabilno odmah, bez vanjske ovisnosti i bez rola)

> Ovo su funkcionalnosti koje **ne ovise** o NIAS rolama ni o vanjskim integracijama. Privatnost (B2/B3) rješava se kao **javna projekcija bez PII** — tj. razlikovanjem „javni vs prijavljeni" pristup, **ne** po roli — pa nije blokirana NIAS-om. Sve što traži ovlast prema roli premješteno je u §2 (BX0).

| # | Funkcionalnost | UC / TC | Strana | Referenca (gap) |
|---|----------------|---------|--------|-----------------|
| B2 | **Privatna projekcija javnog registra** — javni endpoint smije vraćati **samo aktivne**, bez OIB/email. Sada `view=ALL` izlaže SUSPENDED/WITHDRAWN, a detalj izlaže `lessorOib/lessorEmail/representativeOib/representativeEmail`. *(Razlika javni vs prijavljeni; ne traži role.)* | STR-1.4 | BE+FE | `RnController.java:39-58`, `RnDetailDto` |
| B3 | **404/redakcija za WITHDRAWN detalj** — opozvani RB mora biti „nevidljiv kao nepostojeći" javnom korisniku. | STR-1.4, STR-1.3 | BE | `RnService.detail` (nema provjere statusa) |
| B4 | **Scheduler: 18-mj. auto-brisanje osobnih podataka opozvanih RB-ova.** | STR-1.3 | BE | nema `@Scheduled`; postoji samo `DraftCleanupJob` |
| B5 | **Scheduler: auto-purge aktivnosti (18 mj).** Polje `purgeAfter` postoji, ali briše se samo ručno preko `/api/activity/purge`. | STR-3.3 | BE | `AccommodationActivityEntity.RETENTION_MONTHS=18`; nema schedulera |
| B6 | **Audit zapis admin akcija** — approve/reject lessora i purge aktivnosti upisati u audit log. *(Sam zapis je čist; pouzdani identitet aktora ovisi o NIAS rolama — vidi BX0.)* | STR-3.3, audit | BE | `AdminPendingRegistrationService`, `RnStatusTransitionService` |
| B7 | **`verify` razlikovanje stanja** — vratiti razliku „nepostojeći" vs „suspendiran/povučen" vs „aktivan" (sad samo `true`/`false`). | STR-1.5 | BE+FE | `VerifyController.java:24-31` |
| B8 | **`reactivate` ograničiti na SUSPENDED** — trenutno dopušta i `WITHDRAWN→ACTIVE`. | STR-2.2 | BE | `RnStatus.canTransitionTo` |
| B9 | **Export `platform-activities` (Excel/CSV/JSON).** Ne postoji za taj endpoint (postoji samo za STR detail). | STR-3.x | BE | `StatisticsController.java:74-87` |
| B10 | **GO-3 legalnost — priprema grananja** (sad stub koji uvijek vraća „legalizirano"). *Stvarni izvor podataka (GIS/registar) je bloker — vidi BX6; logiku grananja moguće pripremiti sad.* | STR-1.1/1.2 | BE | `Go3LegalityCheck` |
| B11 | **Generiranje dokumenta „Dopis o namjeri" i „Nalog za suspenziju/povlačenje"** (PDF). Generator `SubmissionPdfGenerator` postoji, dodati nove predloške. *Urudžba/dostava je blokirana — vidi BX1/BX2.* | STR-2.1 | BE | `pdf/SubmissionPdfGenerator` |
| B12 | **Vidljivost voditelju + zaprimanje ispravljene dokumentacije** (prigovor → reaktivacija tok). *Sam tok/podaci su čisti; tko-vidi-što po roli ovisi o BX0.* | STR-2.2 | BE+FE | `RnService.reactivate` |

---

## 2. Backend — 🔴 BLOKIRANO vanjskim ovisnostima

> **Autorizacija po rolama je bloker/TODO.** Role (npr. službenik / voditelj / admin) **dobit ćemo iz NIAS-a u budućnosti**. Do tada **nemamo potpunu specifikaciju ni dokumentaciju** kako su role strukturirane (nazivi, claimovi, mapiranje na ovlasti), pa se autorizacijski model **ne može** finalizirati. Sve stavke koje uvjetuju ovlast prema roli (suspend/reactivate/withdraw, admin approve/reject, povjerljive statistike, pouzdani aktor u auditu) ostaju **blokirane na NIAS rolama** i u kodu se vode kao `TODO(auth)`. Do tada ti endpointi rade funkcionalno, ali bez gatinga.

| # | Funkcionalnost | UC / TC | Ovisi o (bloker) | Referenca |
|---|----------------|---------|------------------|-----------|
| BX0 | **Autorizacija po rolama** — `@PreAuthorize` / role-gating na suspend/reactivate/withdraw, `/api/admin/**`, `/api/statistics/**`, `/api/activity/purge`. Trenutno sve `permitAll()`. | C.4, STR-2.1 | **NIAS role — nema specifikacije/dokumentacije** (TODO) | `SecurityConfig.java:44-48`; TODO(auth) u `AdminPendingRegistrationController` |
| BX1 | **Dostava u komunikacijski pretinac (KP) + ePečat/MINTS potpis** rješenja/dopisa. | STR-1.1/1.2/1.3/2.1/2.2 | **KP klijent + ePečat/MINTS klijent — NE postoje** | nema klijenta u `registries/` |
| BX2 | **eGOP urudžbiranje predmeta/pismena** (filing number, klasifikacijska oznaka). Trenutno `EgopClient` je **stub**; potrebni kredencijali + šifrarnici. | STR-2.1, registracija | **eGOP — stub** (`StubEgopClient`); 27 otvorenih pitanja | `EgopClient`, `docs/eGOP-otvorena-pitanja.md` |
| BX3 | **Obavijest platformama (OTA) o suspenziji/povlačenju/reaktivaciji.** | STR-2.1/2.2 | **Kanal obavijesti platformama / SDEP — NE postoji** | nema u `RnService.suspend/withdraw` |
| BX4 | **SDEP M2M uvoz aktivnosti + nasumične provjere + obavještavanje.** Sada samo ručni JSON `/ingest`. | STR-3.1, STR-4.1/4.2 | **SDEP klijent (M2M) — NE postoji** | `AccommodationActivityController` `/ingest` |
| BX5 | **GO-4 stvarna provjera suglasnosti suvlasnika (DGU registar).** Sada validira samo polja iz forme. | STR-1.1/1.2 | **DGU klijent — NE postoji** | `Go4CoOwnerConsent` |
| BX6 | **GO-1/GO-2/GO-3/GO-5 nad stvarnim registrima** (broj jedinica, legalnost, kapacitet, status domaćina). | STR-1.1/1.2 | **MPGI/GIS/RPJ/SR — svi stubovi** | `registries/stub/*` |
| BX7 | **Obavijest službeniku/referentu** (TC-1.1-003) i tok „materijalnog unosa referenata" za neregistrirani objekt (TC-1.1-004). | TC-1.1-003/004 | **eGOP/referent tok — stub** (ovisi i o BX2) | — |
| BX8 | **Otpornost integracija** — timeout/retry/circuit-breaker (Resilience4j) oko svih vanjskih klijenata. | nefunkcionalno | **vanjski klijenti — stubovi** | nema konfiguracije |

---

## 3. Frontend problemi (ugovorne / UI nepodudarnosti)

> Izvedeno iz dokumentiranih FE pretpostavki; potvrditi na frontend repou.

| # | Problem | Strana | Što treba na FE | Referenca |
|---|---------|--------|-----------------|-----------|
| F1 | **Naziv parametra** — FE šalje `type`, backend očekuje `typeId` (Long) na `GET /api/rn`. | FE | preimenovati query param u `typeId`. | `RnController.java:44` |
| F2 | **„Privremeni" status nakon dodjele** — FE prikazuje privremeni status; backend RB izdaje **odmah kao `ACTIVE`** (nema IN_PROCESSING faze za RB). | FE | maknuti „privremeni" prikaz ili ga mapirati na ACTIVE. | C3 / `RnEntity.issue` |
| F3 | **`summary` kao query param** — FE ga šalje kao parametar; backend ga vraća kao **polje u odgovoru** `PlatformActivitiesPageDto`. | FE | čitati `summary` iz odgovora, ne slati kao param. | `StatisticsController.java:74-87` |
| F4 | **Javni registar default `view`** — FE koristi `view=ALL` i time pokazuje suspendirane/povučene javno. | FE+BE | za javni prikaz default na `ACTIVE` (uz BE enforcement B2). | `RnController.java:41` |
| F5 | **Prikaz OIB/email u detalju** — FE renderira PII koji backend trenutno vraća. | FE+BE | sakriti PII za javnu ulogu (uz BE projekciju B2). | `RnDetailDto` |
| F6 | **Gating akcija po roli** — FE nema gating za suspend/reactivate/admin akcije. 🔴 **Blokirano** dok ne stignu NIAS role (vidi BX0). | FE | sakriti/uvjetovati akcije prema roli — **čeka NIAS spec**. | C4 / BX0 |
| F7 | **`verify` poruka** — FE očekuje validaciju samo formata `^HR\d{18}$`; backend vraća `valid` tek za ACTIVE i ne razlikuje nepostojeći od suspendiranog. | FE+BE | uskladiti poruku korisniku (uz BE B7 za razlikovanje). | `VerifyController.java` |
| F8 | **„Verificiran/neverificiran" oznaka** — FE traži taj status; backend nema jedinstveni flag (ima `accommodation.host` + `lessor.applicationStatus`). | FE+BE | dogovoriti kanonski izvor (otvoreno pitanje) pa prikazati. | otvoreno pitanje #3 |

---

## 4. Vanjske ovisnosti (blokeri) — sažetak

| Ovisnost | Status u kodu | Blokira (stavke) | Napomena |
|----------|---------------|------------------|----------|
| **NIAS role (autorizacija)** | `permitAll()`, jedina rola `ROLE_LESSOR` | BX0, B6 (aktor), F6 | **Role dolaze iz NIAS-a u budućnosti** — nema potpune specifikacije/dokumentacije; do tada `TODO(auth)`, bez gatinga |
| **KP (komunikacijski pretinac)** | ne postoji | BX1 | nema klijenta ni specifikacije u repou |
| **ePečat / MINTS** | ne postoji | BX1 | e-potpis rješenja |
| **eGOP** | stub (`StubEgopClient`) | BX2, BX7 | 27 otvorenih pitanja — `docs/eGOP-otvorena-pitanja.md` (auth, šifrarnici, tko otvara predmet) |
| **SDEP** | ručni ingest, nema klijenta | BX3, BX4 | M2M + nasumične provjere |
| **DGU** | ne postoji | BX5 | GO-4 stvarna suglasnost suvlasnika |
| **MPGI / GIS / RPJ / SR** | stubovi (`registries/stub/*`) | BX6, B10 | GO-1/2/3/5 nad stvarnim podacima |
| **NIAS / eIDAS** | SAML2 postoji uvjetno; potpis flow izvan opsega | (registracija EU) | poznato ograničenje — ne ispravljati u ovoj fazi |

---

## 5. Predloženi redoslijed

1. **Odmah — čiste funkcionalnosti (bez NIAS rola, bez vanjskih integracija):** B2 → B3 → B6 (zapis) → B4, B5, B7, B8, B9, B11, B12 + FE: F1, F2, F3. *(Privatnost javnog registra rješava se kao projekcija bez PII, ne traži role.)*
2. **Priprema integracija (logika sad, dostava/podaci kasnije):** B10, BX2 (paralelno s prikupljanjem eGOP odgovora).
3. **Blokirano na NIAS rolama (TODO — čeka specifikaciju):** BX0, F6, pouzdani aktor u B6.
4. **Blokirano dok vanjski sustavi ne budu dostupni:** BX1, BX3, BX4, BX5, BX6, BX7, BX8.

> Blok 1 ne ovisi ni o čemu vanjskom ni o rolama → smislen prvi PR-set. Blok 3 čeka NIAS specifikaciju rola; blok 4 čeka kredencijale/šifrarnike/klijente vanjskih registara.
