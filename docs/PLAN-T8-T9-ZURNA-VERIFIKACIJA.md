# Plan: T8 (žurna verifikacija) i T9 (lista prioriteta za provjeru)

Stanje: **implementirano** (grana `feature/zurna-verifikacija`, 5. 11. 2026.). Upis je iza zastavice `str.eturizam.verifikacija.enabled` (zadano `false`), obavijesti iza vlastite (zadano `false`) dok eTurizam ne potvrdi vrijednosti iz § 6. Pravila označena s *privremeno* (§ 2a) treba potvrditi sa Simonom.

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
| Zastarjeli zaostatak | Retci nastali dok je zastavica ugašena obrade se kad se uključi (zahtjev ima smisla i kasno; obavijesti također). |

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
- **`str_owner` ima samo SELECT.** Preprod i cdupreprod rade pod ulogom `str_owner`, pa ondje upis ne prolazi. Treba `GRANT INSERT, UPDATE ON str.verification_request, str.korisnicka_obavijest TO str_owner` (Darko). Prava produkcijskog korisnika nisu provjerena.

**Posljedice za dizajn:**
- INSERT zahtjeva: prvo `SELECT` po `source_document_id`, pa `INSERT` i čitanje `id`-a po dokumentu (prenosivo na H2; v. § 4.1 — `ON CONFLICT … RETURNING` nije korišten).
- `source_document_id` = `str.facility.document_id` objekta, isto što i `documentId` iz `findOwnership`. Provjerava se skriptom `t8-verifikacija-dijagnostika-2.sql` (W1, W6, W7).
- Audit stupce i `stvoreno` puni STR, u lokalnom vremenu Europe/Zagreb. Zonu baze pokazuje W0.
- T9 nema što upisati: lista prioriteta je eTurizmova, iz zahtjeva i njihovog `created_date`.

## 4. Izvedba

### 4.1 Tok

1. **Izdavanje RB-a** (`RegistrationService.commitRegistration`, ista transakcija):
   `VerifikacijaOutbox.zabiljezi(rn, submissionId, facilityId, facility)` upisuje redak u
   `str_rn.eturizam_verifikacija` (`PENDING`) samo za neverificiran eTurizam objekt s `documentId`.
   Bez `documentId` → WARN `eturizam_verifikacija_bez_dokumenta`, retka nema. Redak nastaje i dok je
   zastavica ugašena. `verifyAndCompleteFacility` sada vraća provjereni `FacilityOwnershipRow`.
2. **Nakon commita** (`EturizamVerifikacijaListener`, `AFTER_COMMIT` na `RnIssuedEvent`, zaseban
   slušatelj, ništa ne baca) → `EturizamVerifikacijaService.obradi(rn)`.
3. **Jedan pokušaj = jedna `REQUIRES_NEW` transakcija** (`EturizamVerifikacijaWriter.upisi`):
   outbox redak `SELECT … FOR UPDATE` (inline i job ne obrađuju isti RB istodobno) → postoji li zahtjev
   za dokument → INSERT zahtjeva → (samo ako ga je upisao STR i obavijesti su uključene) po jedan
   INSERT obavijesti za svakog primatelja → outbox `ZAHTJEV_UPISAN` / `ZAHTJEV_POSTOJAO`. Ista baza,
   pa je sve atomarno: bilo koja greška poništava i zahtjev i obavijesti.
4. **Neuspjeh** se bilježi u zasebnoj `REQUIRES_NEW` (`VerifikacijaOutbox.recordFailure`):
   `attempts + 1`, `next_attempt_at` po `EgopRetryPolicy` (5 min, 10, 20 … do 2 h), `last_error` =
   najdublji uzrok. Nakon `max-attempts` (10) → `ABANDONED` + ERROR `eturizam_verifikacija_exhausted`
   s RB-om i id-em dokumenta.
5. **Retry** (`EturizamVerifikacijaRetryJob`, samo uz `enabled=true`, cron
   `str.eturizam.verifikacija.retry.cron`): dospjeli `PENDING` retci kroz isti put kao inline. Novi
   redak dospijeva tek nakon `backoff-base`, da job ne preteče inline pokušaj.
6. **Tko smije pokušati.** Pokušaj nosi broj pokušaja koji je pozivatelj vidio (inline 0, job iz
   pročitanog retka). Nakon zaključavanja redak mora imati upravo toliko pokušaja, a za job i dospjeli
   rok; inline smije prije roka, ali samo kao prvi. `recordFailure` broji neuspjeh samo uz isti broj
   pokušaja. Tako dvije instance ili zakašnjeli inline pokušaj isti neuspjeh ne broje dvaput.
