# Plan: T8 (žurna verifikacija) i T9 (lista prioriteta za provjeru)

Stanje: **implementirano** (grana `feature/zurna-verifikacija`, 5. 11. 2026.). Upis je iza zastavice `str.eturizam.verifikacija.enabled` (zadano `false`), obavijesti iza vlastite (zadano `false`) dok eTurizam ne potvrdi vrijednosti iz § 6. Pravila označena s *privremeno* (§ 2a) treba potvrditi sa Simonom.

**Dopuna 9. 11. 2026.** (grana `feature/zurna-obavijest-novi-objekt`): u opsegu je i **novi objekt uz predano rješenje o kategorizaciji**, ali samo s obavijestima — v. § 7. Retci § 2 „Kada STR upisuje zahtjev" i „Kada točno" i dalje vrijede za **zahtjev**.

## 1. Kontekst

Primjedbe MINTS-a s UAT-a (22. 10. 2026.), redovi 8 i 9:
- U internom eTurizmu nije vidljiv zahtjev za žurnu verifikaciju nakon zahtjeva za dodjelu RB-a.
- Objekti se ne stavljaju na listu prioriteta za provjeru.

Dogovor s eTurizmom (Simon, 5. 11. 2026.):
- STR **upisuje** zahtjev za verifikaciju u `str.verification_request`.
- STR **upisuje** obavijest u `str.korisnicka_obavijest`.
- eTurizam te zahtjeve i obavijesti prikazuje i iz njih slaže listu prioriteta. Sustav notifikacija je njihov.
- Darko Bosnar daje STR-u prava čitanja i pisanja na obje tablice.

## 2. Odluke (5. 11. 2026.)

| Pitanje | Odluka |
|---|---|
| Kada STR upisuje zahtjev | **Samo kad se RB izda za neverificiran objekt iz eTurizma.** Neverificiran znači ono što provjera zahtjeva (`FacilityClaimVerifier` → `findOwnership`) vrati za jedinicu iz handoffa: `verified = (created_by <> 'optimit')`, dakle migrirana jedinica; provjera već traži da je jedinica aktivna i aktualna po pravilima popisa. Stanje verifikacije predmeta (`BCVS_ZAVRSENA`) se ne gleda zasebno. Novi objekti (s rješenjem ili bez njega) i „podaci nisu točni" nisu u opsegu. |
| Koga se obavještava | **Službenike nadležne županije** (odnosno Grada Zagreba) prema mjestu objekta. |
| Tko prikazuje obavijesti | eTurizam. STR samo upisuje retke. |
| Koga se obavještava (precizno, nakon W3–W5) | **Svi aktivni interni korisnici nadležne jedinice dokumenta**: `application_user.organizational_unit_id = document.jurisdiction_organizational_unit_id`, `active = TRUE`, `internal = TRUE`. Bez filtra po roli. |
| T9 | Isto kao T8. Listu slaže eTurizam iz `verification_request`; tablica nema prioritet, pa STR za T9 ne upisuje ništa dodatno. |
| Kada točno (precizno) | Samo RB izdan za **eTurizam objekt** (`facilityId`) koji je **u trenutku izdavanja** neverificiran: `FacilityOwnershipRow.getVerified() == FALSE` (`str.facility.created_by = 'optimit'` provjerene jedinice). Novi objekt (bez `facilityId`), objekt s učitanim rješenjem i „podaci nisu točni" nisu u opsegu. `verified = NULL` (nepoznat autor) ne ulazi. |
| `created_by` / `last_modified_by` | Oznaka sustava iz konfiguracije, zadano `STR` (`str.eturizam.verifikacija.korisnik`). |
| Vrijednosti obavijesti | Nepoznate → konfiguracija: `vrsta` (zadano `ZURNA_VERIFIKACIJA`), `objekt_tip` (zadano `VERIFICATION_REQUEST`), tekst (predložak). `objekt_id` = `verification_request.id`; `podaci` = `{"rn","documentId","facilityId","verificationRequestId"}`. Obavijesti su iza vlastite zastavice, zadano ugašene. |

### 2a. Privremena pravila (potvrditi sa Simonom)

| Pitanje | Privremeno pravilo |
|---|---|
| Zahtjev za dokument već postoji (bilo koji `active`) | Ne upisuje se novi, postojeći se **ne mijenja**, bilježi se njegov id (`ZAHTJEV_POSTOJAO`), obavijesti se **ne šalju** (zahtjev nije upisao STR). |
| Zatvaranje zahtjeva | STR zahtjeve nikad ne deaktivira ni ne mijenja — ni pri opozivu ni pri povlačenju RB-a. |
| RB-ovi izdani prije puštanja | Bez backfilla. |
| Dokument bez nadležne jedinice / jedinica bez aktivnih internih korisnika | Zahtjev se upisuje, obavijesti ne; WARN s RB-om i id-em dokumenta. |
| Tekst obavijesti | „Smještajnom objektu „{naziv}“ ({mjesto}, ID {facilityId}) dodijeljen je registracijski broj {rn}. Objekt nije verificiran – potrebna je žurna verifikacija.“ |
| Zastarjeli zaostatak | Retci nastali dok je zastavica ugašena obrade se kad se uključi, osim kad je RB u međuvremenu opozvan ili povučen, ili objekt verificiran (`PRESKOCENO`). Suspendiran RB se obrađuje. |
| Broj primatelja | Najviše 200 po zahtjevu (`max-primatelja`), iznad toga WARN i prvih 200 po id-u. |

