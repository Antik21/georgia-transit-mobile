# ADR 0022: In-house Batumi journey planner

Status: Accepted (2026-11-12)

Supersedes in part: the `tripPlanning=false` classification for Batumi in
[ADR 0013](0013-batumi-theta-production-approval.md) and the "never expose trip planning" constraint
in [the Theta evaluation](../development/batumi-theta-evaluation.md), only for the BFF-derived
planner defined here.

## Context

Users need to choose two points in Batumi, origin A and destination B, and get the admissible
public transport options. Each option must state:

- the approximate walk to the boarding stop;
- the walk between stops when a transfer uses a different stop;
- the walk from the final stop to the destination;
- the wait, ride, and total time.

A later goal is to prefer a slightly farther stop when a live bus reaches it much sooner.

The normalized journey contract (`GET /v1/cities/{cityId}/journeys`, `Journey`, `JourneySegment`,
`JourneyPage`) and the `tripPlanning` capability already exist. They are served only by Tbilisi
adapters, which are disabled in production. Batumi is the only production city. Its
`tripPlanning=false`, and no provider offers a Batumi planner. Hosted Transitous does not cover
Batumi, and its policy forbids commercial or heavy routing (ADR 0006).

The Batumi BFF adapter already keeps a validated in-memory catalog and a live snapshot:

- `stopOrderByRoute` ordered by `Status`, then `Order`;
- `RouteCycle` with cumulative metres;
- route shapes;
- the stable moving-speed table;
- the city-wide BatBus vehicle snapshot every five seconds (ADR 0011);
- the status-aware stop-chain ETA (ADR 0012).

Neither source provides a timetable or headways. The public BatBus web planner, analysed on
2026-11-12, runs entirely in the browser on the same two sources. It allows at most one transfer.
It chooses each route's stop pair by minimum walking before it evaluates time. When no live bus is
known, it assumes a fixed 15-minute wait. Its result is a useful reference, but not a contract.

## Decision

### Ownership and activation

Add a provider-agnostic `JourneyPlanner` service boundary to `:transitBff` and a Batumi
implementation built only from the validated catalog and live snapshot. Planning makes **no
additional upstream request**: Theta and BatBus egress stays exactly as bounded by ADR 0013.

On 2026-11-12 the product operator explicitly approved derived trip planning from both Batumi
sources under the same access conditions as ADR 0013. Any later change to those conditions disables
`tripPlanning` pending review, together with the affected source capabilities.

Batumi's intrinsic `tripPlanning` becomes true only while both of these hold:

- a validated catalog is published;
- the live snapshot is within its existing staleness bound.

The capability-control document keeps `tripPlanning` as the immediate kill switch, and the checked-in
example remains disabled.

### Network model

On every catalog publication, atomically build an immutable `BatumiNetworkGraph`:

- one pattern per route and `Status` with ordered stops and cumulative metres;
- a shape slice for every consecutive stop pair;
- a stop-to-stop walking-neighbour index within 600 metres.

A ride never crosses a `Status` boundary or a terminal, because the bus may leave service there. The
only exception is a route whose upstream `RouteIsCircle` is true; the parser starts reading that
flag. Catalog refresh failures keep the previous graph under the existing seven-day rule.

### Search

Use a time-dependent round-based search (RAPTOR) with at most two transfers.

| parameter | value |
| --- | --- |
| access and egress radius | 1000 m |
| transfer walk | ≤ 600 m |
| walking distance (phase 1) | Haversine × 1.3 |
| walking speed | 1.3 m/s |
| dwell | 60 s per intermediate stop, the target stop excluded (ADR 0012) |
| default transfer buffer | 60 s; 300 s when the user asks for a larger buffer |

Departure at a boarding stop is chosen as follows:

1. **Live.** Use the first projected vehicle on that pattern whose ADR 0012 ETA is at least the
   passenger's arrival plus buffer. The ride time is that same vehicle's ETA difference between the
   boarding and alighting stops. The wait is labelled `LIVE_VEHICLE`.
2. **Headway estimate.** If no live candidate exists inside the 45-minute ETA horizon, use
   cycle length ÷ (moving speed × active vehicles on the pattern). The conservative expected wait
   equals one headway, and it is labelled `HEADWAY_ESTIMATE`.
3. **Unavailable.** A pattern with no active vehicle is not offered.

Every stop inside the access radius is evaluated by its resulting arrival time, not only the
nearest stop. This includes boarding upstream on the same route, so a farther stop with an earlier
bus wins naturally.

### Selection

Return every admissible option, ranked, up to six, instead of asking the user to pick a mode.
Each option may carry labels, like the "best / cheapest / fastest" tags of flight search.

Admissible options come from the Pareto set over arrival time, walking distance, and transfers,
after these filters:

- two options with the same route sequence are duplicates; keep the lower generalized cost;
- drop transit that neither arrives earlier nor walks less than walking directly;
- drop an option that arrives more than `max(15 min, 50%)` after the earliest option, the direct
  walk included when it is offered;
