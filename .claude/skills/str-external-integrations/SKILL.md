---
name: str-external-integrations
description: STR external registry integrations — core (read-only), MPGI, and DGU calls with retry, circuit breaker, timeout handling, and failure semantics for the GO pipeline.
---

# STR External Integrations

Patterns for calling the three external registries used by the GO validation pipeline. Use when implementing or modifying any call to `core`, MPGI, or DGU.

## When to Activate

- Implementing a GO step that calls an external registry
- Configuring timeouts, retries, or circuit breakers
- Deciding how to handle a registry being unavailable
- Writing tests for external-registry failure cases

## Registries

| Registry | Used by | Access |
| :--- | :--- | :--- |
| `core` DB | All GO steps (read object/rješenje data) | Read-only JPA/JDBC |
| MPGI | GO-2 (building units), GO-3 (legality/usage permit) | HTTP REST |
| DGU | GO-4 (co-owner consent validity) | HTTP REST |

## Failure Semantics (Critical)

**A registry timeout or unavailability is NOT a validation failure.**

| Scenario | Correct Behaviour |
| :--- | :--- |
| Registry times out | Retry → circuit open → park SSO in `U_OBRADI`, schedule retry |
| Registry returns invalid data / unexpected format | Log + throw `ExternalRegistryException`, do not advance pipeline |
| GO-3 registry confirms "not legal" | Hard fail — this is a domain rejection, not a technical failure |
| GO-4 consent absent | Park in `U_OBRADI` — not a technical failure |

Never let an infrastructure problem look like a business rejection to the user.

## Resilience Wrapper

Wrap every external HTTP call with retry + circuit breaker. Use Resilience4j:

```java
@Service
public class MpgiClient {
  private final RestClient restClient;

  @CircuitBreaker(name = "mpgi", fallbackMethod = "fallback")
  @Retry(name = "mpgi")
  public MpgiBuildingInfo getBuildingInfo(String adresa) {
    return restClient.get()
        .uri("/zgrade/{adresa}", adresa)
        .retrieve()
        .body(MpgiBuildingInfo.class);
  }

  private MpgiBuildingInfo fallback(String adresa, Throwable ex) {
    throw new ExternalRegistryUnavailableException("MPGI", adresa, ex);
  }
}
```

Configure in `application.yml`:
```yaml
resilience4j:
  retry:
    instances:
      mpgi:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
  circuitbreaker:
    instances:
      mpgi:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

## Core Read Access

`core` is accessed via JPA, not HTTP. Key rules:
- All `core.*` entities annotated `@Immutable`.
- Repositories use `@Transactional(readOnly = true)`.
- Never call `save()`, `delete()`, or native update/insert against `core`.
- Use narrow projections — never `SELECT *` from large `core` tables.

## Virtual Threads (Project Loom)

External calls are I/O-bound — they benefit from virtual threads. With Spring Boot 3.2+ and `spring.threads.virtual.enabled=true`, `@Async` and thread-pool-bound RestClient calls automatically use virtual threads. Do not create custom thread pools for registry calls unless profiling shows contention.

## Audit Logging

Every registry call must log:
- Registry name, operation, input key (adresa / OIB / uuid)
- Outcome (success / timeout / rejection)
- Duration in ms
- Correlation ID (from request context)

Never log PII (full OIB, full address) at INFO level — use DEBUG or mask.

## Testing

- Unit-test GO steps against a stub registry client — inject the interface, not the HTTP client.
- Write one integration test per registry using WireMock to assert retry and circuit-breaker behaviour.
- Always test the timeout → `U_OBRADI` path, not just the happy path.