## 3. Činjenice iz koda (stanje `develop`)

- **Pristup bazi.** Jedan datasource, ista baza `eturizam`. Sheme `str` (eTurizam), `str_rn` (STR) i `rpj_dgu`.
  - Na preprod i cdupreprod uloga je `str_owner`, preko JDBC `options=-c role=str_owner`.
  - Na CDU se uloga ne postavlja.
- **Jedini dosadašnji upis u `str`.** `StrFacilityRepository.writeBackRegistrationNumber`:
  - uski native UPDATE s `REQUIRES_NEW`, zove se iz `RnIssuedListener` (AFTER_COMMIT);
  - greška se proguta, a retryja nema.
- **Pravila projekta.** `CLAUDE.md` (r. 60) i skill `str-schema-strategy` (Hard Rule 1) kažu da je `str` samo za čitanje.
  - Iznimke treba dopisati: upis RB-a + nove dvije tablice.
- **Neverificiran objekt.**
  - U trenutku izdavanja RB-a: `FacilityOwnershipRow.getVerified() == false` (SQL `StrFacilityRepository` ~494).
  - Danas na razini objekta: `findObjectVerification` (~584).
  - Pri izdavanju `RegistrationService.verifyAndCompleteFacility` već ima `FacilityOwnershipRow`.
- **Identifikatori za neverificiran objekt.**
  - U STR-u: `accommodation.facility_id` (= `str.facility.id`), `submission_id`, `rn`.
  - Iz upita `findOwnership`: `documentId`; u SQL-u su dostupni i `business_case.id`, `subject_version_id`, `subject.id`, `system_uuid`, `address_id`.
- **Županija objekta.**
  - eTurizam: `coalesce(str.county.name, address.county)` preko `f.address_id`.
  - STR: `accommodation.county`, normaliziran na `rpj_dgu`.
- **Službenici županije.**
  - Obratnog upita (županija → službenici) nema.
  - Lanac za taj upit postoji u `InternalUserRepository.ORGANIZATION_BY_OIB`: `application_user` → `internal_organization` (`IOT_ZUPANIJA`) → `organizational_unit` → `settlement_jurisdiction` → … → `county`.
- **Obrazac za pouzdan upis s ponavljanjem:** `email/MailRetryStore`, `MailRetryEntity` i `MailRetryJob`.
  - Tablica u `str_rn`, `REQUIRES_NEW`, backoff po `EgopRetryPolicy`.
- **Testovi.**
  - H2 bez Testcontainersa; test sam stvara `str.*` tablice.
  - Predložak za test upisa u `str`: `StrFacilityRegistrationNumberQueryTest`, uz ručno čišćenje i rezervirani raspon id-eva.

## 3a. Struktura tablica (CDU test, `eturizam`, korisnik `shorttermrental`, 5. 11. 2026.)

Rezultati su u `docs/sql/results/zurna_verifikacija/`.

**`str.verification_request`** (10 redaka):

| Stupac | Tip | Null | Napomena |
|---|---|---|---|
| `id` | bigint identity | NO | PK |
| `created_by` | varchar(255) | NO | bez defaulta; u postojećim recima OIB (testni iznajmljivači) |
| `created_date` | timestamp (bez zone) | NO | bez defaulta |
| `last_modified_by` | varchar(255) | NO | bez defaulta |
| `last_modified_date` | timestamp (bez zone) | NO | bez defaulta |
| `active` | boolean | NO | default `true` |
| `source_document_id` | bigint | NO | FK `str.document(id)`, **UNIQUE** |

- Zahtjev se veže uz **dokument**, ne uz objekt, a po dokumentu može postojati samo jedan.
- Nema statusa (osim `active`), prioriteta, županije ni veze na RB.
- Nema okidača, funkcija ni pogleda: eTurizam ga čita u aplikaciji.
- Postojeće retke stvara eTurizam/tuStart.

**`str.korisnicka_obavijest`** (0 redaka):

| Stupac | Tip | Null | Napomena |
|---|---|---|---|
| `id` | bigint identity | NO | PK |
| `application_user_id` | bigint | NO | FK `str.application_user(id)`, dakle **po korisniku** |
| `vrsta` | varchar(64) | NO | šifra vrste obavijesti, **nepoznate vrijednosti** |
| `tekst` | varchar(1000) | NO | |
| `objekt_tip` | varchar(64) | YES | indeks (`objekt_tip`, `objekt_id`) |
| `objekt_id` | bigint | YES | |
| `podaci` | jsonb | YES | |
| `stvoreno` | timestamp | NO | bez defaulta |
| `procitano` | timestamp | YES | NULL = nepročitano (parcijalni indeks) |

