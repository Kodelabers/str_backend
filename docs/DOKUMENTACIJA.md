# STR Backend — Tehnička dokumentacija

**Projekt:** Modul za evidenciju kratkoročnog najma smještaja (Short-Term Rental — STR)
**Status:** Implementirano (mock faza, dev-ready)
**Datum:** travanj 2026.

---

## 1. Tehnološki stog

STR backend je dio šireg ekosustava **eTurizam** i u potpunosti slijedi njegov odobreni tehnološki stack.

### 1.1 Back-end stack (eTurizam standard)

| Kategorija | Tehnologija | Svrha |
| :--- | :--- | :--- |
| Jezik | **Java 21** | Records, sealed interfaces, pattern matching, virtual threads |
| Framework | **Spring Boot 3.3** | Razvojni okvir za back-end aplikacije |
| Arhitektura API-ja | **REST** | Dizajn krajnjih točaka |
| Web sloj | Spring MVC (`spring-boot-starter-web`), Bean Validation (`jakarta.validation`) | Kontroleri i validacija |
| Persistence | Spring Data JPA + Hibernate, `ddl-auto=none` | ORM nad PostgreSQL-om |
| Baza podataka | **PostgreSQL 16** (prod/test), H2 (unit testovi) | Glavni i in-memory store |
| Migracije | **Liquibase** (XML changelogs, kontekst `local`/`test`/`prod`) | Verzioniranje sheme |
| Build / DI | **Gradle 9.4** (toolchain Java 21) | Build, linkanje, dependency injection |
| Code generation | **Lombok** | Automatsko generiranje boilerplate koda (getteri/setteri, konstruktori) |
| Mapiranje | **MapStruct** | Mapiranje Entity ↔ DTO klasa |
| Procesi | **Flowable** (BPMN) | Modeliranje i izvršavanje poslovnih procesa |
| Izvještaji | **JasperReports** | Generiranje izvještaja i PDF datoteka |
| Testiranje | JUnit 5, AssertJ, Mockito, `spring-boot-starter-test` | Unit + integration testovi |
| Logiranje | SLF4J + Logback | Strukturirano logiranje |

### 1.2 Razvojni alati i procesi

| Alat | Namjena |
| :--- | :--- |
| **Git** | Kontrola verzija |
| **GitLab** | Server repozitorija koda |
| **IntelliJ IDEA** / **Eclipse** | IDE za back-end razvoj |
| **DBeaver** | Upravljanje bazom podataka |
| **SQL** | Naredbe nad bazom |

### 1.3 Front-end stack (eTurizam, izvan opsega ovog repozitorija)

JavaScript, **React.js**, **Ant Design** (UI biblioteka), **Visual Studio Code** (IDE), **Yarn** (build / dependency injection).

### 1.4 Trenutno stanje ovog modula (mock faza)

U trenutnoj fazi mock implementacije korišten je užmi podskup gornjeg stacka — konkretno **Java 21, Spring Boot 3.3, PostgreSQL, H2, Liquibase, Gradle, JPA, JUnit 5**. Ostale komponente standardnog stacka (**Lombok, MapStruct, Flowable, JasperReports**) bit će uvedene u sljedećim iteracijama kad konkretni use-case-ovi to zatraže (BPMN tijek odobravanja zahtjeva, generiranje rješenja u PDF-u, mapiranje složenijih DTO grafova).

DI je u cijelom modulu implementiran kroz **konstruktor injection** (bez field injection), a DTO-ovi su trenutno Java `record` tipovi.

---

## 2. Arhitektura i sheme baze

Sustav koristi dvije PostgreSQL sheme s eksplicitno odvojenim ovlastima:

| Shema | Ovlasti | Vlasnik |
| :--- | :--- | :--- |
| `core` | **Read-only** (matični podaci o objektima i osobama) | Vanjski servis |
| `str` | Read-write (registracija, RB, aktivnosti, audit) | Ovaj backend |

**Stroga pravila:**
- Sve `core.*` JPA entitete označavamo `@Immutable`, repozitorije s `@Transactional(readOnly = true)`.
- Niti jedan write-path prema `core` ne smije postojati u kodu.
- Svi `@Table` entiteti **eksplicitno** deklariraju `schema =`.
- Hibernate ne generira ni ne validira shemu (`spring.jpa.hibernate.ddl-auto=none`); sve DDL promjene idu kroz Liquibase changeset.

---

## 3. Profili i konfiguracija

| Profil | Baza | Liquibase kontekst | Napomena |
| :--- | :--- | :--- | :--- |
| `local` | `localhost:5432/str_db` | `local` | `LocalDatabaseConfig` automatski kreira bazu pri startu; učitava i `002-core-objekt.xml` |
| `test` | `TEST_DB_URL/USERNAME/PASSWORD` | bez konteksta | preskače kreiranje `core.objekt` (vlasnik je core servis) |
| `prod` | `PROD_DB_URL/USERNAME/PASSWORD` | bez konteksta | identično `test` profilu |