7. **Vremensko ograničenje** (`str.eturizam.verifikacija.timeout`, zadano 10 s) na transakciji
   pokušaja: inline radi na niti HTTP zahtjeva, job na jedinoj niti `@Scheduled` poslova (dijeli je s
   eGOP i mail retryjem). `JpaTransactionManager` ga prenosi na JDBC (`setQueryTimeout`) i JPA upite
   (uključujući `FOR UPDATE`); istek je običan neuspjeh.
8. **Zaštita od pogrešnog konteksta.** `VerifikacijaOutbox.zabiljezi` je `MANDATORY` (samo u
   transakciji izdavanja). `EturizamVerifikacijaRepository` je `MANDATORY` i uz to provjerava naziv
   transakcije (`eturizam-verifikacija`) — `MANDATORY` sam bi se priključio i transakciji izdavanja RB-a.
9. **Log:** svaki neuspjeh jednom (WARN `eturizam_verifikacija_failed` sa stack traceom, odnosno ERROR
   `eturizam_verifikacija_exhausted`). Preduga `vrsta` / `objekt-tip` (> 64) uz uključene obavijesti:
   ERROR `eturizam_verifikacija_config` pri startu.

**UNIQUE `source_document_id` pod konkurencijom:** SELECT pa INSERT; ako eTurizam upiše zahtjev za
isti dokument između ta dva koraka, INSERT na PostgreSQL-u čeka njegov commit i padne na
`uq_verification_request_source_document` (`DuplicateKeyException`). Transakcija se poništi, a servis
odmah ponovi pokušaj jednom — drugi pokušaj zatekne zahtjev (`ZAHTJEV_POSTOJAO`). `ON CONFLICT DO
NOTHING RETURNING id` nije korišten jer ga H2 (testovi) nema; id se nakon INSERT-a čita po dokumentu.

**Vrijeme:** `created_date`, `last_modified_date` i `stvoreno` su `LocalDateTime` u zoni
Europe/Zagreb (projektni `Clock`), kao eTurizamovi retci (W0). Outbox drži `TIMESTAMPTZ`.

**Tekst:** predložak s `{rn}` i `{objekt}`; `{objekt}` = „naziv“ (mjesto, ID facilityId) iz STR
smještaja nakon dopune iz eTurizma (bez naziva samo zagrada). Skraćuje se na 1000 znakova (s „…“, bez
presijecanja surogatnog para).

### 4.2 Paketi i razredi (`com.str.backend.str.verifikacija`)

| Razred | Uloga |
|---|---|
| `EturizamVerifikacijaProperties` (+ `EturizamVerifikacijaConfig`) | `str.eturizam.verifikacija.*` |
| `VerifikacijaOutboxEntity`, `VerifikacijaOutboxRepository` | `str_rn.eturizam_verifikacija`, zaključavanje retka, dospjeli retci |
| `VerifikacijaOutbox` | bilježenje (u transakciji izdavanja), neuspjeh/odustajanje, dospjeli retci |
| `EturizamVerifikacijaRepository` | uski native SQL nad `str.verification_request`, `str.korisnicka_obavijest`, `str.document`, `str.application_user` (`Propagation.MANDATORY`) |
| `EturizamVerifikacijaWriter` | jedan atomaran pokušaj (`REQUIRES_NEW`) |
| `EturizamVerifikacijaService` | zastavica, utrka na UNIQUE, gutanje i bilježenje grešaka |
| `EturizamVerifikacijaListener` | inline pokušaj nakon commita |
| `EturizamVerifikacijaRetryJob` | ponavljanje |
| `VerifikacijaObavijest` | tekst, opis objekta, `podaci` JSON, skraćivanje (čiste funkcije) |

### 4.3 Outbox `str_rn.eturizam_verifikacija` (changeset 150)

| Stupac | Tip | Napomena |
|---|---|---|
| `verifikacija_id` | UUID PK | |
| `rn` | varchar(20) NOT NULL, **UNIQUE** | jedan zahtjev po RB-u |
| `submission_id` | UUID NOT NULL | |
| `facility_id` | varchar(64) NOT NULL | `str.facility.id` |
| `document_id` | bigint NOT NULL | = `source_document_id` |
| `status` | varchar(32) NOT NULL | `PENDING` → `ZAHTJEV_UPISAN` / `ZAHTJEV_POSTOJAO` / `ABANDONED` |
| `verification_request_id` | bigint | upisani ili zatečeni zahtjev |
| `obavijesti_poslano` | integer | broj upisanih obavijesti; NULL dok nije obrađen |
| `attempts` | integer NOT NULL | neuspjeli pokušaji |
| `next_attempt_at` | timestamptz | NULL kad je obrađen |
| `last_error` | varchar(500) | |
| `created_at`, `updated_at` | timestamptz NOT NULL | |

Indeks `(status, next_attempt_at)`. Lokalno (changeset 151, `context="local"`): mock
`str.verification_request` i `str.korisnicka_obavijest` 1:1 kao na CDU-u, plus
`str.document.jurisdiction_organizational_unit_id` i `str.application_user.organizational_unit_id`.

