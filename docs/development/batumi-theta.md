# Batumi Theta development adapter

This is an explicit development/manual-smoke integration only. Theta terms, operational quota,
and production data-quality evidence have not been accepted, so production startup rejects
`BATUMI_THETA_ENABLED=true` and the catalog is labelled `UNREVIEWED`.

The BFF is the only Theta caller. Mobile receives normalized routes, stops, encoded shapes, live
positions, and (where reliable) normalized approximate arrivals; it never sees a Theta URL, DTO,
raw ID, or upstream `Name`. The catalog is validated atomically before publication and kept as an
in-memory LKG for seven days. That cache is lost on process restart.

When the operator has separately authorized a development smoke, the application-composed adapter
uses one fixed BatBus `getAllBuses` request every five seconds for the whole city. A complete
validated generation atomically replaces the prior snapshot and feeds vehicles plus every stop and
route arrival board. Failed refreshes retain the prior normalized generation: it becomes stale after
15 seconds and unavailable after 60 seconds. Runtime capability control gates the poller, so disabling
both Batumi vehicle positions and arrivals stops upstream egress. Raw payloads are neither logged nor
cached. Upstream `Name` becomes a short-lived namespaced internal ID only; it is never shown as a
bus/fleet number. The old route-specific client remains only as an injected test/manual fallback.

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
This is intentionally conservative and must not be promoted to production ETA behavior.

For a respectful one-off smoke, use a private operator-owned capability-control path/state directory
(not the checked-in file directly), then run:

```text
BFF_MODE=development BFF_FIXTURES_ENABLED=false \
BATUMI_THETA_ENABLED=true \
BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT=I_UNDERSTAND_THETA_DEV_ONLY \
BFF_SCHEMA_INTERLOCK_ENABLED=true \
BFF_CAPABILITY_CONTROL_PATH=/private/batumi/capabilities.json \
BFF_CAPABILITY_CONTROL_STATE_DIR=/private/batumi/state \
./gradlew :transitBff:run
```

Copy `transitBff/batumi-theta-capability-control.example.json` into that private control path with
the ownership/mode requirements in the capability-control runbook. The operator can separately
disable `vehiclePositions` or `arrivals` immediately; repeated malformed live responses also latch
the corresponding schema-drift interlock and fail closed. Make one catalog-backed routes,
nearby-stops, shape, vehicle, and (after enough samples) arrivals request; do not add a live request
to CI or log an upstream payload.

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
