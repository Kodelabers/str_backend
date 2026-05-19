---
name: str-domain-model
description: STR domain model — submission/lessor/accommodation tables, UUID keys, enums (Offering, SubmissionStatus, RnStatus), HR-registration number format, and snapshot semantics.
---

# STR Domain Model

Domain rules for the STR (Short-Term Rental) registration module. Use when modeling entities, writing DTOs, or persisting registration data.

## When to Activate

- Creating/modifying JPA entities or records under the `str_rn` schema
- Generating `registration_number` values
- Working with `submission`, `lessor`, `accommodation`, or `registration_number` tables
- Interpreting or emitting `Offering` / `SubmissionStatus` / `RnStatus` enum values
- Copying lessor data into the registration as an immutable snapshot

## Entities

### `str_rn.submission`
Per-request entity capturing the lifecycle of a registration request.

| Field | Type | Rule |
| :--- | :--- | :--- |
| `submission_id` | UUID | Primary Key |
| `channel` | Enum | `NIAS`, `EIDAS`, `FOREIGN` (default `NIAS`) |
| `submission_type_id` | Long | FK to `submission_type` |
| `status` | Enum | `INITIATED`, `IN_VERIFICATION`, `IN_PROCESSING`, `ACCEPTED`, `REJECTED` |
| `filing_date` | Instant | Set on filing |
| `pdf_content` | byte[] | Serialized PDF blob, populated on ACCEPTED |

### `str_rn.lessor`
Largely immutable snapshot of the lessor at registration time. Identity columns (name, address, email, username) carry `updatable = false`. Mutable fields are limited to contact details, legal-entity data, and `application_status`. Use `LessorEntity.create()` / `createNonEu()` factories — no public no-arg constructor.

| Field | Type |
| :--- | :--- |
| `lessor_id` | UUID, PK |
| `lessor_oib` | CHAR(11) |
| `first_name`, `last_name` | VARCHAR, immutable |
| `street`, `street_number`, `place`, `county` | VARCHAR, immutable |
| `email`, `username` | VARCHAR, immutable |
| `password_hash` | VARCHAR (mutable — for non-EU credentials reset) |

### `str_rn.accommodation`
The accommodation being registered. Carries `offering` (Offering enum: `PART`, `WHOLE`), capacity fields, address fields, and FK to `accommodation_type`.

### `str_rn.accommodation_type`
Lookup of accommodation types. Note: column is `group_name` (not `group` — SQL reserved word). Java field is `group`.

## Registration Number

- Format: `HR` + 18 hex digits → 20 chars total. Pattern: `^HR[0-9A-Fa-f]{18}$`.
- Encodes: county code (2 hex), group code (2 hex), type code (2 hex), 12 hex random.
- Must be UNIQUE. On generation, retry up to 5× on collision rather than sequence.
- Only assigned on transition to `RnStatus.ACTIVE`; never expose a pre-activation RN.

```java
public class RegistrationNumber {
  private static final Pattern PATTERN = Pattern.compile("^HR[0-9A-Fa-f]{18}$");

  public RegistrationNumber(String value) {
    if (value == null || !PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid registration number: " + value);
    }
    this.value = value;
  }

  public static RegistrationNumber generate(int countyCode, int groupCode, int typeCode) { ... }
}
```

## DTOs

- Use Java **records** for all DTOs (per java-coding-standards).
- Separate request/response records from JPA entities — never expose entities across controller boundary.
- Use pattern matching on `status` / `offering` for enum switches.

## Snapshot Semantics

`lessor` is a **snapshot**, not a live reference. Implications:
- Do not add FK to external `str.subject` tables — store OIB and denormalized fields.
- Re-registration creates a new snapshot row; previous rows remain for audit.
- `applicationStatus` is set during validation; do not recompute lazily on read.
