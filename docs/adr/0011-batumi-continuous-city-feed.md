# ADR 0011: Continuous Batumi city feed

Status: Accepted (2026-09-14)

## Context

ADR 0010 introduced demand-driven route arrival workers. The original route-specific estimator
required three successive observations before the BFF could infer movement, so the first passenger
opening a cold route could see an empty board. Running that worker for every route would
also multiply upstream requests.

The BatBus web feed exposes one small `getAllBuses` response for the whole city. A measured
two-minute experiment on 28 routes, 578 stops, and roughly 150 vehicles produced about 8,300 ETA
candidates per generation. A deliberately unoptimized Node reference calculation averaged 72 ms
every five seconds and 1.66% of one CPU core. The live response averaged 10.7 KiB, projecting to
about 176 MiB per day before protocol overhead.

## Decision

Supersede per-route polling for the configured Batumi runtime with one continuous, sequential
city-feed coroutine. It starts after runtime capability control publishes its first snapshot and
polls only while Batumi vehicle positions or arrivals remain enabled. Every five seconds it:

1. fetches one bounded `getAllBuses` payload;
2. maps known raw route keys to existing opaque normalized route IDs;
3. validates and normalizes every vehicle inside the Batumi provider boundary;
4. preserves each bus's provider-scoped route-part status;
5. calculates a complete ETA generation for all future stops; and
6. atomically replaces the prior city snapshot.

Vehicle, stop-arrival, and route-arrival requests read this snapshot and never initiate a
route-specific live call in the configured application runtime. A failed refresh retains the last
validated generation. Data older than 15 seconds is explicitly stale; data older than 60 seconds is
unavailable. Shutdown cancels the worker and closes both upstream clients.

The existing demand-driven route registry remains an injected-test/manual fallback when no bulk
client is supplied. It is not used by the application-composed Batumi adapter.

Bulk polling records only the existing finite Batumi/provider/vehicle telemetry labels and never
logs a URL, raw route ID, vehicle name, coordinate, or payload. The runtime capability gate prevents
background egress after the relevant Batumi capabilities are disabled.

This remains an unreviewed development integration. The fixed BatBus endpoint, its usage terms,
quota, attribution, and schema stability require operator approval before production activation.

## Consequences

- A selected route receives the latest already calculated board without a user-driven refresh.
- Upstream cadence is one request per five seconds for the city, independent of active users and
  route count.
- In-memory work scales with the number of published vehicles and future stop candidates, not with
  concurrent clients.
- A process restart can publish ETA after its first validated city snapshot.
- Multiple BFF replicas would each poll independently; production rollout therefore needs an
  approved aggregate quota or a single elected feed owner with snapshot distribution.

## Alternatives

- **Keep route workers:** rejected for the configured runtime because cold routes remain slow and
  polling all routes multiplies requests.
- **Calculate on every HTTP request:** rejected because it repeats identical geometry work and mixes
  GPS generations under concurrent load.
- **Persist every live snapshot:** rejected for this phase because short in-memory history is enough
  and a new persistence stack would enlarge the privacy and operations surface.
- **Run the poller while capabilities are disabled:** rejected because the capability control plane
  is the operator's egress kill switch.

## Verification

```text
BATUMI_EXPERIMENT_DURATION_MS=120000 \
node tools/experiments/batumi-all-routes-load.mjs

./gradlew spotlessCheck :transitBff:check :transitBff:installDist
```

After starting the development Batumi adapter, wait 10–15 seconds and verify that repeated vehicle,
stop-arrival, and route-arrival requests do not increase route-specific upstream call counts and
that `/metrics` reports fresh Batumi vehicle observations.