- keep an option with more transfers only if it arrives at least five minutes earlier than every
  option with fewer transfers;
- add a walk-only option when direct walking is at most 2 km or beats every transit option.

The generalized cost weighs walking × 1.5, waiting × 1.2, and four minutes per transfer.

Labels are assigned only when at least two options are returned. Each label marks at most one
option, and one option may carry several labels:

| label | option |
| --- | --- |
| `OPTIMAL` | the lowest generalized cost |
| `FASTEST` | the earliest arrival; ties go to the lower cost |
| `LEAST_WALKING` | the least walking among transit options |
| `NO_TRANSFERS` | the lowest-cost transit option without transfers, only when another transit option has transfers |

When more than six options remain, keep every labelled option first, then fill by generalized
cost. Order the page with the `OPTIMAL` option first, then by arrival time. Clients render the
label with a fixed, label-specific colour that never depends on order, route, or session.

### Contract

Add `POST /v1/cities/{cityId}/journeys/plan`. Its request body, privacy boundary, and client
endpoint model are defined by [ADR 0023](0023-journey-endpoints-and-coordinate-boundary.md).

The response reuses `JourneyPage` and extends `Journey` and `JourneySegment` with optional fields
only:

- `Journey`: `durationSeconds`, `walkingDistanceMeters`, `timingQuality` (`LIVE`, `MIXED`,
  `ESTIMATED`), `labels` (`OPTIMAL`, `FASTEST`, `LEAST_WALKING`, `NO_TRANSFERS`);
- every segment: `distanceMeters`, `geometry` (encoded polyline), `geometryApproximate`;
- transit segments only: `intermediateStopCount`, `waitSeconds`, `waitBasis`, `vehicleId`.

Waiting stays a field of the transit segment rather than a new segment mode, so existing strict
enum decoders keep working. The page `source` is `CLIENT_ESTIMATE`. `realtime` is true only when
every wait is `LIVE_VEHICLE`. The response validator accepts `CLIENT_ESTIMATE` for journeys and
checks segment continuity and walking/transit alternation.

Legacy `GET /journeys` is deprecated. Batumi answers it with `501 NOT_IMPLEMENTED` even when
`tripPlanning` is true, because query-string coordinates may be retained by edge infrastructure.

### Bounds and observability

- Each request has a 1 s planning budget and four concurrent plans per instance. Excess load
  receives the existing `429` envelope. The budget was 300 ms until 2026-11-13, when the first plan
  on a cold JVM of the 0.5-CPU production instance exceeded it; warm plans stay well below 300 ms.
- Metrics use the static operation `journey_plan` with outcome and latency only.
- No new library, cache, or upstream is added.

Phase 1 walking is a labelled straight-line approximation. A pedestrian routing engine that uses OSM
data, such as embedded GraphHopper, requires its own ADR covering memory, data licence (ODbL), and
attribution.

## Consequences

- Batumi gets planning without new egress, infrastructure, or licence surface. Answers are always
  approximate and non-official, and the UI must say so.
- Plan quality is bounded by the stop-chain ETA model, the 45-minute horizon, and the absence of
  timetables. Waits at night or on single-vehicle lines are estimates or unavailable.
- The graph is rebuilt on catalog publication, so a changed route set or speed table is reflected
  in planning within one catalog refresh.
- The algorithm is owned code and needs regression fixtures. It sits behind a provider-agnostic
  boundary, so a GTFS-based engine can later replace it without changing the public contract.
- Old clients keep decoding journeys, because all new fields are optional.

## Alternatives

- **Port the BatBus browser planner logic.** Rejected: it fixes the minimum-walking stop pair before
  timing, allows only one transfer, and assumes a fixed wait.
- **Plan on the mobile client.** Rejected: it would ship the full provider-derived graph, the
  vehicle feed, and the ADR 0012 ETA model into the app. That violates the BFF boundary.
- **Self-hosted MOTIS (MIT) or OpenTripPlanner (LGPL-3.0).** Deferred: both need a synthesized GTFS
  and GTFS-RT feed from a source without timetables, a separate service, and more memory on the
  single Render instance. They remain candidates when multiple cities publish GTFS.
- **Hosted Transitous.** Rejected: no Batumi coverage, and its usage policy forbids this use.

## Verification

Golden tests on a recorded catalog fixture cover:

- a direct trip and transfers on the same stop and on a walked stop;
- a farther stop with an earlier live bus winning over the nearest stop;
- upstream boarding on the same route;
- no ride across a terminal, and the `RouteIsCircle` exception;
- a pattern without vehicles, and the headway fallback;
- an A–B pair closer than the walk-only threshold;
- the 45-minute horizon;
- selection and deduplication;
- segment continuity;
- a warm plan on a full-size Batumi-like graph within 300 ms, well inside the 1 s budget.

Contract tests cover the optional fields, the `CLIENT_ESTIMATE` validator path, legacy GET 501, and
the capability gate.

```text
./gradlew spotlessCheck :transitBff:check :shared:testAndroidHostTest :androidApp:assembleSandbox
```
