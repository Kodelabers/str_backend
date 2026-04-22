---
name: str-state-machine
description: STR registration status transitions — INICIIRAN → VALIDACIJA → U_OBRADI → AKTIVAN, with SUSPENDIRAN/POVUCEN terminals and allowed triggers.
---

# STR Status State Machine

Defines legal transitions for `str.sso.status`. Use when writing code that changes status, gating actions on status, or auditing why an SSO is in a given state.

## When to Activate

- Any code path that writes `sso.status`
- Building guards/filters that read status
- Implementing suspension, withdrawal, or reactivation flows
- Writing tests that assert terminal or intermediate status

## Transition Table

| From | Trigger | To |
| :--- | :--- | :--- |
| — | Core data fetched, draft created | `INICIIRAN` |
| `INICIIRAN` | User submits fields → pipeline starts | `VALIDACIJA` |
| `VALIDACIJA` | GO-1..GO-5 all pass + signature confirmed | `AKTIVAN` |
| `VALIDACIJA` | GO-4 pending (missing co-owner consent) or signature pending | `U_OBRADI` |
| `U_OBRADI` | Callback confirms consent/signature | `AKTIVAN` |
| `AKTIVAN` | Expiry of consent or inspection action | `SUSPENDIRAN` |
| `AKTIVAN` / `SUSPENDIRAN` | Owner/admin withdrawal | `POVUCEN` |

## Rules

- `AKTIVAN` is the **only** status with SDEP visibility. All other statuses must be filtered out of public-facing reads.
- `POVUCEN` is terminal. Do not resurrect — create a new registration.
- `SUSPENDIRAN` is reversible via a fresh validation cycle (return to `VALIDACIJA`); do not transition directly back to `AKTIVAN`.
- `VALIDACIJA` is transient. Never persist long-term — a stuck `VALIDACIJA` row is a bug, not a valid state.
- `U_OBRADI` requires a pending external callback. Store the expected callback type (consent | signature) alongside status so resumption is unambiguous.

## Guard Pattern

Gate transitions through a single service method; reject illegal transitions with a domain exception rather than silently ignoring:

```java
void transition(UUID uuidSso, Status target, TransitionTrigger trigger) {
  Status current = load(uuidSso).status();
  if (!current.canTransitionTo(target, trigger)) {
    throw new IllegalStatusTransitionException(current, target, trigger);
  }
  // persist + audit
}
```

Encode the allowed transitions on the `Status` enum itself — pattern-match the `(current, target, trigger)` triple instead of sprinkling `if` ladders across services.

## Audit

Every transition writes an audit row (old status, new status, trigger, actor, timestamp) to the `str` schema. Status changes with no audit row are not permitted.