JUnit testovi (`@ActiveProfiles("test")`) override-aju konfiguraciju kroz `src/test/resources/application-test.properties` i koriste H2 in-memory bazu.

---

## 4. Domena registracijskog broja (RB)

### 4.1 Format
- 10 znakova: prefiks `HR` + 8 numeričkih znamenki.
- Validacija: `^HR[0-9A-Fa-f]{18}$` (klasa `RegistrationNumber`).
- Primjer: `HR04920183`.

### 4.2 Generiranje
- 8 znamenki generira se preko `SecureRandom.nextInt(100_000_000)` formatirano kao `HR%08d`.
- Bez kontrolne znamenke — kolizije se rješavaju retry petljom (do 5 pokušaja u `RbService.issue`) uz unique constraint na `str.rb.rb`.
- Generiranje je atomski dio iste transakcije kao i izdavanje (insert), pa se istovremene kolizije svode na `DataIntegrityViolationException` (vrlo rijetko, prihvatljivo kao 500).

### 4.3 Životni ciklus
RB se inserta u bazu **isključivo** nakon što sve GO provjere (GO-1 do GO-5) prođu uspješno. Prije toga RB ne postoji — nema tzv. "draft" RB-a.

---

## 5. Model podataka (str shema)

| Tablica | Svrha |
| :--- | :--- |
| `str.iznajmljivac` | Imutabilan snapshot iznajmljivača u trenutku registracije; jedini mutabilan atribut je `is_domacin` (postavlja GO-1) |
| `str.zahtjev` | Audit zapis o zahtjevu (FK reference iz `rb`/`sso`); više nema multi-step workflow servisa nad njim |
| `str.sso` | Smještajni objekt (kapaciteti, lokacija, suglasnosti suvlasnika, broj soba/kreveta, oznaka kategorizacije) |
| `str.rb` | Registracijski broj sa statusom i datumima izdavanja/povlačenja |
| `str.audit_log` | Centralni audit svih status tranzicija i GO ishoda |
| `str.smjestajni_sadrzaj` | Sadržaji smještaja (parking, wifi, klima, …) |
| `str.sso_aktivnost` | SDEP mjesečni unosi noćenja po platformi (retencija 18 mj) |
| `str.prilog_zahtjeva` | Prilozi/dokumenti vezani za zahtjev |
| `str.zastupnik_pravne_osobe` | Zastupnici iznajmljivača (pravnih osoba); izvor `SUDSKI_REGISTAR` ili `RUCNI_UNOS` |
| `str.vrsta_sso`, `str.vrsta_zahtjeva`, `str.nadlezno_tijelo`, `str.internetska_platforma` | Šifrarnici |

---

## 6. Validacijski engine (Grupne Obrade)

### 6.1 Provjere

| ID | Naziv | Order | Pravilo |
| :--- | :--- | :---: | :--- |
| **GO-2** | Tip građevine | 1 | Ako je `zgrada=true` i `stanovi=true` → `kontekst.markiraj()` (aktivira GO-4) |
| **GO-1** | Status domaćina | 2 | `is_domacin = (zupanija matches) AND NOT zgrada`; postavlja `iznajmljivac.is_domacin` |
| **GO-3** | Legalnost | 3 | Ako `legalizirano=false` → `Odbijena` |
| **GO-4** | Suglasnost suvlasnika | 4 | Pokreće se **samo** ako je GO-2 markirao kontekst; provjerava DGU registar |
| **GO-5** | Provjera kapaciteta | 5 | `sso.kreveti ≤ core.maxKreveta` i `sso.gostiju ≤ core.maxGostiju`; kad `core` nedostupan, prolazi |

### 6.2 Orkestracija (`ParallelValidationOrchestrator`)

Provjere se izvode u **valovima** (waves) baziranim na `dependsOn()` skupu, koristeći `CompletableFuture.supplyAsync(...)` na fiksnom thread-poolu:

1. `planWaves()` napravi topološko sortiranje provjera. Provjere bez zavisnosti idu u prvi val; GO-4 ima `dependsOn = {"GO-2"}` pa ide u sljedeći val tek nakon GO-2.
2. Unutar jednog vala provjere se izvršavaju paralelno; rezultati se obrađuju redoslijedom `order()` (zbog stabilnog audit loga).
3. Prvi `Odbijena` u valu kratko-spaja preostale rezultate **tog vala**.
4. Kritičan invariant: GO-4 pokreće DGU **samo** kad GO-2 postavi `kontekst.markiraj()`. Bez markera — GO-4 vraća `Prosla` bez vanjskog poziva.