**Prava (V11):**
- `shorttermrental` (CDU test) ima SELECT, INSERT i UPDATE na obje tablice.
- **`str_owner` ima samo SELECT.** Preprod i cdupreprod rade pod ulogom `str_owner`, pa ondje upis ne prolazi. Treba (najmanje potrebno) `GRANT SELECT, INSERT ON str.verification_request` i `GRANT INSERT ON str.korisnicka_obavijest` za `str_owner` (Darko); v. § 4.5. Prava produkcijskog korisnika nisu provjerena.

**Posljedice za dizajn:**
- INSERT zahtjeva: prvo `SELECT` po `source_document_id`, pa `INSERT` i čitanje `id`-a po dokumentu (prenosivo na H2; v. § 4.1 — `ON CONFLICT … RETURNING` nije korišten).
- `source_document_id` = `str.facility.document_id` objekta, isto što i `documentId` iz `findOwnership`. Provjerava se skriptom `t8-verifikacija-dijagnostika-2.sql` (W1, W6, W7).
- Audit stupce i `stvoreno` puni STR, u lokalnom vremenu Europe/Zagreb. Zonu baze pokazuje W0.
- T9 nema što upisati: lista prioriteta je eTurizmova, iz zahtjeva i njihovog `created_date`.

## 4. Izvedba

### 4.1 Tok

1. **Izdavanje RB-a** (`RegistrationService.commitRegistration`, ista transakcija):
   `VerifikacijaOutbox.zabiljezi(rn, submissionId, facilityId, facility)` (`MANDATORY`) upisuje redak u
   `str_rn.eturizam_verifikacija` (`PENDING`) samo za neverificiran eTurizam objekt, s nazivom i mjestom
   objekta **iz eTurizma** (`findOwnership`; naziv kroz `FacilityClaimVerifier.objectName`, koji odbacuje
   popunjivače i ime vlasnika). `documentId` `null` ili `≤ 0` → WARN `eturizam_verifikacija_bez_dokumenta`,
   retka nema (u praksi ne stiže: `findOwnership` dokument spaja obaveznim joinom). Redak nastaje i dok
   je zastavica ugašena. `verifyAndCompleteFacility` vraća provjereni `FacilityOwnershipRow`.
2. **Nakon commita** (`EturizamVerifikacijaListener`, `AFTER_COMMIT` na `RnIssuedEvent`, zaseban
   slušatelj, ništa ne baca, `@Order(LOWEST_PRECEDENCE)` — `RnIssuedListener` (write-back, eGOP) ide prvi)
   → `EturizamVerifikacijaService.obradi(Pokusaj.inline(rn))`.
