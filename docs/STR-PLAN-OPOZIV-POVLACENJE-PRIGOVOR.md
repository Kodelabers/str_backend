# Plan implementacije — Povlačenje/opoziv RB (STR-2.3) i postupak suspenzije s prigovorom (STR-2.1)

**Izvor zahtjeva:** Funkcionalna specifikacija STR v3.1 (16.07.2026.), poglavlja 1.1, 2.6, 2.8, 2.12.
**Opseg:** backend (`str_backend`). Frontend se radi u zasebnom repou — ugovorne promjene su označene s **[FE]**.
**Datum plana:** 2026-07-20

---

## Sažetak odluka

| # | Odluka | Obrazloženje |
|---|--------|--------------|
| D1 | Uvodi se novi `RnStatus.REVOKED` (opozvan) uz postojeći `WITHDRAWN` (povučen) | Spec §1.1 i §2.12 tretiraju „Opozvan" i „Povučen" kao **dva statusa** koja se dostavljaju platformama; jedan status + diskriminator značio bi da svaka vanjska projekcija mora sastavljati status iz dva polja |
| D2 | Razlozi su Java enumi + lookup endpoint, ne DB šifrarnik | Lista je pravno fiksirana Uredbom; enum ne uvodi seed drift između okruženja, a lookup endpoint sprječava da FE hardkodira hrvatske nazive |
| D3 | Postupak suspenzije je **zaseban agregat** (`rn_suspension_procedure`), ne novi `RnStatus` | Spec §2.6: tijekom cijelog postupka (obavijest → rok → prigovor → odluka) RB **ostaje „Aktivan"**; tek izvršenje mijenja status |
| D4 | Generirani akti se pohranjuju u `str_rn.rn_document` | Spec traži „pohranjuje ga u postojeći predmet"; eGOP je stub (BX2), pa lokalna pohrana služi kao predmet do integracije. Presedan: `submission.pdf_content` (changeset 015) |
| D5 | Izravni `POST /api/rn/{rn}/suspend` ostaje, ali se u fazi 3 zaključava iza postojanja izvršivog postupka | Spec je izričit da obavijest prethodi suspenziji; postupno gašenje ne lomi postojeći FE odmah |

---

# TOČKA 1 — Opoziv i povlačenje registracijskog broja

## 1.1 Model statusa i trigera

**Trenutno stanje:** `RnStatus.WITHDRAWN` pokriva oba slučaja; razlikuju se samo po `registration_number_log.actor` (`LESSOR:`/`NIAS:` vs. `null`). `RnTrigger.WITHDRAWAL` se koristi za oba.

**Ciljno stanje:**

```java
public enum RnStatus {
    IN_PROCESSING,
    ACTIVE,
    SUSPENDED,
    REVOKED,    // opozvan — na zahtjev iznajmljivača (čl. 5. st. 5.)
    WITHDRAWN;  // povučen — mjera nadležnog tijela (čl. 6.)
}

public enum RnTrigger {
    ISSUE, CONSENT_EXPIRY, INSPECTION, INCOMPLETE_DOCUMENTATION,
    REVOCATION,  // novi — opoziv od strane iznajmljivača
    WITHDRAWAL,  // povlačenje od strane tijela
    REACTIVATE
}
```

Prijelazi u `canTransitionTo`:

```
ACTIVE    --REVOCATION--> REVOKED       ACTIVE    --WITHDRAWAL--> WITHDRAWN
SUSPENDED --REVOCATION--> REVOKED       SUSPENDED --WITHDRAWAL--> WITHDRAWN
REVOKED   → terminalno                  WITHDRAWN → terminalno
```

## 1.2 Šifrarnici razloga

Novi paket `com.str.backend.rn.reason`:

```java
public enum RnWithdrawalReason {          // spec §2.8, taksativno
    INCOMPLETE_DOCUMENTATION("Nepotpuna dokumentacija"),
    FALSE_DATA("Netočni ili lažni podaci"),
    MISUSE("Zlouporaba registracijskog broja"),
    GROSS_VIOLATION("Gruba povreda propisa vezanih uz registracijski broj"),
    OTHER("Ostali razlozi");             // → obvezan opis
}

public enum RnSuspensionReason {          // izvedeno iz postojećih trigera
    CONSENT_EXPIRY("Istek suglasnosti suvlasnika"),
    INSPECTION("Nalaz inspekcije"),
    INCOMPLETE_DOCUMENTATION("Nepotpuna dokumentacija");
}
```

Time se razdvaja *trigger* (brid state machine-a) od *razloga* (poslovni šifrarnik) — sada ih `RnTrigger` konflatira.

**Validacija:** `OTHER` bez `note` → `BusinessException("error.rn.reason.note.required")` (400 preko `GlobalExceptionHandler`).

**[FE] Lookup endpoint** — `GET /api/rn/reasons/{withdrawal|suspension}` → `[{code, label}]`, po uzoru na `AccommodationTypeController`.

## 1.3 Perzistencija

Denormalizirano trenutno stanje na `registration_number` (za detalj i buduću platform-API projekciju bez skeniranja loga), uz log kao izvor istine za povijest:

| Tablica | Nova kolona | Tip | Napomena |
|---|---|---|---|
| `registration_number` | `termination_reason_code` | VARCHAR(48) | razlog opoziva/povlačenja |
| | `termination_reason_note` | VARCHAR(1024) | obvezan za `OTHER` |
| | `terminated_at` | TIMESTAMPTZ | spec §2.8: datum i vrijeme |
| | `terminated_by` | VARCHAR(128) | spec §2.8: službena osoba |
| | `suspension_reason_code` | VARCHAR(48) | trenutni razlog suspenzije |
| | `suspension_reason_note` | VARCHAR(1024) | |
| `registration_number_log` | `reason_code` | VARCHAR(48) | čini razloge **povijesno pretraživima** — traži uloga Inspektorat, spec §3.2 |

`RnEntity.applyStatus` proširiti: na `REACTIVATE` očistiti `suspension_reason_*`; na terminalne statuse postaviti `valid_to` (sada radi samo za `WITHDRAWN`).

> **Napomena o `suspension_deadline`:** postojeća kolona je *rok za ispravak nakon* suspenzije (commit 3c21cc6). Rok iz spec §2.6 je *prije* suspenzije i ide na `rn_suspension_procedure.remedy_deadline` (točka 2). Ne miješati ih.

## 1.4 Liquibase — changeset `051-rn-status-revoked.xml`

⚠️ `chk_rn_status` iz changeseta 005 nabraja dopuštene statuse — mora se **dropati i ponovno kreirati u novom changesetu** (postojeći se ne smije dirati, CLAUDE.md).

```sql
ALTER TABLE str_rn.registration_number DROP CONSTRAINT chk_rn_status;
ALTER TABLE str_rn.registration_number ADD CONSTRAINT chk_rn_status
    CHECK (status IN ('IN_PROCESSING','ACTIVE','SUSPENDED','REVOKED','WITHDRAWN'));
```

**Backfill** — postojeći `WITHDRAWN` nastali na zahtjev iznajmljivača postaju `REVOKED`:

```sql
UPDATE str_rn.registration_number r SET status = 'REVOKED'
WHERE r.status = 'WITHDRAWN' AND EXISTS (
    SELECT 1 FROM str_rn.registration_number_log g
    WHERE g.rn = r.rn AND g.to_status = 'WITHDRAWN'
      AND (g.actor LIKE 'LESSOR:%' OR g.actor LIKE 'NIAS:%'));
```

Odgovarajući log-retci dobivaju `to_status='REVOKED'`, `trigger_name='REVOCATION'`. To je **preimenovanje statusa za taj podskup**, ne prepisivanje povijesti — obrazložiti u `remarks` changeseta.