`ExternalRegistryException` (timeout, MPGI/DGU nedostupan) se **ne** tretira kao validacijsko odbijanje — propagira se nepromijenjen i `GlobalExceptionHandler` ga mapira u HTTP 503.

---

## 7. Status tranzicije

### 7.1 Submission status (`SubmissionStatus`)
`INITIATED → IN_PROCESSING (SUBMIT) | IN_VERIFICATION (FOREIGN_UPLOAD)`; `IN_VERIFICATION → IN_PROCESSING (REFERENT_APPROVE)`; `IN_PROCESSING → ACCEPTED | REJECTED` (terminal). Sve tranzicije idu kroz `SubmissionStatusTransitionService.transition(...)` koja istovremeno upisuje `submission_log` red.

### 7.2 RB status (`RbStatusTransitionService`)
Sve promjene `rb.status` idu **isključivo** kroz `RbStatusTransitionService.transition(...)`. Servis validira tranziciju protiv `RbStatus.canTransitionTo(...)` enum logike i istovremeno upisuje audit row — operacije su nedjeljive.

```
U_OBRADI ──ISSUE────────► AKTIVAN
AKTIVAN  ──CONSENT_EXPIRY/INSPECTION──► SUSPENDIRAN
SUSPENDIRAN ──REACTIVATE──► AKTIVAN
AKTIVAN/SUSPENDIRAN ──WITHDRAWAL──► POVUCEN
POVUCEN  ──REACTIVATE──► AKTIVAN  (rijedak edge-case)
```

`AKTIVAN` je jedini status s javnom vidljivošću (SDEP / verify endpoint).

---

## 8. Tijek registracije (`RegistracijaService`)

Jedinstveni endpoint `POST /api/registracija` pokriva **sva tri scenarija**, raspoznatih kroz `Scenarij` enum:

| Scenarij | Opis |
| :--- | :--- |
| `S1_POSTOJECI_OBJEKT` | Objekt već postoji u core registru — `idCoreObjekt` obavezan; SSO se učitava iz baze |
| `S2_NOVI_OBJEKT_VANJSKI` | Novi objekt iz vanjskog (javnog) portala — SSO se kreira u istoj transakciji |
| `S3_NOVI_OBJEKT_INTERNI` | Novi objekt iz internog (referent) portala — identično S2, drugačiji izvor okidanja |

**Algoritam (po SSO-u):**
1. Učitaj/kreiraj `SsoEntity`.
2. Sastavi `ValidacijskiKontekst(sso, iznajmljivac, coreObjekt?)`.
3. Pokreni `ParallelValidationOrchestrator.execute(...)`.
4. Ako `Odbijena` — baci `ValidationRejectedException` (HTTP 422) s `step` i `detail`.
5. Inače — pozovi `RbService.issue(...)`, audit, dodaj u response.

Cijeli poziv je u jednoj `@Transactional(noRollbackFor = ValidationRejectedException.class)` transakciji da bi audit log preživio rollback poslovnog odbijanja.

---

## 9. SDEP integracija (`SsoAktivnostService`)

| Endpoint | Svrha | Spec |
| :--- | :--- | :--- |
| `POST /api/aktivnosti/ingest` | Mjesečni ingest s internet platformi | STR-3.1 |
| `GET /api/aktivnosti` | Pretraživanje za nadležno tijelo (filter po platformi/RB/datumu) | STR-3.2 |
| `DELETE /api/aktivnosti/purge` | Ručno pokretanje retencijskog purga (18 mj) | STR-3.3 |

Retencija: zapisi stariji od 18 mjeseci brišu se automatski (scheduler) ili na ručni poziv `purge` endpointa.

---

## 10. Vanjske integracije

| Registar | Korisnik | Pristup | Klijent |
| :--- | :--- | :--- | :--- |
| `core` DB | Sve GO | JPA read-only | `CoreObjektRepository` |
| MPGI | GO-2, GO-3 | HTTP REST | `MpgiClient` (stub: `StubMpgiClient`) |
| DGU | GO-4 | HTTP REST | (planirano) |
| eGOP / Sudski registar | Lookup zastupnika pravne osobe | HTTP REST | `EgopClient` (stub: `StubEgopClient`) |

**Failure semantika:** timeout/nedostupnost vanjskog registra **nije** validacijsko odbijanje. `ExternalRegistryException` se mapira u HTTP 503; korisnik dobiva poruku da je vanjski sustav nedostupan, ne da je objekt odbijen.

---

## 11. Iznajmljivač — snapshot semantika

