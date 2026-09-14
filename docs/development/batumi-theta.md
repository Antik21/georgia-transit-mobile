# Batumi production adapter: Theta catalog and BatBus live feed

The product operator approved this integration for production on 2026-09-15; the decision and its
operational bounds are recorded in [ADR 0013](../adr/0013-batumi-theta-production-approval.md).
The adapter remains disabled by default and is published as
`PRODUCTION_READY`/`REVIEWED_ADAPTER` only after explicit operator activation. The production
approval covers exactly two fixed HTTPS origins: the Theta catalog at
`https://thetamaps.site:54321/api` and the hard-coded BatBus live feed at
`https://batbus.app/api/getAllBuses`. Their schema validation, bounded polling, capability kill
switches, and non-official ETA semantics remain part of the production safety envelope.

The BFF is the only caller of either upstream. Mobile receives normalized routes, stops, encoded
shapes, live positions, and (where reliable) normalized approximate arrivals; it never sees a Theta
URL, DTO, BatBus URL, raw ID, or upstream `Name`. The Theta catalog is validated atomically before
publication, refreshed at most once every ten minutes while healthy, and kept as an in-memory LKG
for seven days. After a failed catalog refresh, retries use a twenty-second cooldown. The cache is
lost on process restart.

When the operator activates the adapter, the application-composed adapter makes one sequential
request to the fixed BatBus `getAllBuses` endpoint every five seconds for the whole city while
`vehiclePositions` or `arrivals` is enabled. A complete
validated generation atomically replaces the prior snapshot and feeds vehicles plus every stop and
route arrival board. Failed refreshes retain the prior normalized generation: it becomes stale after
15 seconds and unavailable after 60 seconds. Runtime capability control gates the poller, so
disabling both Batumi vehicle positions and arrivals stops live-feed upstream egress. Raw payloads
are neither logged nor cached. Upstream `Name` becomes a short-lived namespaced internal ID only; it
is never shown as a bus/fleet number. The old route-specific client remains only as an injected
test/manual fallback.

`officialArrivals` remains false. `arrivals=true` means only that the BFF can sometimes supply a
`CLIENT_ESTIMATE`; empty arrival lists are expected whenever status projection is unavailable.
There is no schedule, official ETA, public vehicle number, timestamp, bearing, or
next-stop claim in the upstream data.

The route board endpoint `GET /v1/cities/batumi/routes/{routeId}/arrivals?limitPerStop=N` reads the
already calculated city generation. Opening a route does not start an upstream request. The common
worker and its last-known-good snapshot are process-local and are cancelled during BFF shutdown.

### Approximate ETA policy

The BFF builds a cyclic stop chain ordered by `Status + Order`, preserves the live bus `Status`, and
projects GPS only onto segments for that route part. The published route shape independently bounds
GPS to a 150 m corridor. GPS supplies position, not speed: the estimator uses a stable per-route
moving-speed profile with a 25.1 km/h fallback. Intermediate stops add 60 seconds each; the target
stop is excluded. A bus up to 150 m beyond a stop is treated as arriving, while a target farther
behind wraps to the next cycle. Estimates over 45 minutes, an invalid status/projection, or stale
data are omitted. Every qualifying vehicle is returned, so duplicate route labels are expected. A
vehicle survives one missing live snapshot, then is removed unless it is reconfirmed. `expectedAt`
and whole-minute `expectedInMinutes` agree, and the app labels the source “approximately” in RU/EN/KA.

Theta `Status` is bounded and used only as provider-scoped route-part evidence. It does not become a
passenger direction, destination, or route label. The current catalog exposes one neutral technical
direction.
This intentionally conservative policy is the approved production ETA behavior. It must remain
labelled approximate and must never be represented as an official arrival.

For a production run, copy the checked-in example into a private operator-owned capability-control
path (do not run against the checked-in file directly), create its paired state directory with the
permissions required by the capability-control runbook, then run:

```text
BFF_MODE=production BFF_FIXTURES_ENABLED=false \
BATUMI_THETA_ENABLED=true \
BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT=I_APPROVE_THETA_PRODUCTION_USE \
BFF_SCHEMA_INTERLOCK_ENABLED=true \
BFF_CAPABILITY_CONTROL_PATH=/private/batumi/capabilities.json \
BFF_CAPABILITY_CONTROL_STATE_DIR=/private/batumi/state \
./gradlew :transitBff:run
```

Despite its historical `THETA` name, `BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT` and the
`I_APPROVE_THETA_PRODUCTION_USE` token acknowledge production traffic to both the fixed Theta
catalog origin and the hard-coded BatBus live origin. Neither origin currently requires a
credential. The acknowledgement does not approve a configurable BatBus URL or any other origin.

Copy `transitBff/batumi-theta-capability-control.example.json` into that private control path with
the ownership/mode requirements in the capability-control runbook. The operator can separately
disable `vehiclePositions` or `arrivals` immediately; repeated malformed live responses also latch
the corresponding schema-drift interlock and fail closed. Keep the production service at one
instance until an aggregate request budget or single-feed-owner design is approved. Verify one
catalog-backed routes, nearby-stops, shape, vehicle, and arrivals request without adding a live
upstream request to CI or logging an upstream payload.

For a complete emergency stop, publish a new capability-control revision with Batumi
`enabled=false`. That removes the city from the effective snapshot, stops the continuous live feed,
and prevents passenger requests from reaching the catalog adapter.

On Render, the capability document and writable durable state directory need a process-owned
persistent mount with the exact POSIX modes required by the capability-control runbook. A Render
environment secret or secret file alone does not replace the writable state directory. Keep the
service at one instance and verify the mount initialization before enabling automatic deploys.

To manually smoke the optional basemap in the same development process, additionally set
`BFF_MAP_ENABLED=true`, an explicit HTTPS raster template, and required attribution. A public OSM
template is documented only as a manual development check; it is not a production default or
approval. `BFF_MAP_PUBLIC_BASE_URL` is target-specific and is never inferred from a request Host
header: for Android emulator use `http://10.0.2.2:8080`; for an Android physical device first run
`adb reverse tcp:8080 tcp:8080` and use `http://127.0.0.1:8080`; for iOS Simulator use
`http://127.0.0.1:8080`. Run a BFF instance with the matching value for the target under test.
The BFF style emits that absolute same-BFF tile URL, while host-side `curl` can still call the
listener at `127.0.0.1`. This slice rejects map activation in production pending an operator-owned
reviewed provider, quota/abuse controls, and a separate architecture decision.
