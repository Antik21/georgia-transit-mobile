# ADR 0003: JVM Transit BFF runtime and provider boundary

Status: Accepted (2026-09-07)

## Context

The Android and iOS clients must consume one normalized Georgia Transit API,
while city operators expose different contracts, data freshness, identifiers,
and credentials. Provider contracts and credentials cannot enter `shared`, the
native hosts, application configuration, or version control. The BFF needs a
small local development path without ever mistaking synthetic data for a real
provider integration.

Directory data changes slowly, but nearby-stop viewports may refresh often.
Realtime observations and journey planning can cause duplicate upstream calls
when clients refresh together. The BFF therefore needs bounded cache and
request-coalescing behaviour alongside a clear normalized public contract.

## Decision

Create a separate Java 17 Kotlin/JVM `:transitBff` Gradle application module.
It uses Kotlin 2.4.10, Ktor 3.3.3 Netty, and kotlinx serialization, all under
their Apache-2.0 licenses with no mandatory production API fee. Versions and
aliases remain centralized in `gradle/libs.versions.toml`. The module is not a
dependency of `shared`, Android, or iOS and introduces no mobile DI, network,
serialization, state, or persistence stack.

The public contract is versioned under `/v1` and documented in
[`docs/openapi/transit-bff-v1.yaml`](../openapi/transit-bff-v1.yaml). It carries
only normalized serializable DTOs, WGS84 coordinates, opaque public entity IDs,
UTC timestamps, city capabilities, and a consistent request-ID/error envelope.
The BFF accepts only safe `X-Request-ID` values and otherwise generates an
opaque ID. It maps typed provider failures to the documented 400, 404, 409,
429, 501, 502, 503, and 504 responses. Arrival timing distinguishes schedule,
expected timestamp/minutes, realtime state, cancellation state, and its
normalized source rather than implying certainty.

Each city is owned by one `CityTransitProviderAdapter`. Only that adapter may
know a provider DTO or credential; it maps to normalized models before the
service layer sees it. `ProviderRegistry` exposes capabilities from configured
adapters. Kutaisi and Batumi have no adapter and are absent; an absent city
returns `CITY_NOT_FOUND`. Trip planning is explicitly capability-gated for
every configured city until a reviewed adapter supports it.

The server caches city, route list, route detail, stop directories, and shapes
with coroutine-safe TTL caches. Directory TTL is configurable from one to six
hours; shapes may have a separate one-to-24-hour TTL. Nearby stops always filter
the cached local directory with Haversine distance rather than making a
provider call per viewport. Realtime vehicle, arrival, and journey requests use
bounded, cancellation-safe single-flight work: cancelling one HTTP caller does
not cancel another caller's shared fetch, timeout is bounded, entries are
removed at completion, and server shutdown cancels the scope.

`DemoFixtureTransitProviderAdapter` is an unmistakably synthetic `demo` city
and is registered only when both `BFF_MODE=development` and
`BFF_FIXTURES_ENABLED=true`. Fixture enablement in production is invalid.
Production startup deliberately fails closed while there are no real,
reviewed adapters; it never falls back to fixtures. Health exposes only
readiness and runtime mode, never URLs, credentials, or provider diagnostics.

## Consequences

- The repository gets a local BFF build, check, distribution, OpenAPI, and CI
  surface, while no direct mobile-provider traffic or secret is introduced.
- A future provider needs an adapter implementation, credential delivery from
  an operator secret manager, contract verification, capability decision, and
  city-specific operational review before it can be registered in production.
- Cache TTLs trade currentness for bounded upstream traffic. Realtime pages
  expose observed time, maximum age, and stale state rather than pretending
  cached data is live.
- The fixture can exercise every endpoint locally, but must not be cited as
  real service or provider readiness.
- No Docker image is included in this decision: `installDist` is the supported
  deployment artifact until target hosting and operational controls are
  selected. Operators still own compute, egress, monitoring, backups, abuse
  control, provider costs, and credentials.

## Alternatives

- **Direct provider clients in Android/iOS:** rejected because it exposes
  provider contract, credentials, quota, and privacy policy to mobile clients.
- **A Spring/Node server or a second JVM framework:** rejected because Ktor
  keeps the minimal Kotlin toolchain and avoids a parallel server stack.
- **Generic provider DTOs in the public API:** rejected because it couples the
  app release cycle to every city provider and makes IDs non-portable.
- **No cache or per-viewport nearby provider calls:** rejected because it
  multiplies upstream traffic and cannot bound viewport refresh cost.
- **Fixtures available in production as a fallback:** rejected because it
  disguises a provider outage as valid transit data.

## Verification

```text
./gradlew :transitBff:compileKotlin
./gradlew spotlessCheck :transitBff:check :transitBff:installDist
BFF_MODE=development BFF_FIXTURES_ENABLED=true ./gradlew :transitBff:run
curl -i http://127.0.0.1:8080/healthz
curl -i 'http://127.0.0.1:8080/v1/cities/demo/routes?locale=en'
```

Before a production adapter is enabled, verify all documented status mappings,
safe request IDs, cache expiry, ETags, cancellation, malformed input, WGS84
bounds, city capabilities, no secrets in logs/responses, and real provider
licence/cost/quota/attribution obligations. Build Android and iOS independently
when their future clients consume this contract.