RB-ovi bez log-zapisa (mock seedovi 036/048, legacy) ostaju `WITHDRAWN`. Prihvatljivo; usput postaviti `valid_to = issue_date` gdje je NULL, čime se rješava i poznati problem detekcije u `WithdrawnRnRetentionJob` (B4 faza 2).

## 1.5 Blast radius — datoteke koje se **moraju** dirati

| Datoteka | Promjena |
|---|---|
| `domain/RnStatus.java` | novi `REVOKED` + prijelazi |
| `domain/RnTrigger.java` | novi `REVOCATION` |
| `rn/RnEntity.java` | nove kolone, `applyStatus` |
| `rn/RnRegistryView.java` | `ALL` i `INVALID` moraju uključiti `REVOKED`; dodati `REVOKED` view |
| `rn/RnService.java` | `inactive()` (STR-1.5) + `REVOKED`; `withdraw()` prima šifru razloga |
| `rn/RnRepository.java` | `findByStatusAndValidToBefore` → `...StatusIn...` (retencija mora pokriti oba terminalna statusa) |
| `rn/WithdrawnRnRetentionJob.java` | prosljeđuje `List.of(REVOKED, WITHDRAWN)` |
| `lessor/LessorRnActionService.java` | `withdrawOwn*` → `revokeOwn*`, cilj `REVOKED`, trigger `REVOCATION` |
| `statistics/StatisticsService.java` | `buildRow` + `CountyStrDto` — nova brojka „opozvani" **[FE]** |
| `statistics/StatisticsExportService.java` | `List.of(ACTIVE,SUSPENDED,WITHDRAWN)` (linija 49) i mapa labela (347–348) |
| `statistics/PlatformActivityQuery.java` | `mapStatus`/`mapToDbStatus` — `REVOKED ↔ "opozvan"`. Brojač anomalija (`status != 'ACTIVE'`) automatski hvata novi status |
| `rn/VerifyController.java` | `REVOKED` se javno tretira kao `WITHDRAWN` → `{valid:false}` (privatnost). Stvarni status ide isključivo na budući M2M platform-API |
| `rn/dto/RnDetailDto.java` | +`terminationReasonCode/Note`, `terminatedAt`, `suspensionReasonCode/Note` **[FE]** |

## 1.6 API promjene **[FE]**

| Endpoint | Promjena |
|---|---|
| `POST /api/rn/{rn}/withdraw` | `reason` iz opcionalnog query параметра → **obvezno tijelo** `{reasonCode, note}`. Prekidajuća promjena |
| `POST /api/lessor/registrations/{rn}/revoke` | **novo** ime za opoziv; stari `/withdraw` ostaje kao deprecated alias jedan release |
| `GET /api/rn/reasons/withdrawal` \| `/suspension` | novo — punjenje dropdowna |
| `GET /api/rn?view=…` | novi dopušteni `view=REVOKED` |
| `GET /api/rn/{rn}/detail` | nova polja razloga/datuma/aktora |

## 1.7 Akt i obavijest

