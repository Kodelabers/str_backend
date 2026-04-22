---
name: str-domain-model
description: STR domain model — sso/iznajmljivac tables, UUID keys, enums (ponuda, status), HR-registration number format, and snapshot semantics for accommodation-object registration.
---

# STR Domain Model

Domain rules for the STR (Smještajni Objekt) registration module. Use when modeling entities, writing DTOs, or persisting registration data.

## When to Activate

- Creating/modifying JPA entities or records under the `str` schema
- Generating `registracijski_broj` values
- Working with `sso` or `iznajmljivac` tables
- Interpreting or emitting `ponuda` / `status` enum values
- Copying core data into STR as an immutable snapshot

## Entities

### `str.sso` (Smještajni Objekt)
Registration entity extending `core.objekt`.

| Field | Type | Rule |
| :--- | :--- | :--- |
| `uuid_sso` | UUID | Primary Key, FK to `core.objekt` (shared identity) |
| `registracijski_broj` | VARCHAR(18), UNIQUE | `HR` + 8 random digits |
| `kapacitet_kreveta` | Integer | ≤ capacity in source rješenje (see GO-5) |
| `kapacitet_gostiju` | Integer | ≤ capacity in source rješenje (see GO-5) |
| `ponuda` | Enum | `DIO`, `CJELINA` |
| `kat` | String | Residential buildings only |
| `broj_stana` | String | Residential buildings only |
| `status` | Enum | `U_OBRADI`, `AKTIVAN`, `SUSPENDIRAN`, `POVUCEN` (+ `INICIIRAN`, `VALIDACIJA` during flow) |

### `str.iznajmljivac`
Immutable snapshot of the renter at registration time. Do **not** update from core after creation — create a new snapshot row if underlying data changes.

| Field | Type |
| :--- | :--- |
| `oib` | CHAR(11) |
| `naziv_prezime` | VARCHAR |
| `adresa_prebivalista` | VARCHAR |
| `is_domacin` | Boolean (set by GO-1) |

## Registration Number (`registracijski_broj`)

- Format: `HR` + 8 random digits → 10 chars total (column width 18 for future expansion).
- Must be UNIQUE. On generation, retry on collision rather than sequence.
- Only assigned after successful validation pipeline; never expose a pre-activation RB.

```java
public record RegistracijskiBroj(String value) {
  private static final Pattern PATTERN = Pattern.compile("^HR\\d{8}$");

  public RegistracijskiBroj {
    if (!PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid registracijski broj: " + value);
    }
  }
}
```

## DTOs

- Use Java **records** for all DTOs (per java-coding-standards).
- Separate request/response records from JPA entities — never expose entities across controller boundary.
- Use pattern matching on `status` / `ponuda` for enum switches.

## Snapshot Semantics

`iznajmljivac` is a **snapshot**, not a live reference. Implications:
- Do not add FK to `core` person tables — store the OIB and denormalized fields.
- Re-registration triggers a new snapshot; previous rows remain for audit.
- `is_domacin` is derived at GO-1 time; do not recompute lazily on read.
