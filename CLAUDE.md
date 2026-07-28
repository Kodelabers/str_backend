# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

Build tool is Maven. Run via IntelliJ ili terminalski:

```bash
mvn spring-boot:run                     # pokretanje (local profil aktivan po defaultu)
mvn test                                # svi testovi
mvn test -Dtest="Go5*"                  # jedan test razred
mvn compile                             # samo kompajlacija
mvn package                             # build fat JAR
```

## Environments & Profiles

Five Spring profiles — `local` (default), `mock`, `dev`, `test`, `prod`. Override via `SPRING_PROFILES_ACTIVE` env var.

| Profile | DB | Notes |
|---|---|---|
| `local` | `localhost:5432/str_db_local`, user `postgres` | `LocalDatabaseConfig` auto-creates the DB **and `str_rn` schema** before Liquibase runs; Liquibase runs `context=local` (mocks `str.subject*` + the rpj_dgu / eturizam_test address hierarchy + a temporary `str.country` restore until rpj_dgu.drzava is populated on dev). Password via `LOCAL_DB_PASSWORD` env var (default `postgres`) |
| `mock` | Railway PostgreSQL via `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD` | DB provisioned by Railway; `LocalDatabaseConfig` only creates `str_rn` schema; Liquibase runs `context=local` (same mock set). Set `SPRING_PROFILES_ACTIVE=mock` in Railway service env vars. |
| `dev` | shared dev PostgreSQL `s-str-02.infodom.hr:5431/str2` | `str`, `rpj_dgu`, `eturizam_test` schemas are owned by other services — read-only; Liquibase only manages `str_rn` |
| `test` | real PostgreSQL via `TEST_DB_URL/USERNAME/PASSWORD` env vars | Liquibase runs migrations only |
| `prod` | real PostgreSQL via `PROD_DB_URL/USERNAME/PASSWORD` env vars | same as test |

Unit tests (`@ActiveProfiles("test")`) use H2 from `src/test/resources/application-test.properties` — the test classpath file overrides the main one, so JUnit tests are unaffected by the real `test` env config.

Two external read-only schema families:

- `str` — `subject`, `subject_version`, `subject_address`, `address`, `country` (still used by `StrLessorLookupService` to resolve OIB → lessor identity + home address, and by `CountryRepository` for the country dropdown). On `dev`/`prod` these are fully populated by the upstream service; on `local`/`mock` only `subject*` are mocked, `address`/`country` mocks are TBD on `rpj_dgu`/`eturizam_test`. The address-hierarchy tables (`county`, `municipality`, `settlement`, `street`, `house_number`) used to live here but have been migrated off — see below.
- `rpj_dgu` + `eturizam_test` — DGU registar prostornih jedinica + eTurizam adresni registar. Address hierarchy for the registration form now reads from these:
  - `CountyEntity` → `rpj_dgu.zupanije` (id, zu_ime, zu_rb)
  - `MunicipalityEntity` → `rpj_dgu.gradovi_i_opcine` (id, jls_ime, jls_mb, zu_rb)
  - `SettlementEntity` → `rpj_dgu.naselja` (id, na_ime, na_mb, jls_mb)
  - `StreetEntity` → `eturizam_test.ar_ulice` (id, naziv_ulice, naselje_id varchar)
  - `HouseNumberEntity` → `eturizam_test.ar_address` (id, broj, ulica_id)
  - Joins use string FKs (zu_rb, jls_mb LPAD'd to 5, na_mb varchar 6), so several repository queries are native SQL.
  - Postal code is resolved via name-based LEFT JOIN on `rpj_dgu.postanski_brojevi` (≈98.6% match rate).

On `dev`/`prod` we read from the real registries. On `local`/`mock` the address hierarchy (rpj_dgu.zupanije / gradovi_i_opcine / naselja / postanski_brojevi + eturizam_test.ar_ulice / ar_address) is seeded with 4 counties / 7 municipalities / 10 settlements / 14 streets / 42 house numbers so the registration form walks end-to-end. The country dropdown reads from `str.country` (temporary restore — see comment in `104-str-country-restore-local.xml`); flip `CountryEntity` back to `rpj_dgu.drzava` and drop that changeset once GIS populates the registry. The `str.address` mock is still TBD — OIB lookup for non-test subjects will fail locally until then. Liquibase context gating is via `spring.liquibase.contexts=local` in `application-local.properties`.

## Architecture

### Schema ownership
`str.*` is owned externally (read-only on dev/prod; mocked on local/mock via Liquibase `context="local"`). Entities mapped to `str.*` carry `@Immutable` and their repositories are `@Transactional(readOnly = true)`.

`str_rn.*` is exclusively owned by this service. All `str_rn` tables are managed by Liquibase.

### Registration flow
`RegistrationService` orchestrates the lifecycle. Submissions are keyed by `submission_id` (UUID); accommodations by `accommodation_id` (UUID); lessors by `lessor_id` (UUID).

```
POST /api/generateRegistrationNumber             → RegistrationService.generateRegistrationNumber()  ← runs GO pipeline, returns {registrationNumber, submissionId}
GET  /api/generateRegistrationNumber/{id}/pdf    → SubmissionPdfGenerator
```

### State machines
There are two separate state machines:

`SubmissionStatus` (GO pipeline, `submission.status`):
```
IN_PROCESSING → ACCEPTED (VALIDATION_PASSED) / REJECTED (VALIDATION_REJECTED)
```

`LessorApplicationStatus` (lessor registration review, `lessor.application_status`):
```
PENDING → ACCEPTED (admin approves)
PENDING → REJECTED (admin rejects)
```

`RnStatus` (registration number lifecycle) — suspension is **two-phase**: the party is first invited to respond (čl. 30. st. 2 ZUP), and only an expired deadline suspends:
```
IN_PROCESSING → ACTIVE (ISSUE)
ACTIVE → SUSPENSION_PROPOSED (CONSENT_EXPIRY / INSPECTION / INCOMPLETE_DOCUMENTATION)
SUSPENSION_PROPOSED → ACTIVE (REVOKE_PROPOSAL)        [party fixed the issue]
SUSPENSION_PROPOSED → SUSPENDED (DEADLINE_EXCEEDED)   [SuspensionDeadlineJob, daily]
SUSPENDED → ACTIVE (REACTIVATE)
ACTIVE / SUSPENSION_PROPOSED / SUSPENDED → WITHDRAWN (WITHDRAWAL)   [terminal — permanent, no reactivation]
```

All status changes go exclusively through `SubmissionStatusTransitionService.transition()` and `RnStatusTransitionService.transition()`. Each validates the transition against `canTransitionTo()` and immediately writes a `submission_log` / `registration_number_log` row — these two operations are inseparable. Never mutate the status field directly from service code.

Every `RnStatus` transition publishes `RnLifecycleEvent` (AFTER_COMMIT), consumed by two listeners that both dispatch through `StrDocumentType.forTransition()` — the single source of truth mapping a transition to its ZUP act. Adding an `RnStatus` or `RnTrigger` therefore requires: a branch in `forTransition` (or a deliberate decision that the transition produces no act), and Croatian labels in `documents/hr/labels.properties` — a missing label throws at render time, i.e. *after* the status has already changed. `DocumentLabelsTest` guards this.

Note: `SubmissionStatusTransitionService` is defined but not yet wired into any production call site — submissions remain in `IN_PROCESSING` indefinitely. Intentional for now; the GO pipeline will own the transition to `ACCEPTED`/`REJECTED` in a future iteration.

### GO validation pipeline
`ParallelValidationOrchestrator` runs `ValidationCheck` implementations in waves. Within a wave checks fan out in parallel; the next wave starts only after the previous completes (so `ValidationContext` flags set by upstream checks are visible without races). The first `Rejected` short-circuits the remaining checks.

Critical invariant: **GO-4 only runs when GO-2 sets the flag.** `Go2BuildingType` calls `context.markCoOwnerConsentRequired()` when the building has > 3 units; `Go4CoOwnerConsent` checks `context.requiresCoOwnerConsent()` before calling DGU. Do not change GO-4 to run unconditionally.

An `ExternalRegistryException` from MPGI or DGU propagates unhandled through the orchestrator — it is NOT a validation failure. It surfaces as 503 via `GlobalExceptionHandler`. This is intentional.

### Lessor snapshot
`LessorEntity` is largely immutable after creation (`updatable = false` on identity columns: name, address, email, username). Mutable fields are limited to contact details and `applicationStatus`. Use the static `LessorEntity.create()` / `createNonEu()` factories — no public no-arg constructor exposed for application code (protected for JPA).

### Registration number
Format `HR` + 18 decimal digits encoding county code, group code, type code, and 12 digits of randomness, validated by `RegistrationNumber` record (pattern `^HR\d{18}$`). Assigned only on transition to `RnStatus.ACTIVE`. Generation retries up to 5× checking uniqueness before insert — a `DataIntegrityViolationException` on concurrent collision returns 500 (rare, acceptable).

## Key Constraints

- `ddl-auto=none` always. Schema changes go in a new numbered Liquibase changeset under `db/changelog/changes/`.
- **Liquibase changesets are immutable once applied.** Never edit the content of an existing changeset file — doing so changes its checksum and Liquibase will refuse to start (`ValidationFailedException`). Any correction or improvement to an already-applied changeset must go into a new, higher-numbered changeset. This applies even to "harmless" changes like adding `IF EXISTS` or a defensive `UPDATE`.
- No field injection — constructor injection only.
- Read-only repository/service methods must carry `@Transactional(readOnly = true)`.
- All `@Table` annotations must declare `schema =` explicitly.
- Lombok is used (`@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)` on entities). No `@Data` or `@Builder` — entities use static factory methods for controlled construction.

## Workflow

Never run `git commit` or `git push` unless explicitly instructed. Always wait for confirmation before committing or pushing changes.

## Skills

Domain-specific review guides live in `.claude/skills/`. Use them via the `code-reviewer` skill for pre-PR checks. Notable skills:
- `str-domain-model` — submission/lessor/accommodation tables, UUID keys, enums, snapshot semantics
- `str-state-machine` — allowed SubmissionStatus / RnStatus transitions and triggers
- `str-validation-engine` — GO-1 through GO-5 sequencing rules
- `str-schema-strategy` — str (read-only) vs str_rn (read-write) boundary rules
- `str-external-integrations` — MPGI/DGU retry, circuit breaker, timeout semantics