3. **Jedan pokušaj = jedna `REQUIRES_NEW` transakcija** (`EturizamVerifikacijaWriter.upisi`):
   outbox redak `SELECT … FOR UPDATE` → je li pokušaj još na redu (v. 6.) → **provjera pri obradi**:
   RB opozvan/povučen (`str_rn.registration_number.status` `REVOKED`/`WITHDRAWN`), RB-a nema, ili je
   objekt danas verificiran (`RnFacilityVerification`, isto pravilo kao stupac „Verificiran") →
   `PRESKOCENO`, ništa se ne upisuje → postoji li zahtjev za dokument → INSERT zahtjeva → (samo ako ga
   je upisao ovaj pokušaj i obavijesti su uključene) po jedan INSERT obavijesti za svakog primatelja
   (najviše `max-primatelja`, 200) → outbox `ZAHTJEV_UPISAN` / `ZAHTJEV_POSTOJAO`. Ista baza, pa je sve
   atomarno: bilo koja greška poništava i zahtjev i obavijesti.
4. **Neuspjeh** se bilježi u zasebnoj `REQUIRES_NEW` s istim ograničenjem trajanja
   (`VerifikacijaOutbox.recordFailure`): `attempts + 1`, `next_attempt_at` po `EgopRetryPolicy`
   (5 min, 10, 20 … do 2 h), `last_error` = najdublji uzrok bez vrijednosti retka (odrezano od
   `Detail:` / `Failing row contains` / H2 `SQL statement`). Nakon `max-attempts` (10) → `ABANDONED` +
   ERROR `eturizam_verifikacija_exhausted`. **Bez prava** (`42501`): pokušaj se ne broji, redak ostaje
   `PENDING`, sljedeći pokušaj za `backoff-max`, ERROR `eturizam_verifikacija_bez_prava` samo prvi put.
   Kad i rollback padne (veza zatvorena nakon isteka), bilježi se izvorna greška.
5. **Retry** (`EturizamVerifikacijaRetryJob`, samo uz `enabled=true`): dospjeli `PENDING` retci kroz
   isti put kao inline. Novi redak dospijeva tek nakon `backoff-base`, da job ne preteče inline pokušaj.
   **Prvi infrastrukturni neuspjeh** (istek, zaključavanje, veza, transakcijski sustav, bez prava)
   prekida seriju (WARN `eturizam_verifikacija_retry_aborted`) — dijeljena nit `@Scheduled` poslova se
   tako ne zauzima 50 × 10 s; neuspjeh jednog retka zbog podataka seriju ne prekida.
6. **Tko smije pokušati.** Pokušaj nosi broj pokušaja koji je pozivatelj vidio (inline 0, job iz
   pročitanog retka). Nakon zaključavanja redak mora imati upravo toliko pokušaja, a za job i dospjeli
   rok; inline smije prije roka, ali samo kao prvi. `recordFailure` broji neuspjeh samo uz isti broj
   pokušaja. Tako dvije instance ili zakašnjeli inline pokušaj isti neuspjeh ne broje dvaput.
7. **Vremensko ograničenje** na transakciji pokušaja: inline (`inline-timeout`, zadano 3 s) radi na
   niti HTTP zahtjeva, job (`timeout`, zadano 10 s) na jedinoj niti `@Scheduled` poslova (dijeli je s
   eGOP i mail retryjem). `JpaTransactionManager` ga prenosi na JDBC (`setQueryTimeout`) i JPA upite
   (uključujući `FOR UPDATE`); istek je običan neuspjeh.
8. **Zaštita od pogrešnog konteksta.** `VerifikacijaOutbox.zabiljezi` je `MANDATORY` (samo u
   transakciji izdavanja). `EturizamVerifikacijaRepository` (jedini upis u `str`) je vidljiv samo
   unutar paketa `str.verifikacija`, pa ga transakcija izdavanja (`RegistrationService`, drugi paket)
   ne može zvati; `MANDATORY` odbija poziv bez transakcije.
9. **Log:** svaki neuspjeh jednom — WARN `eturizam_verifikacija_failed` (ERROR pri odustajanju) s
   očišćenim razlogom; puni stack trace, koji može nositi vrijednosti retka, samo na DEBUG. Pri startu
   (`StartupDiagnostics`, uz uključene obavijesti): ERROR `eturizam_verifikacija_config` za
   `vrsta` / `objekt-tip` dulje od 64 znaka i za predložak bez `{rn}`.

**UNIQUE `source_document_id` pod konkurencijom:** SELECT pa INSERT; ako eTurizam upiše zahtjev za
isti dokument između ta dva koraka, INSERT na PostgreSQL-u čeka njegov commit i padne na
`uq_verification_request_source_document` (`DuplicateKeyException`). Transakcija se poništi, a servis
odmah ponovi pokušaj jednom — samo za taj sukob (prepoznaje se po imenu ograničenja); drugi pokušaj
zatekne zahtjev (`ZAHTJEV_POSTOJAO`). Svaki drugi sukob ključa (npr. PK, sekvenca iza podataka) je
obični neuspjeh. `ON CONFLICT DO NOTHING RETURNING id` nije korišten jer ga H2 (testovi) nema; id se
nakon INSERT-a čita po dokumentu.

**`ZAHTJEV_POSTOJAO`** znači da je zahtjev za dokument već postojao — upisao ga je eTurizam ili STR
za drugi RB istog dokumenta (v. § 6, t. 8). Vlastiti zahtjev istog retka ondje ne može završiti: upis
zahtjeva i zatvaranje outbox retka su u istoj transakciji, pa i kad ishod commita nije poznat, ili je
prošlo oboje (redak je zatvoren i sljedeći pokušaj ga preskače) ili ništa.

**Vrijeme:** `created_date`, `last_modified_date` i `stvoreno` su `LocalDateTime` u zoni
Europe/Zagreb (projektni `Clock`) **u trenutku obrade**, ne izdavanja RB-a (v. § 6, P3). Outbox drži
`TIMESTAMPTZ`.

**Tekst:** predložak s `{rn}` i `{objekt}`; `{objekt}` = „naziv“ (mjesto, ID facilityId), naziv i
mjesto iz eTurizma pri izdavanju RB-a (bez naziva samo zagrada). Naziv i mjesto su tuđi unos, a ne znamo
prikazuje li eTurizam `tekst` kao HTML (§ 6, P5), pa se čiste: bez kontrolnih i formatnih znakova
(prijelomi, bidi, nevidljivi), bez znakova `<`, `>`, `&`, `"`, `'` i obrnutog navodnika, sažeti razmaci, najviše 100 znakova svaki — RB i ID
tako nikad ne ispadnu. Cijeli tekst se skraćuje na 1000 znakova (s „…“, bez presijecanja surogatnog
para).

### 4.2 Paketi i razredi (`com.str.backend.str.verifikacija`)

| Razred | Uloga |
|---|---|
| `EturizamVerifikacijaProperties` (+ `EturizamVerifikacijaConfig`) | `str.eturizam.verifikacija.*`, jedino mjesto zadanih vrijednosti |
| `VerifikacijaOutboxEntity`, `VerifikacijaOutboxRepository` | `str_rn.eturizam_verifikacija`, zaključavanje retka, dospjeli retci |
| `VerifikacijaOutbox` | bilježenje (u transakciji izdavanja), neuspjeh / bez prava / odustajanje, dospjeli retci |
| `EturizamVerifikacijaRepository` (paketno vidljiv) | uski native SQL nad `str.verification_request`, `str.korisnicka_obavijest`, `str.document`, `str.application_user` (`MANDATORY`) |
| `EturizamVerifikacijaWriter` | jedan atomaran pokušaj (`REQUIRES_NEW`, inline/job ograničenje), provjera pri obradi |
| `EturizamVerifikacijaService` | zastavica, utrka na UNIQUE, gutanje i bilježenje grešaka, ishod za job |
| `EturizamVerifikacijaListener` | inline pokušaj nakon commita (zadnji slušatelj) |
| `EturizamVerifikacijaRetryJob` | ponavljanje, prekid serije na infrastrukturnom neuspjehu |
| `VerifikacijaObavijest` | tekst, čišćenje i opis objekta, `podaci` JSON, skraćivanje (čiste funkcije) |
| `VerifikacijaGreska` | razvrstavanje neuspjeha (bez prava / infrastruktura / podaci) i siguran opis |

### 4.3 Outbox `str_rn.eturizam_verifikacija` (changeseti 151, 153)

| Stupac | Tip | Napomena |
|---|---|---|
| `verifikacija_id` | UUID PK | |
| `rn` | varchar(20) NOT NULL, **UNIQUE** | jedan zahtjev po RB-u |
| `submission_id` | UUID NOT NULL | |
| `facility_id` | varchar(64) NOT NULL | `str.facility.id` |
| `document_id` | bigint NOT NULL | = `source_document_id` |
| `objekt_naziv`, `objekt_mjesto` | varchar(255) | iz eTurizma pri izdavanju (152) |
| `status` | varchar(32) NOT NULL | `PENDING` → `ZAHTJEV_UPISAN` / `ZAHTJEV_POSTOJAO` / `PRESKOCENO` / `ABANDONED` |
| `verification_request_id` | bigint | upisani ili zatečeni zahtjev |
| `obavijesti_poslano` | integer | broj upisanih obavijesti; NULL dok nije obrađen |
| `attempts` | integer NOT NULL | neuspjeli pokušaji (bez prava se ne broje) |
| `next_attempt_at` | timestamptz | NULL kad je obrađen |
| `last_error` | varchar(500) | razlog neuspjeha, preskakanja ili odustajanja; `bez_prava: …` dok čeka GRANT |
| `created_at`, `updated_at` | timestamptz NOT NULL | |

Indeks `(status, next_attempt_at)`. Lokalno (changeset 152, `context="local"` i uvjet da je
`str.application_user` lokalni mock bez `created_by`): mock `str.verification_request` i
`str.korisnicka_obavijest` 1:1 kao na CDU-u, plus `str.document.jurisdiction_organizational_unit_id`
(postavljen samo seedanim dokumentima 900–999) i `str.application_user.organizational_unit_id`.

**Liquibase konteksti:** `application-prod.properties` i `application-test.properties` (glavni, ne JUnit)
sada imaju `spring.liquibase.contexts=prod` odnosno `test` i `default-schema=str_rn`, kao ostali
profili. U changelogu postoje samo konteksti `local`, `dev` i `cdu`, pa produkcija vrti točno
changesete bez konteksta — mockovi nad shemom `str` se ondje više ne mogu izvršiti.

### 4.4 Konfiguracija

Zadane vrijednosti su samo u `EturizamVerifikacijaProperties`; `application.properties` ključeve veže na
env s praznim defaultom (`${STR_…:}`), a prazno znači „zadano". Iznimka je cron, koji nije dio zapisa.

| Ključ | Env | Zadano |
|---|---|---|
| `str.eturizam.verifikacija.enabled` | `STR_ETURIZAM_VERIFIKACIJA_ENABLED` | `false` |
| `str.eturizam.verifikacija.korisnik` | `STR_ETURIZAM_VERIFIKACIJA_KORISNIK` | `STR` |
| `str.eturizam.verifikacija.timeout` | `STR_ETURIZAM_VERIFIKACIJA_TIMEOUT` | `PT10S` (job, bilježenje neuspjeha) |
| `str.eturizam.verifikacija.inline-timeout` | `STR_ETURIZAM_VERIFIKACIJA_INLINE_TIMEOUT` | `PT3S` (prvi pokušaj, nit HTTP zahtjeva) |
| `str.eturizam.verifikacija.obavijesti.enabled` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_ENABLED` | `false` |
| `str.eturizam.verifikacija.obavijesti.vrsta` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_VRSTA` | `ZURNA_VERIFIKACIJA` |
| `str.eturizam.verifikacija.obavijesti.objekt-tip` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_OBJEKT_TIP` | `VERIFICATION_REQUEST` |
| `str.eturizam.verifikacija.obavijesti.tekst` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_TEKST` | v. § 2a |
| `str.eturizam.verifikacija.obavijesti.max-primatelja` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_MAX_PRIMATELJA` | `200` |
| `str.eturizam.verifikacija.retry.cron` | `STR_ETURIZAM_VERIFIKACIJA_RETRY_CRON` | `0 */5 * * * *` (u `application.properties`) |
| `str.eturizam.verifikacija.retry.max-attempts` | `STR_ETURIZAM_VERIFIKACIJA_RETRY_MAX_ATTEMPTS` | `10` |
| `str.eturizam.verifikacija.retry.backoff-base` / `-max` | `STR_ETURIZAM_VERIFIKACIJA_RETRY_BACKOFF_BASE` / `_MAX` | `PT5M` / `PT2H` |
| `str.eturizam.verifikacija.retry.batch-size` | — | `50` |

`StartupDiagnostics` logira `startup_eturizam_verifikacija enabled=… obavijesti=… nedostaje=[…]`:
SELECT i INSERT na `str.verification_request`, uz obavijesti još SELECT na `str.document` i
`str.application_user` te INSERT na `str.korisnicka_obavijest` (WARN kad je uključeno, a prava nema;
start nikad ne pada).

**Povrat u red:** jedan redak `UPDATE str_rn.eturizam_verifikacija SET status = 'PENDING', attempts = 0,
next_attempt_at = now(), last_error = NULL, updated_at = now() WHERE rn = '<RB>' AND status = 'ABANDONED'`;
svi retci koji su čekali prava — upit u `DEPLOY-CDU.md` § 8e (i preprod/cdupreprod).

### 4.5 Prava po okolinama

- CDU test: `shorttermrental` ima SELECT/INSERT/UPDATE na obje tablice — smije se uključiti.
- Preprod, cdupreprod (`str_owner`): samo SELECT → najmanje potrebno:
  `GRANT SELECT, INSERT ON str.verification_request TO str_owner; GRANT INSERT ON str.korisnicka_obavijest TO str_owner;`
  (+ SELECT na `str.document`, `str.application_user`, ako ga nema). `UPDATE`/`DELETE` ne treba (STR
  postojeće retke ne mijenja), sekvenca ne treba (`identity`, V2). Detalji i provjera:
  `DEPLOY-PREPROD.md` § 2e, `DEPLOY-CDU-PREPROD.md` § B5b, `DEPLOY-CDU.md` § 8e.
- Produkcija: prava nisu provjerena.

### 4.6 Testovi

`EturizamVerifikacijaIntegrationTest` (H2, tablice stvara sam, id-evi 951000–951999, ručno čišćenje),
`EturizamVerifikacijaServiceTest`, `EturizamVerifikacijaRetryJobTest`, `VerifikacijaObavijestTest`,
`StartupDiagnosticsTest`. Pokriveno: neverificiran objekt kroz cijelo izdavanje → outbox + zahtjev +
obavijesti točno aktivnim internim korisnicima jedinice; verificiran i nov objekt → ništa; postojeći
zahtjev → `ZAHTJEV_POSTOJAO` bez obavijesti; bez jedinice / bez korisnika → zahtjev bez obavijesti;
obavijesti ugašene → samo zahtjev; granica primatelja; zastavica ugašena → `PENDING`; pad obavijesti →
zahtjev poništen, neuspjeh s backoffom; **ponovna dodjela** (RB1 opozvan dok je integracija ugašena,
RB2 za isti objekt → RB1 `PRESKOCENO`, RB2 upisuje); povučen RB, objekt u međuvremenu verificiran i RB
koji ne postoji → `PRESKOCENO`; suspendiran RB i dalje upisuje; pad provjere verificiranosti → obični
neuspjeh; bez prava → pokušaji se ne troše; retry backoff, uspjeh nakon otklanjanja uzroka, odustajanje,
prekid serije na infrastrukturnom neuspjehu; utrka na UNIQUE (samo za UNIQUE dokumenta); zastario
pokušaj se preskače i ne broji dvaput; job ne pokušava prije roka; **naš** timeout prekida čekanje na
eTurizamov nepotvrđeni zahtjev (H2 lock timeout za taj test dignut na 20 s, pokušaj s 1 s završi za
~1 s); čišćenje HTML/kontrolnih/bidi znakova i ograničenje naziva; siguran `last_error`; zadane
vrijednosti iz zapisa; provjera konfiguracije pri startu; `zabiljezi` izvan transakcije pada.

### 4.7 Izvan opsega

- Frontend: bez promjena.
- `CLAUDE.md` i skill `str-schema-strategy` još kažu da je `str` samo za čitanje — iznimku treba
  dopisati (prijedlog teksta je u opisu PR-a).

## 5. Koraci do produkcije

1. Pregled koda, PR.
2. CDU test: `STR_ETURIZAM_VERIFIKACIJA_ENABLED=true`, zahtjev za RB za neverificiran objekt, provjera
   po `DEPLOY-CDU.md` § 8e (Z1–Z4).
3. Simon: odgovori na § 6 → vrijednosti obavijesti u `.env`, pa `..._OBAVIJESTI_ENABLED=true`.
4. Darko: grant za `str_owner` (preprod, cdupreprod), pa uključivanje ondje.

## 6. Otvorena pitanja za Simona

1. **`korisnicka_obavijest`:** koje vrijednosti idu u `vrsta` i `objekt_tip` (privremeno
   `ZURNA_VERIFIKACIJA` / `VERIFICATION_REQUEST`)? Je li `objekt_id` = `verification_request.id`
   ispravan, ili očekuju `document.id`? Koriste li `podaci` i u kojem obliku? Odgovara li tekst iz § 2a?
2. **`created_by` = `STR`:** smeta li eTurizmu oznaka sustava umjesto OIB-a (postojeći retci nose OIB
   podnositelja; W1)?
3. **Postojeći zahtjev za isti dokument:** je li ispravno da STR ne radi ništa (ni ponovnu obavijest)?
   Ako je zatečeni `active = false`, treba li ga ponovno aktivirati?
4. **Zatvaranje:** postavlja li eTurizam `active = false` kad je verifikacija gotova? Je li u redu da
   STR zahtjev ne dira pri opozivu/povlačenju RB-a?
5. **Primatelji:** je li dovoljna jedinica dokumenta, ili i cijela organizacija (W3: u MINTS jedinici
   27 su 4 korisnika, u organizaciji 36)? Treba li filtar po roli (npr. `VERIFIER`)?
6. **Backfill:** treba li jednokratno upisati zahtjeve za neverificirane objekte kojima je RB izdan
   prije puštanja (W6: 2 takva na CDU-u)?
7. **Pravilo „neverificiran":** STR gleda zapis jedinice u trenutku izdavanja (`created_by = 'optimit'`).
   Objekt u verifikaciji koja traje (autor nije `optimit`) zahtjev ne dobiva — je li to ispravno?
8. **Drugi RB za drugu jedinicu istog dokumenta:** zahtjev se veže uz dokument (UNIQUE), pa drugi RB
   dobije `ZAHTJEV_POSTOJAO` bez obavijesti — i kad je prvi zahtjev upisao sam STR. Treba li za drugu
   jedinicu ipak obavijestiti službenike?
9. **Obavijesti uključene naknadno:** za zahtjeve upisane dok su obavijesti bile ugašene obavijest se
   ne šalje ni poslije. Je li to u redu, ili treba jednokratno obavijestiti?
10. ~~**Zaostatak nakon povlačenja**~~ — riješeno u kodu: redak čiji je RB u međuvremenu opozvan ili
    povučen, ili čiji je objekt verificiran, završi `PRESKOCENO` bez upisa. Potvrditi da je to željeno.
11. **(P5) Prikaz teksta:** prikazuje li eTurizam `korisnicka_obavijest.tekst` kao običan tekst ili
    kao HTML? STR naziv i mjesto objekta čisti (bez `< > & " '`, kontrolnih i bidi znakova), ali
    eTurizam bi tekst svejedno trebao prikazivati kao običan tekst.
12. **(P1) Obujam:** oko 99 % objekata u eTurizmu su migrirani (`optimit`), pa gotovo svaki RB iz
    tuStarta daje zahtjev i obavijest **svim** aktivnim internim korisnicima jedinice. Je li to
    prihvatljivo, ili obavijesti treba suziti (rola, županija, jedna po organizaciji)?
13. **(P2) Verifikacija u tijeku:** objekt čija verifikacija već traje (npr. `BCVS_U_IZRADI`) — treba
    li za njega preskočiti zahtjev? STR ga danas ne prepoznaje.
14. **(P3) Vrijeme zahtjeva:** `created_date` i `stvoreno` su vrijeme **obrade**, ne izdavanja RB-a.
    Za zaostatak (zastavica ugašena, retry) to može biti sati ili dani kasnije, što mijenja redoslijed
    na listi prioriteta (T9). Treba li vrijeme izdavanja RB-a?
15. **(P4) Suspendiran RB:** RB u prijedlogu suspenzije ili suspendiran u trenutku obrade i dalje
    dobiva zahtjev (preskaču se samo opozvan i povučen). Je li to ispravno?


## 7. Dopuna: novi objekt uz predano rješenje (9. 11. 2026.)

Excel primjedbi (r. 8, očitovanje) traži zahtjev za verifikaciju i za objekte koji nisu u eTurizmu, a
stranka je priložila rješenje („2. opcija"). Odluka (9. 11. 2026.): za njih STR šalje **samo obavijesti**.

**Zašto bez zahtjeva.** `str.verification_request.source_document_id` je NOT NULL, FK na `str.document`
i UNIQUE. Novi objekt u eTurizmu nema dokument dok ga službenik ne unese (Verifikacije → Unos rješenja),
pa STR nema uz što upisati zahtjev. Posljedica: novi objekt **nije na eTurizmovoj listi prioriteta (T9)**
dok eTurizam ne odluči kako (pitanje 16).

| Pitanje | Odluka |
|---|---|
| Okidač | Uspješna predaja rješenja uz RB **novog** objekta (`accommodation.facility_id IS NULL`), `CategorizationDecisionService.upload` — NIAS i non-EU, i kao odgovor na suspenziju. Objekt iz eTurizma ide kroz § 2 (pri izdavanju RB-a). |
| Koliko | **Jedna poslana obavijest po RB-u** (`rn` UNIQUE). Ponovna predaja nakon odbijenog rješenja ne šalje novu obavijest ako je prva već poslana (`OBAVIJESTI_UPISANE`). Ako nije (redak čeka, `PRESKOCENO` jer je prethodno rješenje odbijeno prije slanja, ili `ABANDONED`), redak se vraća na čekanje uz novo rješenje — inače službenici za njega ne bi saznali. |
| Primatelji | Aktivni interni korisnici (`active`, `internal`) **organizacije** vrste **`IOT_ZUPANIJA`** (`internal_organization_id`) čija je neka aktivna jedinica preko `settlement_jurisdiction` nadležna za barem jedno naselje **županije objekta** — lanac kao `OfficialCountyResolver`. Po organizaciji, ne po jedinici: na CDU-u dio službenika nije u jedinici s nadležnošću (W3, 9. 11.: Šibensko-kninska 1 u jedinici, 2 u organizaciji); svaka od 21 županije ima točno jednu takvu organizaciju s barem jednim službenikom (W5, `docs/sql/results/t8-novi-objekt`). Ministarstvo (`IOT_MINTS`) ne dobiva obavijest za svaki novi objekt. Najviše `max-primatelja`. |
| Županija | `accommodation.county` (adresni registar) → `str.county`: prvo po šifri (`external_code` = `rpj_dgu.zupanije.zu_rb` tog naziva), inače po nazivu (`CountyNames.key`) — kao `OfficialCountyResolver`. Neprepoznata županija ili bez službenika → redak se zatvara s 0 obavijesti i WARN-om. |
| Tekst | `obavijesti.tekst-novi-objekt`, zadano: „Uz registracijski broj {rn} priloženo je rješenje o kategorizaciji za novi smještajni objekt {objekt}. Objekt nije upisan u eTurizam – potrebna je žurna verifikacija. Rješenje unesite kroz modul Verifikacije, funkcionalnost Unos rješenja." Uputa je na zahtjev MINTS-a (9. 11. 2026.): službenik objekt unosi kroz Verifikacije → Unos rješenja. `{objekt}` = „naziv“ (naselje ili grad) iz zahtjeva za RB, očišćeno; bez oboje „(bez naziva)". |
| Ostala polja | `vrsta` ista kao § 2; `objekt_tip` i `objekt_id` **NULL** (nema zahtjeva); `podaci` = `{"rn","decisionId"}` (`str_rn.categorization_decision`). |
| Provjera pri obradi | RB opozvan/povučen/ne postoji, ili rješenje više nije `SUBMITTED` (službenik ga je prihvatio ili odbio) → `PRESKOCENO`. |
| Obavijesti ugašene | Redak čeka `PENDING`, ne troši pokušaje i ne ulazi u seriju retry joba (`due()` ga ne uzima); obradi se kad se obavijesti uključe. |

**Izvedba.** Isti outbox (`str_rn.eturizam_verifikacija`, changeset 154): stupac `vrsta`
(`ETURIZAM_OBJEKT` / `NOVI_OBJEKT_RJESENJE`), `decision_id`, `zupanija`; `facility_id` i `document_id`
više nisu NOT NULL; novi završni status `OBAVIJESTI_UPISANE`. Redak nastaje u transakciji predaje
(`VerifikacijaOutbox.zabiljeziNoviObjekt`, `MANDATORY`), prvi pokušaj nakon commita
(`RjesenjeNovogObjektaPredano` → `EturizamVerifikacijaListener`), retry i odustajanje kao § 4.1.

**Prava.** Uz § 4.5 još SELECT na `str.county`, `str.municipality`, `str.settlement`,
`str.settlement_jurisdiction`, `str.organizational_unit`, `str.internal_organization`,
`str.codebook_element` (`StartupDiagnostics` ih provjerava uz uključene obavijesti) i na
`rpj_dgu.zupanije`.

**Provjera na CDU-u prije uključivanja:** `docs/sql/t8-novi-objekt-dijagnostika.sql` (vrste organizacija,
šifre županija, broj primatelja po županiji).

### 7a. Otvorena pitanja za Simona (dopuna)

16. **Lista prioriteta za novi objekt:** novi objekt nema `str.document`, pa ni `verification_request`.
    Kako ga eTurizam želi na listi (zahtjev bez dokumenta, druga tablica, ili ga kreira tuStart, kako
    kaže očitovanje „Riješiti na TuStart strani")?
17. **Primatelji za novi objekt:** je li ispravno „sve aktivne jedinice `IOT_ZUPANIJA` nadležne za
    županiju objekta", bez MINTS-a? Ili treba jedinica po naselju objekta?
18. **`vrsta` / `objekt_tip`** za obavijest bez zahtjeva: ista `vrsta` kao za objekt iz eTurizma i
    `objekt_tip` NULL — ili zasebna vrsta?