`LessorEntity` je **uglavnom imutabilan** nakon kreiranja (`updatable=false` na identitetskim kolonama: ime, adresa, email, username). Mutabilna polja su ograničena na kontakt podatke, podatke o pravnoj osobi i `applicationStatus`. Re-validacija ne stvara novi snapshot — lessor je perzistentan, GO-1 ažurira host status flag.

Posljedica: izmjena podataka iznajmljivača u core sustavu ne mijenja postojeće STR snapshote — registracija ostaje vezana uz stanje u trenutku izdavanja.

---

## 12. Audit log

Sve promjene statusa (RB, zahtjev) i sve GO provjere upisuju red u `str.audit_log`:

| Polje | Sadržaj |
| :--- | :--- |
| `entity_type` | `RB`, `SSO`, `ZAHTJEV` |
| `entity_id` | UUID/string entiteta |
| `event_type` | `TRANSITION`, `VALIDATION` |
| `from_status` / `to_status` | tranzicije |
| `step` / `outcome` | GO koraci (`GO-1..GO-5`, `PROSLA`/`ODBIJENA`) |
| `trigger_name` | naziv okidača |
| `detail` | slobodan tekst (do 1024 znaka) |
| `occurred_at` | timestamp (UTC) |

Statusna promjena bez audit reda nije dopuštena — `*StatusTransitionService` upisuje status i audit u istoj `@Transactional` jedinici.

---

## 13. REST API pregled

Sve verzije endpointa su uklonjene (mock faza, bez versioniranja). Kompletna OpenAPI 3.0 specifikacija nalazi se u `swagger.txt` (JSON) i `str-api.yaml` (YAML).

| Endpoint | Metoda | Svrha |
| :--- | :--- | :--- |
| `/api/verify/{rb}` | GET | Javna provjera RB-a (SDEP, OTA platforme) |
| `/api/rb/nevazeci` | GET | STR-1.5 — lista nevažećih RB-ova (SUSPENDIRAN + POVUCEN) |
| `/api/rb/{rb}` | GET | Dohvat RB-a |
| `/api/rb/{rb}/suspend` | POST | Suspenzija (trigger: `CONSENT_EXPIRY`/`INSPECTION`) |
| `/api/rb/{rb}/reactivate` | POST | Reaktivacija |
| `/api/rb/{rb}/withdraw` | POST | Povlačenje (terminalno) |
| `/api/registracija` | POST | S1/S2/S3 registracija + dodjela RB-a |
| `/api/aktivnosti/ingest` | POST | SDEP ingest |
| `/api/aktivnosti` | GET | Pretraživanje aktivnosti |
| `/api/aktivnosti/purge` | DELETE | Ručni retencijski purge |
| `/api/registerLessor` | POST | Self-service registracija non-EU iznajmljivača — detalji u `docs/LESSOR-REGISTRATION-API.md` |
| `/api/auth/login` | POST | Login non-EU iznajmljivača (username + password) |
| `/api/auth/me` | GET | Podaci o prijavljenom korisniku |
| `/api/auth/logout` | POST | Odjava |
| `/api/address/countries` | GET | Lista država (za `zemljaPrebivalistaId` polje) |

---

## 14. Build i pokretanje

```bash
gradle bootRun                         # pokreće app (default profil: local)
gradle test                            # cijeli test suite
gradle test --tests "*.Go5*"           # pojedinačna klasa
gradle compileJava                     # samo kompilacija
gradle bootJar                         # fat JAR
```

Profil se odabire varijablom okoline `SPRING_PROFILES_ACTIVE`. Lokalna baza se kreira automatski preko `LocalDatabaseConfig` ako ne postoji.

---

## 15. Testiranje

- **Unit testovi GO koraka** pokrivaju `Go1..Go5` — testira se ponašanje na `null` core objektu, na granicama kapaciteta, na različitim kombinacijama `zgrada/stanovi/legalizirano`.
- **Integration testovi** koriste H2 (`@ActiveProfiles("test")`) i Liquibase za pripremu sheme.
- **Coverage cilj:** 80%+ linija, sve GO grane (PROSLA/ODBIJENA) pokrivene.

---

## 16. Što **nije** implementirano

- Spring Security / autentikacija — endpointi su trenutno otvoreni (mock faza).
- Resilience4j retry/circuit breaker oko MPGI/DGU/eGOP — koriste se stubovi.
- Stvarni DGU klijent (GO-4 koristi flag iz konteksta, bez vanjskog poziva).
- Scheduleri za automatsku suspenziju i SDEP retencijski purge — postoje ručni endpointi.
- NIAS/eIDAS digitalni potpis flow — uklonjen iz scope-a u ovoj fazi.

Ovi dijelovi su namjerno odgođeni za sljedeću iteraciju kad bude dostupna stvarna integracija prema vanjskim sustavima.