### 4.4 Konfiguracija

| Ključ | Env | Zadano |
|---|---|---|
| `str.eturizam.verifikacija.enabled` | `STR_ETURIZAM_VERIFIKACIJA_ENABLED` | `false` |
| `str.eturizam.verifikacija.korisnik` | `STR_ETURIZAM_VERIFIKACIJA_KORISNIK` | `STR` |
| `str.eturizam.verifikacija.timeout` | `STR_ETURIZAM_VERIFIKACIJA_TIMEOUT` | `PT10S` |
| `str.eturizam.verifikacija.obavijesti.enabled` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_ENABLED` | `false` |
| `str.eturizam.verifikacija.obavijesti.vrsta` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_VRSTA` | `ZURNA_VERIFIKACIJA` |
| `str.eturizam.verifikacija.obavijesti.objekt-tip` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_OBJEKT_TIP` | `VERIFICATION_REQUEST` |
| `str.eturizam.verifikacija.obavijesti.tekst` | `STR_ETURIZAM_VERIFIKACIJA_OBAVIJESTI_TEKST` | v. § 2a |
| `str.eturizam.verifikacija.retry.cron` | `STR_ETURIZAM_VERIFIKACIJA_RETRY_CRON` | `0 */5 * * * *` |
| `str.eturizam.verifikacija.retry.max-attempts` | `STR_ETURIZAM_VERIFIKACIJA_RETRY_MAX_ATTEMPTS` | `10` |
| `str.eturizam.verifikacija.retry.backoff-base` / `-max` | `STR_ETURIZAM_VERIFIKACIJA_RETRY_BACKOFF_BASE` / `_MAX` | `PT5M` / `PT2H` |
| `str.eturizam.verifikacija.retry.batch-size` | — | `50` |

`StartupDiagnostics` logira `startup_eturizam_verifikacija enabled=… obavijesti=… nedostaje=[…]`:
SELECT i INSERT na `str.verification_request`, uz obavijesti još SELECT na `str.document` i
`str.application_user` te INSERT na `str.korisnicka_obavijest` (WARN kad je uključeno, a prava nema;
start nikad ne pada).

**Povrat `ABANDONED` retka:** `UPDATE str_rn.eturizam_verifikacija SET status = 'PENDING', attempts = 0,
next_attempt_at = now(), last_error = NULL, updated_at = now() WHERE rn = '<RB>' AND status = 'ABANDONED'`
(`DEPLOY-CDU.md` § 8e).

### 4.5 Prava po okolinama

- CDU test: `shorttermrental` ima SELECT/INSERT/UPDATE na obje tablice — smije se uključiti.
- Preprod, cdupreprod (`str_owner`): samo SELECT → `GRANT INSERT, UPDATE ON str.verification_request,
  str.korisnicka_obavijest TO str_owner` (Darko). STR koristi samo INSERT. Detalji i provjera:
  `DEPLOY-PREPROD.md` § 2e, `DEPLOY-CDU-PREPROD.md` § B5b, `DEPLOY-CDU.md` § 8e.
- Produkcija: prava nisu provjerena.

### 4.6 Testovi

`EturizamVerifikacijaIntegrationTest` (H2, tablice stvara sam, id-evi 951000–951999, ručno čišćenje),
`EturizamVerifikacijaServiceTest`, `VerifikacijaObavijestTest`. Pokriveno: neverificiran objekt kroz
cijelo izdavanje → outbox + zahtjev + obavijesti točno aktivnim internim korisnicima jedinice;
verificiran i nov objekt → ništa; postojeći zahtjev → bez upisa, bez obavijesti, `ZAHTJEV_POSTOJAO`;
bez jedinice / bez korisnika → zahtjev bez obavijesti; obavijesti ugašene → samo zahtjev; zastavica
ugašena → `PENDING`, ništa upisano; pad obavijesti → zahtjev poništen, neuspjeh zabilježen s
backoffom; retry backoff, uspjeh nakon otklanjanja uzroka i odustajanje; utrka na UNIQUE; skraćivanje
na 1000; izdavanje RB-a ne trpi kad upis u eTurizam padne; zastario pokušaj (drugi je redak već
obradio/odgodio) se preskače i ne broji dvaput; job ne pokušava prije roka; ograničenje trajanja stiže
do JDBC veze i EntityManagera; čekanje na eTurizamov nepotvrđeni zahtjev za isti dokument završava kao
zabilježen neuspjeh; repozitorij odbija tuđu transakciju; `zabiljezi` izvan transakcije pada.

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
10. **Zaostatak nakon povlačenja:** retci nastali dok je integracija ugašena obrade se kad se uključi,
    i kad je RB u međuvremenu povučen ili opozvan — tekst tada kaže „dodijeljen je registracijski
    broj” za broj koji više ne vrijedi. Treba li takve retke preskočiti?
