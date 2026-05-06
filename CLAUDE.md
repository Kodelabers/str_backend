# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

Build tool is Maven. Run via IntelliJ ili terminalski:

```bash
mvn spring-boot:run                     # pokretanje (local profil aktivan po defaultu)
mvn test                                # svi testovi
mvn test -Dtest="Go5*"                 # jedan test razred
mvn compile                             # samo kompajlacija
mvn package                             # build fat JAR
```

## Environments & Profiles

Three Spring profiles — `local` (default), `test`, `prod`. The active profile is set via `SPRING_PROFILES_ACTIVE` env var; `application.properties` defaults to `local`.

| Profile | DB | Notes |
|---|---|---|
| `local` | `localhost:5432/str_db`, user `postgres` | `LocalDatabaseConfig` auto-creates the DB on startup if absent |
| `test` | real PostgreSQL via `TEST_DB_URL/USERNAME/PASSWORD` env vars | Liquibase runs migrations only (skips `core.objekt` creation) |
| `prod` | real PostgreSQL via `PROD_DB_URL/USERNAME/PASSWORD` env vars | same as test |

Unit tests (`@ActiveProfiles("test")`) use H2 from `src/test/resources/application-test.properties` — the test classpath file overrides the main one, so JUnit tests are unaffected by the real `test` env config.

Liquibase context `local` is set only in `application-local.properties`. Changeset `002-core-objekt.xml` carries `context="local"` and must never run against test/prod (that table is owned by the `core` service).

## Architecture

### Schema ownership
`core.*` is **read-only**. `CoreObjektEntity` carries `@Immutable` and `CoreObjektRepository` is `@Transactional(readOnly = true)`. No write path to `core` schema exists or should be added.

`str.*` is exclusively owned by this service. All `str` tables are managed by Liquibase.

### Registration flow
`SsoService` orchestrates the lifecycle. A registration (`sso`) record is keyed by the same UUID as its `core.objekt` row.

```
POST /api/sso/registracije          → SsoService.iniciraj()
POST /{uuid}/validacija             → SsoService.validiraj()   ← runs GO pipeline
POST /{uuid}/callback               → SsoService.potvrdiCallback()
POST /{uuid}/suspend?razlog=...     → SsoService.suspendiraj()
POST /{uuid}/povuci                 → SsoService.povuci()
```

### State machine
All `sso.status` changes go exclusively through `StatusTransitionService.transition()`. That method validates the transition against `Status.canTransitionTo()` and immediately writes an `audit_log` row — these two operations are inseparable. Never call `sso.applyStatus()` directly from service code.

```
INICIIRAN → VALIDACIJA → AKTIVAN
                       ↘ U_OBRADI → AKTIVAN
AKTIVAN → SUSPENDIRAN → VALIDACIJA (reactivation)
AKTIVAN / SUSPENDIRAN → POVUCEN (terminal)
```

### GO validation pipeline
`ValidacijskiOrkestrator` runs `ValidacijskaProvjera` implementations sorted by `order()` — sequential, no parallelism. Steps short-circuit on `Odbijena` or `CekaCallback`.

Critical invariant: **GO-4 only runs when GO-2 sets the flag.** GO-2 calls `kontekst.markiraj()` when the building has > 3 units; GO-4 checks `kontekst.zahtjevaSuglasnost()` before calling DGU. Do not change GO-4 to run unconditionally.

An `ExternalRegistryException` from MPGI or DGU propagates unhandled through the orchestrator — it is NOT a validation failure. It surfaces as 503 via `GlobalExceptionHandler`. This is intentional.

### Iznajmljivac snapshot
`IznajmljivacEntity` is immutable after creation (`updatable = false` on all identity columns). The only mutable field is `isDomacin`, which GO-1 sets during validation. Each re-validation fetches the latest snapshot via `findTopByUuidSsoOrderByCreatedAtDesc`.

### Registracijski broj
Format `HR` + 8 random digits, validated by `RegistracijskiBroj` record. Assigned only on transition to `AKTIVAN`. Generation retries up to 5× checking uniqueness before insert — a `DataIntegrityViolationException` on concurrent collision returns 500 (rare, acceptable).

## Key Constraints

- `ddl-auto=none` always. Schema changes go in a new numbered Liquibase changeset under `db/changelog/changes/`.
- No field injection — constructor injection only.
- Read-only repository/service methods must carry `@Transactional(readOnly = true)`.
- All `@Table` annotations must declare `schema =` explicitly.
- Lombok is used (`@Getter`, `@NoArgsConstructor` on entities). No `@Data` or `@Builder` — entities use static factory methods for controlled construction.

## Workflow

Never run `git commit` or `git push` unless explicitly instructed. Always wait for confirmation before committing or pushing changes.

## Skills

Domain-specific review guides live in `.claude/skills/`. Use them via the `code-reviewer` skill for pre-PR checks. Notable skills:
- `str-domain-model` — SSO/iznajmljivac tables, UUID keys, enums, snapshot semantics
- `str-state-machine` — allowed transitions and triggers
- `str-validation-engine` — GO-1 through GO-5 sequencing rules
- `str-schema-strategy` — core (read-only) vs str (read-write) boundary rules
- `str-external-integrations` — MPGI/DGU retry, circuit breaker, timeout semantics
