---
name: str-schema-strategy
description: STR database schema strategy — str (external, read-only) vs str_rn (this service, read-write) separation, ddl-auto=none migration policy, and cross-schema access rules.
---

# STR Schema Strategy

Enforces the two-schema contract between `str` (externally owned reference data) and `str_rn` (this service's tables). Use when writing SQL, JPA mappings, or migrations.

## When to Activate

- Writing any SQL, DDL, or migration script
- Defining a JPA `@Table(schema = ...)`
- Configuring datasources or Hibernate properties
- Reviewing a query that touches both schemas

## Hard Rules

1. **`str` is read-only.** No `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, or DDL against any `str.*` table from this service on dev/prod. On local/mock the schema is mocked via Liquibase changesets gated by `context="local"`.
2. **`str_rn` is owned exclusively by this backend.** No other service writes here.
3. **`spring.jpa.hibernate.ddl-auto=none`.** Hibernate must not generate or validate schema against the DB beyond mapping checks. All schema changes ship as Liquibase changesets.
4. **Cross-schema reads** are allowed — e.g., join `str_rn.lessor` ↔ `str.subject` via shared OIB. Prefer narrow queries over eager JPA joins.

## JPA Mapping

Always specify schema explicitly; never rely on default search_path:

```java
@Entity
@Table(schema = "str_rn", name = "submission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionEntity { ... }

@Entity
@Table(schema = "str", name = "subject")
@Immutable  // enforce read-only at ORM level as defense-in-depth
public class StrSubjectEntity { ... }
```

Mark all `str.*` entities `@Immutable` and expose only via repositories that declare `readOnly = true` transactions.

## Migrations

- Tool: Liquibase XML changesets under `src/main/resources/db/changelog/changes/`. Numbered (`030-...`, `031-...`).
- Mock `str.*` schema and seeds are gated by `context="local"`, activated via `spring.liquibase.contexts=local` in `application-local.properties` and `application-mock.properties`.
- Review rule: every changeset touching `str_rn` must include a `<rollback>` or documented reason one is not feasible. Seed-only changesets can use `<rollback/>` (empty).
- Changes against `str` are **out of scope** — that schema is owned externally.

## Snapshot vs. Live Data

When `str_rn` needs reference data that must survive future external edits (e.g., lessor identity at the moment of registration), copy it into a `str_rn` snapshot table (e.g., `str_rn.lessor`). Do not rely on live `str` joins for audit-critical fields.

## Common Pitfalls

- Forgetting the schema prefix in native queries — breaks when `search_path` differs between envs.
- Using SQL reserved words as column names — e.g., `group_name` not `group` for accommodation type (Java field can still be `group`).
- Using `@GeneratedValue` against a PK that originates externally — assigned values must be set explicitly.
