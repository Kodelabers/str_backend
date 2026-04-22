---
name: str-validation-engine
description: STR validation engine — sequential GO-1 through GO-5 checks (host status, building type, legality, co-owner consent, capacity) gating registration activation.
---

# STR Validation Engine (Grupne Obrade)

Sequential validation pipeline (GO-1 → GO-5) that must complete before a `registracijski_broj` activates. Use whenever adding, reordering, or changing a validation step.

## When to Activate

- Implementing any of GO-1 through GO-5
- Adding new validation logic to the registration flow
- Debugging why an `sso` is stuck in `VALIDACIJA` or `U_OBRADI`
- Integrating with `core`, MPGI, or DGU registries for checks

## Pipeline Rules

1. Execute sequentially. Do **not** parallelize — GO-2 decides whether GO-4 runs.
2. A failing GO short-circuits the pipeline (except GO-4, which parks status in `U_OBRADI`).
3. All 5 must pass before transitioning to `AKTIVAN`.
4. Each GO writes an audit log entry to the `str` schema capturing inputs, outcome, and timestamp.

## GO-1: Status Domaćina (Host Verification)

- **Compare**: JLS (županija/grad) from `iznajmljivac.adresa_prebivalista` vs. location of `core.objekt`.
- **Writes**: `iznajmljivac.is_domacin` (Boolean).
- **Effect**: Determines tax/regulatory treatment downstream — never skip.

## GO-2: Tip Građevine (Building Context)

- **Source**: MPGI registar — number of residential units at the address.
- **Rule**: If units **> 3**, classify as "stan u zgradi" → **GO-4 becomes mandatory**.
- **Persist**: Store the classification; GO-4 reads it as its gate.

## GO-3: Legalnost Objekta (Legality Check)

- **Source**: `core` rješenje status + MPGI akt o uporabi.
- **Rule**: If not "Legalan" → **block**, emit user-visible error, do not continue pipeline.
- This is a hard fail: no `U_OBRADI` fallback.

## GO-4: Suglasnost Suvlasnika (Co-owner Consent)

- **Source**: DGU registar — digital co-owner consent presence + validity.
- **Gate**: Runs only if GO-2 flagged the object.
- **Rule**: Missing/invalid consent → status stays `U_OBRADI`, awaiting callback confirmation. Do not fail the pipeline; leave the SSO parked.

## GO-5: Provjera Kapaciteta (Capacity Audit)

- **Compare**: submitted `kapacitet_kreveta` / `kapacitet_gostiju` vs. maximums in source `core` rješenje o kategorizaciji.
- **Rule**: Submitted values must not exceed rješenje maxima. Exceeding → reject, return to user for correction.

## Implementation Notes

- Model each GO as a named bean implementing a common interface (e.g., `ValidacijskaProvjera`). Orchestrate in a single service that owns the sequence and state transitions.
- Every GO **must** be idempotent — re-running on the same `uuid_sso` must not duplicate audit rows or change outcomes unless inputs have changed.
- External-registry calls (MPGI, DGU, core) must be wrapped with retry + circuit breaker; a registry timeout is not a validation failure.
- Do not mutate `core` data — STR is read-only against `core`.
