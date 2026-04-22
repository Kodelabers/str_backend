---
name: str-schema-strategy
description: STR database schema strategy — core (read-only) vs str (read-write) separation, ddl-auto=none migration policy, and cross-schema access rules.
---

# STR Schema Strategy

Enforces the two-schema contract between `core` (shared matične podatke) and `str` (registration module). Use when writing SQL, JPA mappings, or migrations.

## When to Activate

- Writing any SQL, DDL, or migration script
- Defining a JPA `@Table(schema = ...)`
- Configuring datasources or Hibernate properties
- Reviewing a query that touches both schemas

## Hard Rules

1. **`core` is read-only.** No `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, or DDL against any `core.*` table. Ever. The backend user must lack write grants on `core`.
2. **`str` is owned exclusively by this backend.** No other service writes here.
3. **`spring.jpa.hibernate.ddl-auto=none`.** Hibernate must not generate or validate schema against the DB at runtime beyond mapping checks. All schema changes ship as hand-written SQL migration files.
4. **Cross-schema reads** are allowed — e.g., join `str.sso` ↔ `core.objekt` via shared `uuid`. Prefer narrow queries over eager JPA joins.

## JPA Mapping

Always specify schema explicitly; never rely on default search_path:

```java
@Entity
@Table(schema = "str", name = "sso")
public class SsoEntity { ... }

@Entity
@Table(schema = "core", name = "objekt")
@Immutable  // enforce read-only at ORM level as defense-in-depth
public class CoreObjektEntity { ... }
```

Mark all `core.*` entities `@Immutable` and expose only via repositories that declare `readOnly = true` transactions.

## Migrations

- Tool: hand-written SQL (no Flyway/Liquibase auto-generation from entities).
- Location: versioned migration files committed alongside code.
- Review rule: every migration touching `str` must include a rollback script or documented reason one is not feasible.
- Migrations against `core` are **out of scope** — raise with the core owners instead.

## Snapshot vs. Live Data

When STR needs core data that must survive future core edits (e.g., renter identity at the moment of registration), copy it into a `str` snapshot table (e.g., `str.iznajmljivac`). Do not rely on live `core` joins for audit-critical fields.

## Common Pitfalls

- Forgetting the schema prefix in native queries — breaks when `search_path` differs between envs.
- Adding an FK from `str` → `core` at the DB level — allowed, but confirm `ON DELETE` is `RESTRICT` (never cascade, since core is not ours to modify).
- Using `@GeneratedValue` against a `core` PK — never; `uuid_sso` is assigned from core, not generated in STR.
