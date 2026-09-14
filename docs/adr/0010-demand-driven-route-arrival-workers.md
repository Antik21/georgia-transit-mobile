# ADR 0010: Demand-driven route arrival workers

Status: Superseded by ADR 0011 (2026-09-14)

## Context

Batumi's reviewed web-client behavior shows that arrival estimates can be derived from a cached
route catalog, route geometry, recent vehicle observations, and a speed model. The mobile clients
must not own provider polling or provider-specific calculations. A selected-route screen also needs
one coherent ETA generation for every stop instead of issuing a separate upstream request per stop.

Unconditionally polling every route would create needless upstream traffic when no passenger is
viewing it. Request-only calculation, however, cannot reliably retain the successive observations
needed by adapters that infer direction and speed from movement.

## Decision

Add an optional route-arrival operation to `CityTransitProviderAdapter` and expose it as
`GET /v1/cities/{cityId}/routes/{routeId}/arrivals`. Its normalized `ArrivalPage` contains arrivals
for every stop on that route; `limitPerStop` is applied independently to each stop.

The Batumi development adapter owns an in-process, coroutine-based worker registry keyed only by
normalized route ID. The first route-board request validates the route, starts one worker, and
waits for its first bounded refresh. Later requests return the latest atomic snapshot and update
the worker's activity time. The worker polls every five seconds and stops after 30 minutes without
a route-board request. BFF shutdown cancels all workers.

Each refresh fetches or reuses one validated route vehicle snapshot, projects route data once, and
calculates arrivals for all future stops. Raw provider payloads, raw vehicle names, and provider IDs
remain inside the adapter. Stale vehicle snapshots produce an explicitly stale empty live board,
never a silently frozen ETA.

The registry is deliberately per BFF process. Before running multiple production replicas against
a quota-sensitive source, operators must introduce reviewed route affinity or a distributed lease;
adding a distributed persistence stack is outside this decision.

This decision does not approve BatBus or Theta data for production. Provider terms, quota,
attribution, schema stability, and prediction accuracy still require an explicit review before the
Batumi adapter can become production-ready.

## Consequences

- A mobile route screen can retrieve one coherent server-computed board without provider knowledge.
- Active routes stay warm long enough to collect movement samples; inactive routes consume no
  continuing polling traffic after the idle window.
- Concurrent requests share one route worker and the existing service single-flight boundary.
- Other city adapters may reject the optional route-board operation with the normalized 501 response
  until they implement it.
- A process restart discards active workers and their short sample history; the first new request
  recreates them safely.

## Alternatives

- **One request per stop:** rejected because it repeats route observations and can mix GPS
  generations in one screen.
- **Always poll every route:** rejected because idle routes would consume upstream quota.
- **Run the algorithm in Android/iOS:** rejected because it duplicates logic and exposes provider
  behavior outside the BFF.
- **Create JVM threads per route:** rejected because structured Kotlin coroutines provide bounded,
  cancellable ownership without dedicating an operating-system thread.

## Verification

```text
./gradlew :transitBff:test
./gradlew spotlessCheck :transitBff:check :transitBff:installDist

curl -i \
  'http://127.0.0.1:8080/v1/cities/demo/routes/demo:fixture:route:blue/arrivals?limitPerStop=2&locale=en'
```
