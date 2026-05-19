---
name: str-validation-engine
description: STR validation engine — GO-1 through GO-5 checks (host status, building type, legality, co-owner consent, capacity) running in parallel waves, gating registration activation.
---

# STR Validation Engine (Grupne Obrade)

Wave-parallel validation pipeline (GO-1 through GO-5) that must complete before a `registration_number` activates. Use whenever adding, reordering, or changing a validation step.

## When to Activate

- Implementing any of GO-1 through GO-5 (`Go1HostStatus`, `Go2BuildingType`, `Go3LegalityCheck`, `Go4CoOwnerConsent`, `Go5CapacityCheck`)
- Adding new validation logic to the registration flow
- Debugging why a submission is stuck in `IN_PROCESSING`
- Integrating with eGOP, MPGI, or DGU registries for checks

## Pipeline Rules

1. `ParallelValidationOrchestrator` runs checks in **waves**. Independent checks fan out in parallel; checks with `dependsOn()` start only after their predecessors complete.
2. Each wave finishes before the next starts — `ValidationContext` flags set by upstream checks (e.g., GO-2 → GO-4) are visible to downstream checks without races.
3. The first `Rejected` within a wave short-circuits remaining checks of that wave.
4. All checks must pass before the submission transitions to `ACCEPTED`.
5. An `ExternalRegistryException` from MPGI / DGU propagates as 503, NOT as a validation failure.

## GO-1: Host Status (`Go1HostStatus`)

- **Compare**: Lessor's residence county (`LessorEntity.county`) vs. accommodation's county.
- **Rule**: Host (`is_domacin`) requires county match AND accommodation NOT classified as a building.
- **Writes**: Updates lessor's host flag for tax/regulatory treatment downstream.

## GO-2: Building Type (`Go2BuildingType`)

- **Source**: MPGI registry (`MpgiClient.brojStambenihJedinica()`) — number of residential units at the address.
- **Rule**: If units **> 3**, classify as apartment in a multi-unit building → flag context via `ctx.markCoOwnerConsentRequired()` so GO-4 runs.
- Skips MPGI call entirely when accommodation is not a building, or is a building but not apartments.

## GO-3: Legality Check (`Go3LegalityCheck`)

- **Source**: Accommodation's `legalized` flag (sourced from eGOP / building registry).
- **Rule**: If not legalized → reject. Hard fail.

## GO-4: Co-owner Consent (`Go4CoOwnerConsent`)

- **Source**: DGU registry — digital co-owner consent presence + validity dates.
- **Gate**: Runs only if GO-2 set `ctx.requiresCoOwnerConsent()` (true when units > 3).
- **Rule**: Missing consent, consent flag false, or withdrawal date today/past → reject. Open-ended consent (no expiry) passes.

## GO-5: Capacity Check (`Go5CapacityCheck`)

- **Compare**: Submitted bed/guest counts vs. maxima in the source rješenje o kategorizaciji.
- Currently passes always (placeholder for future capacity reconciliation against external registry data).

## Implementation Notes

- Each GO is a Spring `@Component` implementing `ValidationCheck`. Declare ordering with `order()` and dependencies via `dependsOn()`.
- Every GO **must** be idempotent — re-running on the same accommodation must not change outcomes unless inputs have changed.
- External-registry calls must be wrapped with retry + circuit breaker; a registry timeout is not a validation failure, it surfaces as 503.
- Do not mutate external schema data — STR is read-only against `core` / eGOP / MPGI / DGU.
- Validation log entries go to slf4j only (no DB row). Once a submission exists in `IN_PROCESSING` and a pipeline rejection is recorded, a transition log entry to `REJECTED` is written via `SubmissionStatusTransitionService`.
