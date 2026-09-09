# ADR 0005: Mobile Transit BFF client and offline catalog cache

Status: Accepted (2026-09-07)

## Context

Android and iOS need a production path to the normalized Transit BFF described
by the published `/v1` OpenAPI without acquiring provider contracts,
credentials, endpoints, or provider-specific identifier semantics. Directory
data must remain useful during temporary BFF outages, but mobile must not imply
that old or unavailable realtime data is current.

The existing shared KMP networking, serialization, DI, and native key-value
adapter boundaries are sufficient. A general database/cache stack would expand
the product architecture before a broader offline product is defined.

## Decision

Mobile speaks only the normalized Transit BFF. Provider DTOs, URLs, headers,
credentials, and secrets remain server-side. Namespaced BFF IDs are opaque
typed values: the client validates their OpenAPI shape at the mapping boundary
but never splits, derives, or rebuilds them.

Use the existing Ktor 3.3.3 client, kotlinx serialization, Koin composition,
and OkHttp/Darwin actuals. The data-layer client implements all stable `/v1`
endpoints, applies a five-second connect limit and 8/20-second realtime or
directory/planner request limits, and retries GET only twice for transport
timeouts/connectivity and HTTP 408, 429, 502, 503, and 504. Retries use bounded
exponential backoff plus injected jitter, respect a bounded `Retry-After`, and
dispose retry responses. Documented error envelopes and malformed/undocumented
responses map to typed domain failures; cancellation is always rethrown.

A release host may supply only an explicit HTTPS BFF endpoint; the current
release supplies none and therefore fails closed with a typed configuration
result, never falling back to preview or provider traffic. Debug composition
alone may use
`http://10.0.2.2:8080` on the Android emulator or `http://127.0.0.1:8080` on
the iOS simulator. Android's debug-only network-security configuration permits
cleartext only for `10.0.2.2`; the iOS Debug plist alone permits the loopback
ATS exception. Release has neither exception.

Durable mobile cache is deliberately limited to the city/capability snapshot
and route-list catalogs. Entries use schema version 1. The city/capability
snapshot retains its one-hour freshness policy; route-list catalogs use a
48-hour TTL measured from the last successfully accepted BFF validation and
are fresh only while validation age is strictly less than 48 hours. At age 48
hours or later, the client revalidates while retaining
last-known-good payloads. Route cache keys include city, locale, and mode;
entry count and encoded size are bounded. Outcomes explicitly distinguish
network, valid cache, stale/offline, empty, and typed failure. A successful
route-list 200 atomically replaces payload, ETag, and both timestamps; a 304
retains payload and fetched time, advances validation time, and uses the
response ETag when present. A cached route list is accepted only when its
embedded request exactly matches the requested key and every route has that
request's city ID; a mode-filtered request must also contain only the requested
mode. Otherwise the entry is evicted. A route-list 200 with either semantic
mismatch fails closed and leaves the prior LKG entry and timestamps untouched.
During once-only, off-UI hydration, the repository scans at most the 12 bounded
route entries, evicts semantically invalid entries, and publishes every valid
eligible locale/mode variant to the immutable in-memory snapshot. A valid cached
city snapshot prevents publication and evicts route variants for absent or
route-disabled cities. Future, negative, malformed, oversized, or incompatible
entries are evicted.

Only transient transport/upstream/rate-limit failures may return stale
last-known-good data. A fresh city snapshot invalidates routes for removed
cities or where `routes` becomes unavailable. A route-list `CITY_NOT_FOUND` or
`CAPABILITY_NOT_AVAILABLE` response invalidates every bounded route-cache
locale/mode variant and immutable in-memory route snapshot for its typed city.
Provider-ID-change responses from every city-scoped BFF endpoint that declares
them perform the same invalidation, without parsing opaque route or provider
IDs and without affecting another city. A route-list 404 or 501 is returned as
its typed failure after invalidation, so a following request cannot return its
former catalog as fresh. Fresh network data remains usable if its best-effort
cache write fails.

Use the existing native `SharedPreferences` and `NSUserDefaults` adapter
pattern for the bounded serialized cache. This avoids introducing SQLDelight,
Room, Realm, DataStore, or another persistence/runtime stack for two small
catalog snapshots. The tradeoff is intentionally no durable stop directory,
shape, vehicle, arrival, or journey cache, no general query capability, and
strict limits/corruption eviction instead of database migration/recovery.

## Consequences

- Production route/catalog callers receive explicit freshness and error state;
  synchronous callers read the latest immutable in-memory snapshot without
  initiating I/O.
- Native hosts own non-secret endpoint and cache-store composition. ViewModels
  remain constructor-injected and do not resolve Koin.
- Route lists can start promptly from valid cache and survive eligible
  transient failures as visibly stale data. They cannot disguise configuration,
  not-found, capability, serialization, or provider-ID-change errors.
- A future durable offline product for large directories or realtime data needs
  a separate scope, storage budget, eviction policy, encryption/privacy review,
  and ADR; it must not silently broaden this catalog cache.

## Alternatives

- **Direct provider clients or client-side credentials:** rejected because
  they leak contracts/secrets and couple mobile releases to providers.
- **Preview repository fallback in production:** rejected because an outage or
  missing endpoint would appear to be valid transit data.
- **Unbounded/default HTTP retry behavior:** rejected because it can delay UI,
  leak response resources, and retry non-retryable client failures.
- **Persisting every BFF response or adding a database now:** rejected because
  the documented offline requirement is only bootstrap/catalog data and larger
  persisted data needs product and operational decisions.
- **Permissive release cleartext/ATS exceptions:** rejected because no deployed
  production BFF endpoint exists and release traffic must remain HTTPS-only.

## Verification

Run formatting and target compilation appropriate to the changed surface:

```text
./gradlew spotlessCheck
./gradlew :shared:compileCommonMainKotlinMetadata :shared:compileKotlinIosSimulatorArm64
./gradlew :androidApp:assembleDebug
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

QA owns shared-target serialization, retry, cache, and freshness tests. Manual
release verification must confirm that no endpoint configuration fails closed,
while Debug loopbacks remain restricted to their respective emulator/simulator.
