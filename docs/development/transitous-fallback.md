# Transitous Tbilisi fallback operator runbook

## Direct walking estimate

`POST /v1/cities/{cityId}/walking-estimate` is deliberately separate from
`/journeys`. MOTIS v2.10.2 returns walking-only results in `plan.direct`; a
journey request with `maxTransfers=0` instead means direct transit. The BFF
issues a dedicated direct-only plan call with empty `transitModes`,
`directModes=WALK`, and `maxDirectTime=1800` seconds, then accepts only
all-WALK `direct` itineraries with finite nonnegative distance and positive
duration. It neither uses nor populates Transitous journey LKG.

The normalized walking endpoint is request-scoped: no BFF SingleFlight, TTL,
LKG, disk cache, coordinate telemetry label, body logging, or response cache
is permitted. It returns `Cache-Control: no-store, no-cache, max-age=0`; the
mobile client has a documented straight-line fallback when routing is disabled
or unavailable.

## Status and policy gate

This repository includes a server-only, best-effort Tbilisi adapter for the
pinned [MOTIS v2.10.2 OpenAPI](https://raw.githubusercontent.com/motis-project/motis/refs/tags/v2.10.2/openapi.yaml).
It is **disabled by default**. A built binary must not make a hosted Transitous
request unless all activation inputs below are deliberately supplied.

Read [Transitous hosted API policy](https://transitous.org/api/) before any
activation. Hosted use is limited to FOSS, non-commercial, light use; commercial
use is forbidden. Routing, heavy use, and multi-user deployments require contact
and approval from the operator. MOTIS's MIT software licence does not grant
rights to the hosted API or its data. The deployment owner must retain written
eligibility evidence before setting the acknowledgement variables below.

No value in this repository is written approval. Production remains blocked until
the deployer has both that external evidence and the runtime configuration below.

## Fail-closed activation

The adapter is constructed only when all arrival gates are true/nonblank:

| Input | Required value |
| --- | --- |
| `TRANSITOUS_ENABLED` | `true` |
| `TRANSITOUS_ELIGIBILITY_ACKNOWLEDGED` | `true`, only after the written policy/eligibility review |
| `TRANSITOUS_ELIGIBILITY_REFERENCE` | safe non-secret reference to that evidence |
| `TRANSITOUS_CONTACT` | a syntactically valid email address or absolute `http(s)` URL, included in `User-Agent` |
| `TRANSITOUS_CONTACT_ACKNOWLEDGED` | `true` |
| `BFF_RELEASE_VERSION` | safe deployed application version token |

`TRANSITOUS_BASE_URL` is restricted to the exact HTTPS
`https://api.transitous.org` origin; alternate hosts, path prefixes,
credentials, query strings, and fragments are rejected. The BFF does not probe
the volunteer service during startup, so startup readiness never depends on
volunteer availability. When enabled, every upstream request sends
`GeorgiaTransitBff/<version> (contact: <contact>)`. Provider request URLs,
response bodies, source filename/line metadata, and approval references are
never logged or exposed by the public BFF.

Routing has two more required gates:

| Input | Required value |
| --- | --- |
| `TRANSITOUS_ROUTING_APPROVAL_ACKNOWLEDGED` | `true` |
| `TRANSITOUS_ROUTING_APPROVAL_REFERENCE` | safe non-secret reference to written operator approval |

Without both routing values, the adapter's intrinsic `tripPlanning` capability
is false. The control document cannot expand it, so `/journeys` returns 501
before any upstream `/plan` call.

After those environment gates pass, place an operator-owned strict control file
and state directory as described in the [capability-control runbook](bff-capability-control.md).
Start from [`transitous-capability-control.example.json`](../../transitBff/transitous-capability-control.example.json),
keep `enabled: false` until the operational review is complete, and atomically
enable `tbilisi` only when arrivals are intended to serve. `arrivals: true` is a
generic arrivals/schedule capability; `officialArrivals` must stay `false` for
this adapter. A control update can disable the city, arrivals, or routing before
the service calls upstream.

`TRANSITOUS_TBILISI_STOP_IDS` is an optional operator-reviewed, comma-separated
seed list of at most 128 raw Tbilisi upstream stop IDs. It is server-only and
must not contain whitespace. The adapter also learns up to 128 LRU stop IDs
only from a strictly validated, approval-gated Tbilisi `/plan` response. An
opaque public stop ID must decode and be in this bounded catalog before any
`/stoptimes` request; an arbitrary base64url-looking ID therefore receives a
normalized 404 without reaching Transitous. The learned catalog is process
memory, so operators who need arrivals before an approved routing result must
seed reviewed IDs explicitly.

## Bounded adapter behavior

Only these pinned MOTIS endpoints are used:

- `GET /api/v6/stoptimes` only for a pre-authorized catalog stop ID. The adapter
  sends a current time, `arriveBy=false`, `direction=LATER`, `n <= 20`, transit
  mode, one language, no `window`, and no public page cursor. It requests and
  returns only the first page, trimming output to the requested bound. Tbilisi
  bounding-box checks on responses are correctness validation after a request,
  not an abuse/load control; the catalog gate is the pre-request protection.
  Before mapping, the response place and every returned event place must carry a
  nonblank stop ID exactly equal to the decoded requested upstream ID. The BFF
  performs no trim, case-folding, alias lookup, or other ID guessing; a mismatch
  is a normalized 502 and is never added to LKG.
- `GET /api/v6/plan` only after routing approval. It maps public `departureAt`
  to `time`, accepts times only from five minutes ago through the next 24 hours,
  bounds coordinate inputs to that Tbilisi box and the upstream stop search to
  a 500 m radius, transfers to at most three,
  search window to ten minutes, travel time to 120 minutes, itinerary count to
  four (an over-limit upstream response is rejected, not truncated), upstream
  timeout to three seconds, and disables detailed legs,
  alternatives, and direct routing output. No cursor is accepted or exposed. A
  malformed, unknown-mode, out-of-scope, or unsupported leg invalidates the
  entire response as 502; the adapter never silently drops legs or turns a
  nonempty upstream itinerary list into an empty 200 response. Every supported
  upstream portion is retained in ordered additive `Journey.segments` using
  only the public `TRANSIT`, `WALK`, `BICYCLE`, `CAR`, or `OTHER` categories.
  The legacy `Journey.legs` contract remains unchanged and contains only transit
  portions with required route, direction, and stop IDs. New clients use
  `segments`; existing clients can continue deserializing `legs` unchanged.
  This adapter retains endpoint coordinates for every emitted segment.
  The adapter rejects an itinerary when a leg is reversed, lies outside the
  itinerary time bounds, or starts before the preceding leg has arrived.

The adapter never calls `/api/v6/map/trips`. Its trip segments are not vehicle
positions, so this integration exposes `vehiclePositions: false` and never
creates a `PositionKind.GPS` value.

Each call has a 2 s connect and 3.5 s request/socket bound. A JVM-wide
fail-fast budget permits at most two concurrent upstream attempts and 24 starts
per rolling minute, counting retries; excess work becomes normalized 429 rather
than queueing. Successful response bodies are capped at 256 KiB before JSON
deserialization. It retries only transient transport failures and 408, 429,
500, 502, 503, or 504, at most twice with bounded backoff; a long `Retry-After`
is returned as a normalized 429 instead of sleeping unboundedly. Cancellations
are rethrown. Safe normalized errors become the BFF's documented
400/404/429/502/503/504 responses.

For an eligible outage (network/timeout/429/5xx), the server may return a
bounded in-memory last-known-good arrivals or journey page for the exact same
effective request for up to ten minutes. Arrival keys include the raw catalog
stop ID, effective bounded limit, and normalized locale; journey keys include
both points, departure instant, locale, requested/effective transfer bounds.
Only that response has `stale: true`; its `observedAt` stays the timestamp taken
after the successful body read, decode, mapping, and validation. LKG age is
measured from that same timestamp. A malformed upstream success is a 502 and is
never converted to stale data.

Arrival mapping is intentionally conservative: because this query uses
`arriveBy=false`, `expectedAt` comes only from MOTIS `place.departure` and
`scheduledAt` from `place.scheduledDeparture`. `realtime` comes from
`StopTime.realTime`, cancellation combines event, trip, and place flags, and
each item is `AGGREGATOR_REALTIME` only if realtime; otherwise it is
`SCHEDULE`. The upstream `source` filename/line is discarded. Journey pages
include normalized `source`, `realtime`, `observedAt`, and `stale` metadata.

## Client attribution

Effective Tbilisi city metadata includes visible, provider-neutral links for
**Transitous sources** at `https://transitous.org/sources/` and
**© OpenStreetMap contributors (ODbL)** at
`https://www.openstreetmap.org/copyright`. The shared Compose City Selection
screen renders these links before a city is selected, including when that city
is unavailable and non-selectable; Map repeats them for a selected city. Both
surfaces use accessible link semantics and stable city-scoped automation IDs.
Do not replace the visible credits with a hidden legal page or add a provider
DTO to mobile code.

## Observability, probes, and safety latches

Use [the BFF observability runbook](bff-observability.md) before enabling
scrapes or probes. `/metrics` is private deployment infrastructure, never a
public client endpoint; it exports only finite labels and no contact,
approval, target ID, URL, query, coordinate, request ID, response body, or
error text. The monitoring backend/receiver, private ingress rule, production
probe target, hosted-use approval, and multi-replica aggregate traffic limit
are external operator inputs, not defaults supplied by this repository.

`BFF_PROBES_ENABLED` remains false by default. An enabled Transitous probe must
use `TRANSITOUS_PROBE_STOP_ID`, an already operator-approved server-only member
of `TRANSITOUS_TBILISI_STOP_IDS`; it shares the exact retry/rate/circuit path
and bypasses LKG for fresh-success evaluation. Set
`TRANSITOUS_PROBE_REALTIME_EXPECTED=false` for schedule-only arrivals; the
generic `officialArrivals=false` contract must not cause a perpetual realtime
alarm.

Production Transitous activation also requires a durable
`BFF_SCHEMA_INTERLOCK_ENABLED=true`. Repeated synthetic-probe JSON decode or
normalized-schema failures atomically latch only that capability, preventing
further provider calls. It does not silently recover after restart. Follow the
new-revision acknowledgement/evidence procedure in
[bff-observability.md](bff-observability.md) to recover, or use a new control
revision as the immediate kill switch/rollback.

## Operating checks

Before enabling real traffic, validate only through an operator-approved manual
exercise of the official hosted endpoint; routine CI must not contact it:

1. Start with activation/config control disabled; assert `/healthz` is not
   ready and no hosted requests occur.
2. Enable `tbilisi` arrivals only; assert `/v1/cities` reports
   `arrivals: true`, `officialArrivals: false`, `vehiclePositions: false`, and
   the two attributions.
3. Disable arrivals in a new control revision; assert arrival requests return
   501 with no upstream request.
4. Keep routing approval absent; assert `tripPlanning: false` and no `/plan`
   request. Repeat only with written routing approval.
5. Simulate 429, timeout, 5xx, and malformed JSON. Verify bounded retry,
   `Retry-After`, normalized safe error, LKG `stale` behavior, and that logs do
   not contain payloads, upstream URLs, contact reference, or source metadata.
