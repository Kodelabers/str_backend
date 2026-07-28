---
name: str-state-machine
description: STR registration status transitions — two state machines (SubmissionStatus and RnStatus) with allowed triggers and per-aggregate audit logs.
---

# STR Status State Machines

Defines legal transitions for `submission.status` (SubmissionStatus) and `registration_number.status` (RnStatus). Use when writing code that changes status, gating actions on status, or auditing why a record is in a given state.

## When to Activate

- Any code path that writes `submission.status` or `registration_number.status`
- Building guards/filters that read status
- Implementing suspension, withdrawal, or reactivation flows
- Writing tests that assert terminal or intermediate status

## Submission Lifecycle (`SubmissionStatus`)

| From | Trigger | To |
| :--- | :--- | :--- |
| — | New submission created | `IN_PROCESSING` |
| `IN_PROCESSING` | `VALIDATION_PASSED` | `ACCEPTED` (terminal) |
| `IN_PROCESSING` | `VALIDATION_REJECTED` | `REJECTED` (terminal) |


## Registration Number Lifecycle (`RnStatus`)

| From | Trigger | To |
| :--- | :--- | :--- |
| — | RN issued when submission ACCEPTED | `IN_PROCESSING` |
| `IN_PROCESSING` | `ISSUE` | `ACTIVE` |
| `ACTIVE` | `CONSENT_EXPIRY` / `INSPECTION` / `INCOMPLETE_DOCUMENTATION` | `SUSPENSION_PROPOSED` |
| `SUSPENSION_PROPOSED` | `REVOKE_PROPOSAL` | `ACTIVE` |
| `SUSPENSION_PROPOSED` | `DEADLINE_EXCEEDED` | `SUSPENDED` |
| `SUSPENDED` | `REACTIVATE` | `ACTIVE` |
| `ACTIVE` / `SUSPENSION_PROPOSED` / `SUSPENDED` | `WITHDRAWAL` | `WITHDRAWN` |
| `WITHDRAWN` | — (terminal) | — |

Suspension is **two-phase**: `suspend()` only *proposes* it. The party is invited to respond within `suspension_deadline` (čl. 30. st. 2 ZUP); `SuspensionDeadlineJob` (daily) performs `DEADLINE_EXCEEDED` when the deadline passes unanswered. Never transition `ACTIVE → SUSPENDED` directly.

## Documents

Each transition maps to a ZUP act through `StrDocumentType.forTransition(to, trigger, byLessor)` — one function shared by the eGOP filing listener and the e-mail listener, so document and message can never diverge. Adding a status or trigger means:

1. a branch in `forTransition` (or a deliberate decision that it produces no act — issuance is the one such case, handled by the registration flow);
2. Croatian labels in `documents/hr/labels.properties` for the new `RnStatus` / `RnTrigger`. `ZupContextFactory` resolves the status label on **every** render and a missing key throws — after the status has already changed. `DocumentLabelsTest` guards this.

`WITHDRAWAL` produces two different acts: `OPOZIV` when the lessor initiated it (actor prefix `LESSOR:` or `NIAS:`), `POVLACENJE` ex officio.

## Rules

- `RnStatus.ACTIVE` is the **only** publicly visible state (see `RnStatus.isPubliclyVisible()`). All others must be filtered out of public-facing reads.
- `SubmissionStatus.ACCEPTED` / `REJECTED` are terminal — `isTerminal()` returns true. Do not transition further.
- `IN_PROCESSING` is transient. A stuck `IN_PROCESSING` row indicates an interrupted pipeline run, not a valid steady state.
- `IN_VERIFICATION` requires a pending referent action (foreign upload review). It is the only non-NIAS entry into processing.

## Guard Pattern

All transitions go through `SubmissionStatusTransitionService.transition()` and `RnStatusTransitionService.transition()`. These validate the `(current, target, trigger)` triple via `canTransitionTo()` and immediately write a `submission_log` / `registration_number_log` row. These two operations are inseparable — never mutate the status field directly from service code.

```java
void transition(UUID submissionId, SubmissionStatus target, SubmissionTrigger trigger) {
  SubmissionStatus current = submission.getStatus();
  if (!current.canTransitionTo(target, trigger)) {
    throw new IllegalStatusTransitionException(current, target, trigger);
  }
  submission.applyStatus(target);
  submissionLogRepository.save(SubmissionLogEntity.transition(submissionId, current, target, trigger, actor));
}
```

Encode the allowed transitions on the enum itself — pattern-match the `(current, target, trigger)` triple inside `canTransitionTo()` rather than sprinkling `if` ladders across services.

## Audit

Every transition writes a row to the per-aggregate log table:
- Submission transitions → `submission_log` (event_type `STATUS_TRANSITION`).
- Registration-number transitions → `registration_number_log`.

Status changes with no log row are not permitted. There is no longer a generic `audit_log` table — domain-specific log tables align with the PDF "LOG ZAHTJEVA IZNAJMLJIVAČ" spec.
