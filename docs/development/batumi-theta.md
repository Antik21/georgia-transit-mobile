# Batumi Theta development adapter

This is an explicit development/manual-smoke integration only. Theta terms, operational quota,
and production data-quality evidence have not been accepted, so production startup rejects
`BATUMI_THETA_ENABLED=true` and the catalog is labelled `UNREVIEWED`.

The BFF is the only Theta caller. Mobile receives normalized routes, stops, encoded shapes, live
positions, and (where reliable) normalized approximate arrivals; it never sees a Theta URL, DTO,
raw ID, or upstream `Name`. The catalog is validated atomically before publication and kept as an
in-memory LKG for seven days. That cache is lost on process restart.

When the operator has separately authorized a development smoke, the adapter may call only
`getBusLocsOnRoute` for a route ID from that validated catalog. It has a per-route four-second
cache/single-flight boundary. Valid `{data:null}` is an empty live snapshot, distinct from an
upstream timeout/unavailable response. A failed refresh may use its last validated normalized
snapshot for at most 60 seconds; all such vehicle pages are explicitly `stale=true`. Raw payloads
are neither logged nor cached. Upstream `Name` becomes a short-lived namespaced internal ID only;
it is never shown as a bus/fleet number.

`officialArrivals` remains false. `arrivals=true` means only that the BFF can sometimes supply a
`CLIENT_ESTIMATE`; empty arrival lists are expected whenever direction or movement confidence is
insufficient. There is no schedule, official ETA, public vehicle number, timestamp, bearing, or
next-stop claim in the upstream data.

### Approximate ETA policy

The BFF projects each live position and ordered stop on the catalog route polyline. It emits an
estimate only after three fresh samples (each 2–45 seconds apart) produce two agreeing projected
movement directions. Samples must move at least 15 m, be within 120 m of the line, imply 1–16 m/s,
and not be a teleport (more than `min(750 m, elapsed × 30 m/s + 80 m)`). The two speeds are averaged.
ETA is emitted only for a stop ahead in that direction; a non-loop terminal or a passed stop has no
ETA. A loop is recognized only where the shape ends within 120 m of its start and may wrap once.
Intermediate projected stops add 20 seconds dwell each. Estimates over 90 minutes, zero/slow motion,
stale data, path ambiguity, reversing direction, or insufficient samples are omitted. `expectedAt`
and rounded-up `expectedInMinutes` always agree, and the app labels the source “approximately” in
RU/EN/KA.

Theta `Status` is bounded/validated as structural evidence but has undocumented semantics, so it
does not become a passenger direction, destination, or route label. The current catalog exposes one
neutral technical direction; directional confidence is therefore established by movement first.
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
