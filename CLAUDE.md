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
| `local` | `localhost:5432/str_db_local`, user `postgres` | `LocalDatabaseConfig` auto-creates the DB **and `str_rn` schema** before Liquibase runs; Liquibase runs `context=local` (mock `str` schema + 8 test lessors). Password via `LOCAL_DB_PASSWORD` env var (default `postgres`) |
| `mock` | Railway PostgreSQL via `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD` | DB provisioned by Railway; `LocalDatabaseConfig` only creates `str_rn` schema; Liquibase runs `context=local` (same mock `str` schema + seed). Set `SPRING_PROFILES_ACTIVE=mock` in Railway service env vars. |
| `dev` | shared dev PostgreSQL `s-str-02.infodom.hr:5431/str2` | `str` schema is owned by another service — read-only; Liquibase only manages `str_rn` |
| `test` | real PostgreSQL via `TEST_DB_URL/USERNAME/PASSWORD` env vars | Liquibase runs migrations only |
| `prod` | real PostgreSQL via `PROD_DB_URL/USERNAME/PASSWORD` env vars | same as test |

Unit tests (`@ActiveProfiles("test")`) use H2 from `src/test/resources/application-test.properties` — the test classpath file overrides the main one, so JUnit tests are unaffected by the real `test` env config.

The `str` schema (subject, subject_version, subject_address, address, county, municipality, settlement, street, house_number) is owned externally. On `dev`/`prod` we read from the real registry. On `local` we mock those tables via Liquibase changesets `100-str-schema-local.xml` and `101-str-seed-local.xml`, both gated by `context="local"` and activated via `spring.liquibase.contexts=local` in `application-local.properties`.

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

`RnStatus` (registration number lifecycle):
```
IN_PROCESSING → ACTIVE (ISSUE)
ACTIVE → SUSPENDED (CONSENT_EXPIRY / INSPECTION) → ACTIVE (REACTIVATE)
ACTIVE / SUSPENDED → WITHDRAWN (WITHDRAWAL) → ACTIVE (REACTIVATE)
```

All status changes go exclusively through `SubmissionStatusTransitionService.transition()` and `RnStatusTransitionService.transition()`. Each validates the transition against `canTransitionTo()` and immediately writes a `submission_log` / `registration_number_log` row — these two operations are inseparable. Never mutate the status field directly from service code.

Note: `SubmissionStatusTransitionService` is defined but not yet wired into any production call site — submissions remain in `IN_PROCESSING` indefinitely. Intentional for now; the GO pipeline will own the transition to `ACCEPTED`/`REJECTED` in a future iteration.

### GO validation pipeline
`ParallelValidationOrchestrator` runs `ValidationCheck` implementations in waves. Within a wave checks fan out in parallel; the next wave starts only after the previous completes (so `ValidationContext` flags set by upstream checks are visible without races). The first `Rejected` short-circuits the remaining checks.

Critical invariant: **GO-4 only runs when GO-2 sets the flag.** `Go2BuildingType` calls `context.markCoOwnerConsentRequired()` when the building has > 3 units; `Go4CoOwnerConsent` checks `context.requiresCoOwnerConsent()` before calling DGU. Do not change GO-4 to run unconditionally.

An `ExternalRegistryException` from MPGI or DGU propagates unhandled through the orchestrator — it is NOT a validation failure. It surfaces as 503 via `GlobalExceptionHandler`. This is intentional.

### Lessor snapshot
`LessorEntity` is largely immutable after creation (`updatable = false` on identity columns: name, address, email, username). Mutable fields are limited to contact details and `applicationStatus`. Use the static `LessorEntity.create()` / `createNonEu()` factories — no public no-arg constructor exposed for application code (protected for JPA).

### Registration number
Format `HR` + 18 hex digits encoding county code, group code, type code, and 12 hex of randomness, validated by `RegistrationNumber` record (pattern `^HR[0-9A-Fa-f]{18}$`). Assigned only on transition to `RnStatus.ACTIVE`. Generation retries up to 5× checking uniqueness before insert — a `DataIntegrityViolationException` on concurrent collision returns 500 (rare, acceptable).

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
