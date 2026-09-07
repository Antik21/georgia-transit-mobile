# ADR 0006: Controlled Transitous best-effort fallback

Status: Accepted (2026-09-08)

## Context

Tbilisi needs a carefully constrained fallback for arrivals/schedule and
server-side journey planning when a reviewed deployment is eligible to use
Transitous. The mobile clients must continue to use only the normalized Transit
BFF. A hosted volunteer API is not a generally available production dependency:
its [policy](https://transitous.org/api/) limits hosted use to FOSS,
non-commercial, light use; commercial use is forbidden and routing/heavy or
multi-user use needs operator contact/approval. The MOTIS MIT software licence
does not grant hosted-service or source-data rights.

The pre-existing city adapter owns one city and the control plane only
intersects intrinsic capabilities. It previously used `officialArrivals` as the
only arrivals gate, had no public city attribution, and journey pages did not
express source/realtime/stale metadata.

## Decision

Add an internal `TransitousTransitProviderAdapter` to `:transitBff`, pinned to
the [MOTIS v2.10.2 OpenAPI](https://raw.githubusercontent.com/motis-project/motis/refs/tags/v2.10.2/openapi.yaml).
It is constructed only after explicit environment activation: enabled flag,
recorded eligibility reference and acknowledgement, meaningful contact and its
acknowledgement, and a safe release version. The hosted base origin is HTTPS and
restricted to the exact `https://api.transitous.org` origin. Construction does
no network probe; therefore deployment startup never relies on a volunteer
response.

Every upstream request carries `GeorgiaTransitBff/<version> (contact: <contact>)`.
Routing additionally requires both an explicit acknowledgement and a recorded
written-approval reference. Without those fields the intrinsic Tbilisi
`tripPlanning` capability is false. Runtime control still takes the immutable
snapshot before service calls, so an operator can disable a city/feature before
the adapter is reached. The checked-in environment example remains disabled;
only an operator with written eligibility/approval may supply these values.

The adapter only calls `GET /api/v6/stoptimes` and, with routing approval,
`GET /api/v6/plan`. It uses typed strict subsets of upstream JSON, opaque
base64url namespaced BFF identifiers, conservative Tbilisi coordinate bounds,
a 500 m upstream stop-search radius, a five-minute-past/24-hour-forward
plan-time horizon, a bounded server-only reviewed/discovered stop catalog that
rejects arbitrary public IDs before `/stoptimes`, exact opaque stop-ID matching
for the response place and every event before mapping/cache, 256 KiB response
bodies,
two concurrent/24 per minute global upstream attempt budget, no public cursor,
and bounded retries, backoff, timeouts, and 429 handling. It never logs
payloads, URLs, secrets, or upstream source filename/line metadata.
Cancellation is rethrown and failures normalize to the existing public error
contract. It never calls
`/api/v6/map/trips`, does not expose vehicle positions, and never infers GPS.

Because `/stoptimes` is queried with `arriveBy=false`, arrivals map
`place.departure` to `expectedAt` and `place.scheduledDeparture` to
`scheduledAt`; `StopTime.realTime` maps to `realtime`, and event/trip/place
flags map to `cancelled`. `AGGREGATOR_REALTIME` is used only for realtime
items; others are `SCHEDULE`. The legacy `JourneyLeg` contract is preserved
unchanged for old clients: it contains only transit portions with required
route/direction/stop IDs. Additive ordered `Journey.segments` retains every
upstream portion, using only the app-owned `TRANSIT`, `WALK`, `BICYCLE`, `CAR`,
or `OTHER` categories rather than any provider taxonomy; only `TRANSIT` carries
route/direction and mandatory stop IDs. This adapter retains endpoint
coordinates for every segment. A malformed, unsupported, reversed,
out-of-bounds, or non-continuous itinerary invalidates the whole upstream page
rather than producing a partial page. Bounded server-memory LKG pages for an
exact effective request may be returned only after a transient volunteer outage
and only with `stale: true`. `observedAt` is taken after successful body read,
decode, mapping, and validation and is also the timestamp used to age LKG.

Extend the normalized contract additively with generic `arrivals`, preserving
`officialArrivals` as a separate false value for Transitous, city attribution
links, and journey `source`/`realtime`/`stale`. Both control documents and
shared DTO/domain mappers carry these fields. Common Compose consumes only the
normalized attribution model: City Selection shows the exact
`https://transitous.org/sources/` and OpenStreetMap copyright credits even for
a non-selectable city, and Map repeats them after selection. Both surfaces use
stable automation IDs and accessible links on both mobile platforms.

Ktor CIO is added as the existing Ktor 3.3.3 client-family JVM engine. It is
Apache-2.0 licensed and has no mandatory production fee; it does not introduce
a second networking stack.

## Consequences

- A production binary is safe by default: no hosted fallback adapter or client
  exists absent explicit activation, and a missing/invalid control document is
  still closed.
- Transitous data is visibly attributed and never marketed as official city
  realtime or GPS vehicle data.
- The Tbilisi fallback has deliberately narrow discovery capabilities: it does
  not add map-stop, route-directory, shape, or vehicle API consumption merely
  to make the upstream API look complete.
- Availability can degrade to a labelled ten-minute BFF LKG response on a
  qualifying outage, but malformed upstream data remains a safe 502.
- Operators own eligibility evidence, upstream approvals, usage/quota review,
  traffic controls, monitoring, and the right to keep the fallback disabled.

## Alternatives

- **Enable the hosted API by default:** rejected because it cannot prove policy
  eligibility, exposes the volunteer service to accidental traffic, and has no
  emergency approval record.
- **Treat aggregator arrivals as official:** rejected because it misrepresents
  source authority and conflicts with the normalized capability contract.
- **Use `map/trips` as vehicle positions:** rejected because trip segments do
  not assert an observed GPS position.
- **Put Transitous types/configuration in mobile:** rejected because it leaks a
  provider contract and bypasses BFF control/attribution decisions.
- **Persist unbounded realtime LKG:** rejected because it turns best effort
  volunteer data into an opaque offline store without a retention/privacy ADR.

## Verification

```text
./gradlew --no-daemon --no-build-cache spotlessCheck
./gradlew --no-daemon --no-build-cache :transitBff:compileKotlin :transitBff:check
./gradlew --no-daemon --no-build-cache :shared:compileCommonMainKotlinMetadata :shared:compileKotlinIosSimulatorArm64
./gradlew --no-daemon --no-build-cache :androidApp:assembleDebug
```

QA must add contract tests for disabled construction, control-plane kill switches,
MOTIS request bounds and mappings, all outage/LKG paths, safe errors/redaction,
JourneyPage metadata, and shared attribution accessibility. Do not contact the
production hosted API from routine CI.