- `RnDocumentType`: dodati `OBAVIJEST_POVLACENJE` („Obavijest o povlačenju registracijskog broja", spec §2.8) — sada postoji samo `NALOG_POVLACENJE`.
- E-mail iznajmljivaču o povlačenju (vidi §2.6 ovog plana — zajednički e-mail sloj).
- Dostava u KP + urudžba: **BX1/BX2, izvan opsega** — akt se pohranjuje u `rn_document`.
- Dostava platformama (spec §2.8, zadnji odlomak): **BX3, izvan opsega** — u kodu ostaviti `TODO(BX3)` na mjestu tranzicije.

---

# TOČKA 2 — Postupak suspenzije s prigovorom

## 2.1 Zašto zaseban agregat

Spec §2.6 opisuje postupak koji traje **prije** promjene statusa: obavijest → rok za otklanjanje → (opcionalni) prigovor → odluka → tek onda suspenzija. Tijekom cijelog postupka RB je **„Aktivan"**. Modeliranje kroz `RnStatus` bi zahtijevalo lažni „u postupku" status koji spec ne poznaje i koji bi procurio na javni registar i platform-API.

## 2.2 Entitet `RnSuspensionProcedureEntity` (`str_rn.rn_suspension_procedure`)

```
procedure_id UUID PK
rn                  VARCHAR(20) FK → registration_number
status              VARCHAR(32)   -- vidi state machine ispod
reason_code         VARCHAR(48)   -- RnSuspensionReason, "razlog potencijalne suspenzije"
defect_description  TEXT          -- "opis utvrđenog nedostatka ili nepravilnosti"
remedy_deadline     DATE          -- "rok za otklanjanje problema"
notice_issued_at    TIMESTAMPTZ
notice_issued_by    VARCHAR(128)
objection_filed_at  TIMESTAMPTZ
objection_text      TEXT
decision_at         TIMESTAMPTZ
decision_by         VARCHAR(128)
decision_note       TEXT
closed_at           TIMESTAMPTZ
created_at, updated_at TIMESTAMPTZ
```

**Parcijalni unique index** — najviše jedan otvoren postupak po RB-u:
```sql
CREATE UNIQUE INDEX uq_rn_procedure_open ON str_rn.rn_suspension_procedure (rn)
WHERE status IN ('NOTICE_ISSUED','OBJECTION_FILED','OBJECTION_REJECTED');
```

## 2.3 State machine postupka

```
                    ┌──> DISCONTINUED         (nedostatak otklonjen u roku — obustava)
NOTICE_ISSUED ──────┼──> OBJECTION_FILED ──┬──> OBJECTION_UPHELD   (prigovor osnovan → obustava, RB ostaje ACTIVE)
                    │                      └──> OBJECTION_REJECTED (rješenje o neosnovanosti)
                    └──────────────────────────> SUSPENSION_EXECUTED
                                                 ▲
                          OBJECTION_REJECTED ────┘
```

Terminalni: `DISCONTINUED`, `OBJECTION_UPHELD`, `SUSPENSION_EXECUTED`.

**Ključni guard (`execute`)** — suspenzija je dopuštena samo ako je:
- `status == OBJECTION_REJECTED`, **ili**
- `status == NOTICE_ISSUED` i `remedy_deadline` je prošao.

Inače `BusinessException("error.rn.procedure.not.executable")`. Ovo je poslovno pravilo koje spec izričito propisuje („Nakon isteka roka … odnosno nakon donošenja rješenja o neosnovanosti").

Prijelaze provodi `RnSuspensionProcedureService` po uzoru na `RnStatusTransitionService` — validacija + audit zapis su neodvojivi.

## 2.4 Prilozi uz prigovor — `str_rn.rn_objection_attachment`

```
attachment_id UUID PK
procedure_id  UUID FK
file_name     VARCHAR(255)
content_type  VARCHAR(100)
size_bytes    BIGINT
content       BYTEA
uploaded_at   TIMESTAMPTZ
```

Obrazac preslikan s `lessor_document` (BYTEA + `@JdbcTypeCode(SqlTypes.VARBINARY)`). Allowlist `application/pdf`, `image/jpeg`, `image/png`; limit po datoteci i po zahtjevu preko `spring.servlet.multipart.*`.

## 2.5 Akti (`RnDocumentType`)

| Konstanta | Naslov iz spec-a | Status |
|---|---|---|
| `DOPIS_NAMJERE` | → preimenovati naslov u **„Obavijest o mogućoj suspenziji registracijskog broja"** | postoji, uskladiti tekst |
| `RJESENJE_NEOSNOVANOST_PRIGOVORA` | „Rješenje o neosnovanosti prigovora" | **novo** |
| `OBAVIJEST_SUSPENZIJA` | „Obavijest o suspenziji registracijskog broja" | **novo** |
| `OBAVIJEST_POVLACENJE` | „Obavijest o povlačenju registracijskog broja" | **novo** (točka 1) |
| `NALOG_SUSPENZIJA`, `NALOG_POVLACENJE` | interni nalozi | postoje |

Obavijest o mogućoj suspenziji mora sadržavati svih pet elemenata iz spec §2.6: RB, opis nedostatka, razlog, rok, **uputu o mogućnosti podnošenja prigovora**.

`RnDocumentService.generate(rn, type, reason)` → `generate(rn, type, RnDocumentContext ctx)` gdje `ctx` nosi podatke postupka. Generirani PDF se sprema u `str_rn.rn_document` (D4).

## 2.6 E-mail sloj

`EmailService` proširiti (postojeći obrazac: `EmailTemplates` HR+EN, slanje preko `@TransactionalEventListener(AFTER_COMMIT)` kao u `RegistrationEmailListener`):

- `sendSuspensionNoticeNotification` — obavijest o mogućoj suspenziji (rok + uputa o prigovoru)
- `sendObjectionRejectedNotification` — rješenje o neosnovanosti
- `sendSuspensionNotification` — spec §2.6 izričito: *„Iznajmljivaču se šalje e-mail s obaviješću o suspenziji"*
- `sendWithdrawalNotification` — točka 1
- `sendReactivationNotification` — nije u spec-u, ali simetrično; opcionalno

Novi `RnLifecycleEmailListener` + eventi u `email/event/`. Adresa se dohvaća iz `RnRepository.findDetail(rn).lessorEmail()`; ako je prazna → `log.warn` i preskoči (isti obrazac kao postojeći listener).

## 2.7 Endpointi

**Interni portal (back office):**
```
POST   /api/rn/{rn}/suspension-procedure              pokreni postupak (reasonCode, defectDescription, remedyDeadline)
GET    /api/rn/{rn}/suspension-procedure              aktivni postupak (za ekran detalja)
GET    /api/rn/{rn}/suspension-procedures             povijest postupaka
POST   /api/rn/suspension-procedure/{id}/objection-decision   {upheld: bool, note}
POST   /api/rn/suspension-procedure/{id}/execute      provedi suspenziju (guard iz §2.3)
POST   /api/rn/suspension-procedure/{id}/discontinue  obustavi (nedostatak otklonjen)
GET    /api/rn/suspension-procedure/{id}/documents/{tip}
```

**Vanjski portal (iznajmljivač):**
```
POST   /api/lessor/registrations/{rn}/objection       multipart: tekst + prilozi — owner-only
GET    /api/lessor/registrations/{rn}/suspension-procedure   status postupka + rok
```

Autorizacija vlasništva za prigovor: ponovno iskoristiti `loadOwnedByLessorId` / `loadOwnedByOib` iz `LessorRnActionService` (404 umjesto 403 da se ne otkriva postojanje RB-a).

`SecurityConfig`: `/api/lessor/registrations/**` je već `authenticated()` — prigovor je pokriven. Back-office rute ostaju `permitAll` do BX0 (NIAS role), uz `TODO(auth/BX0)` kao na postojećim rutama.

## 2.8 Scheduler (faza 3, opcionalno)

Spec kaže da službena osoba **može** provesti suspenziju nakon isteka roka — dakle **bez automatske suspenzije**. Job po uzoru na `AccommodationActivityPurgeJob` samo označava postupke kojima je rok istekao i (opcionalno) šalje podsjetnik službeniku. Nikakva automatska promjena statusa RB-a.

---

## Redoslijed isporuke (PR-ovi)

| PR | Sadržaj | Ovisi o | Veličina |
|---|---|---|---|
| **PR-1** | `RnStatus.REVOKED` + `RnTrigger.REVOCATION`, changeset 051 (CHECK + backfill), ažuriranje svih call-site-ova i testova | — | M |
| **PR-2** | Šifrarnici razloga, kolone razloga (052), obvezan razlog na `withdraw`, lookup endpoint, `RnDetailDto` proširenje | PR-1 | M |
| **PR-3** | `rn_document` tablica (053) + novi `RnDocumentType` + `RnDocumentContext`, `OBAVIJEST_POVLACENJE` | PR-2 | S |
| **PR-4** | E-mail sloj (obavijesti o suspenziji/povlačenju/rješenju) | PR-3 | S |
| **PR-5** | Postupak suspenzije: entitet + state machine + servis + interni endpointi (054) | PR-3 | **L** |
| **PR-6** | Prigovor: prilozi, owner-only endpoint, odluka o prigovoru, rješenje o neosnovanosti | PR-5 | M |
| **PR-7** | Zaključavanje izravnog `/suspend` iza postojanja izvršivog postupka (iza konfiguracijske zastavice) | PR-6 + FE migracija | S |

Sve na feature granama → PR prema `develop` (nikad `main`).

## Testovi koje treba proširiti/dodati

**Postojeći koji će puknuti ili tražiti dopunu:**
`RnStatusTransitionServiceTest` (nazivi `withdrawn_cannot_*` → dodati `revoked_*` parnjake), `LessorRnActionServiceTest` (3 testa), `RnServiceTest` (`withdraw_passesReasonToTransition`, `withdraw_nullReasonStaysNull` — razlog postaje obvezan), `WithdrawnRnRetentionJobTest`, `StatisticsServiceTest`, `PlatformActivitiesExportTest`, `VerifyControllerTest`, `RnDocumentServiceTest`, `RnDocumentControllerTest`.

**Novi:**
- `RnSuspensionProcedureServiceTest` — svih 6 prijelaza + guard „ne može se izvršiti prije isteka roka bez odbijenog prigovora" (najvažniji test u paketu)
- `RnObjectionControllerTest` — owner-only (404 za tuđi RB), validacija priloga
- `RnWithdrawalReasonValidationTest` — `OTHER` bez opisa → 400
- Integracijski test punog toka: obavijest → prigovor → odbijen → izvršenje → `RnStatus.SUSPENDED` + tri log zapisa

## Otvorena pitanja za naručitelja

1. **Rok za podnošenje prigovora** — spec ga ne navodi. Je li vezan uz `remedy_deadline` ili je zaseban?
2. **Šifrarnik razloga suspenzije** — spec taksativno nabraja samo razloge *povlačenja*. Trenutna tri razloga suspenzije izvedena su iz koda, ne iz specifikacije. Treba potvrdu.
3. **Smije li iznajmljivač opozvati RB dok je postupak suspenzije u tijeku?** Rizik izbjegavanja mjere — prijedlog: blokirati ili barem označiti u postupku.
4. **Je li izravna suspenzija bez prethodne obavijesti ikad dopuštena** (hitni slučajevi)? O tome ovisi PR-7.
5. **Može li se opozvani RB reaktivirati?** Trenutna pretpostavka: ne, terminalan je (kao i povučen).
6. **Nakon povlačenja — može li isti objekt dobiti novi RB?** Provjera duplikata lokacije gleda samo `ACTIVE`/`SUSPENDED`, pa je trenutno odgovor „da".

## Poznata ograničenja (ostaju izvan opsega)

- **BX0** — role službenika/voditelja/Inspektorata; `terminated_by` do tada puni `SessionIdentityResolver` ili `AdminAuditService.SYSTEM`.
- **BX1** — dostava akata u Korisnički pretinac.
- **BX2** — eGOP urudžbiranje u „postojeći predmet"; do tada `rn_document` služi kao lokalni predmet.
- **BX3** — obavještavanje internetskih platformi o statusnoj promjeni i evidencija razmjene (spec §2.8/§2.12).
