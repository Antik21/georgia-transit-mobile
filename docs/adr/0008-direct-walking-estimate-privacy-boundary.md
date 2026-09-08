# ADR 0008: Direct walking estimate privacy boundary

Status: Accepted (2026-09-08)

## Context

A selected stop sheet needs a useful walking distance and approximate time from
the device's current location. A journey request with `maxTransfers=0` is not
a walking query: MOTIS v2.10.2 defines it as direct public transit. Its direct
walking alternatives are returned separately under `direct`. Precise device
coordinates are sensitive and must not become a BFF cache key, an LKG entry, a
metric label, analytics data, debug-inspector record, or durable client state.

## Decision

Add the normalized, additive `POST /v1/cities/{cityId}/walking-estimate`
operation. Its body contains `{from, to, locale}` and its response contains
only `distanceMeters`, `durationSeconds`, and `observedAt`. It is gated by the
existing `tripPlanning` capability and sends `Cache-Control: no-store,
no-cache, max-age=0` plus `Pragma: no-cache`. Call logging keeps only the route
path; observability uses the static `walking_estimate` operation and existing
`trip_planning` capability labels.

The BFF executes this operation directly at the provider boundary, with no
SingleFlight, TTL cache, LKG, disk cache, or coordinate-bearing key. Existing
coordinate-bearing journey coalescing and Transitous journey LKG are removed
for the same reason. Default adapters fail closed; the development fixture
returns a deterministic result. Transitous uses its pinned MOTIS v2.10.2
`/api/v6/plan` direct-only request: `transitModes` empty, `directModes=WALK`,
and positive bounded `maxDirectTime=1800` seconds. It parses only `direct`
itineraries whose legs are all `WALK`, requires finite nonnegative distance and
positive duration, and picks the fastest valid result.

Shared domain code owns the fallback: if capability is off, no direct result
exists, the normalized result is invalid, or the request fails, it estimates
Haversine distance × 1.25 at 1.4 m/s and labels it `Approximate`. Cancellation
propagates. The map starts one latest-wins debounced request only for an active
sheet and the same fresh, permission-granted, <=250m fix; it rechecks that gate
before request and publication. The UI always says the time is approximate and
states that the fallback is not walking directions.

No SDK, analytics system, cache, navigation, DI, or network stack is added.

## Consequences

- The public API is backward-compatible and additive, but client code must
  treat this response as one-shot rather than cacheable transit data.
- Arrivals remain available when permission is denied, restricted, disabled,
  stale, inaccurate, or unavailable. The sheet provides a polite explanation
  and reuses the My Location action where that action is available.
- Routing may be unavailable or fail; the transparent local fallback preserves
  useful context without representing a straight-line estimate as directions.
- Android and iOS Debug inspectors redact exact coordinate query/body fields;
  iOS usage copy describes the optional BFF request truthfully.

## Alternatives

- **Use journeys with `maxTransfers=0`:** rejected; it requests direct transit,
  not direct walking, and can silently produce the wrong product meaning.
- **Call Transitous from mobile:** rejected; it leaks provider contracts and
  bypasses the Transit BFF boundary.
- **Cache precise query results or reuse journey LKG:** rejected; keys and
  cached values retain sensitive coordinates longer than the request.
- **Add a routing or map SDK:** rejected; MOTIS is already behind the BFF and
  the feature needs no additional SDK or licensing surface.

## Verification

```text
./gradlew --no-daemon --no-build-cache :transitBff:compileKotlin
./gradlew --no-daemon --no-build-cache :shared:compileAndroidMain
./gradlew --no-daemon --no-build-cache :androidApp:assembleDebug
```

QA additionally verifies permission/freshness gates, cancellation/latest-wins
behavior, localized thresholds, debug redaction, and Android/iOS UI flows.
