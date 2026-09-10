# TTC Tbilisi adapter operator runbook

The TTC adapter is a server-only, disabled-by-default `:transitBff` adapter.
It is registered only when startup accepts an explicit activation configuration.
It does not put TTC DTOs, raw identifiers, provider endpoints, credentials, or
request headers into shared/mobile code, normalized API responses, errors,
logs, or metrics.

## Preconditions and activation

Production activation requires all of the following:

- `BFF_MODE=production`, `BFF_FIXTURES_ENABLED=false`, and the existing private
  capability-control document/state-directory pair.
- `BFF_SCHEMA_INTERLOCK_ENABLED=true`, so the deployment remains compatible
  with the BFF's durable schema-safety control plane.
- `TTC_ENABLED=true`.
- `TTC_BASE_URL` supplied by the deployment's secret/environment boundary as
  an explicit HTTPS origin or path prefix. The BFF rejects userinfo, query, or
  fragment components.
- nonblank `TTC_API_KEY` injected by the same protected boundary. Do not place
  it in source control, capability documents, command history, process logs,
  examples, support tickets, or client configuration.

The checked-in [disabled example](../../transitBff/ttc-capability-control.example.json)
is structurally valid but exposes no city. Its capability values, including
`stops: true`, match the TTC adapter's intrinsic support, so an operator can
provision a private physical control file from it, give it a new safe revision,
and atomically set the `tbilisi` entry to `enabled: true` only after deployment
review. To withhold any feature, explicitly reduce that capability to `false`.
Omitting Tbilisi or setting `enabled: false` is the runtime kill switch. A
restart with `TTC_ENABLED=false` removes the adapter entirely.

Do not activate TTC together with the Transitous Tbilisi adapter. Startup
rejects that ambiguous single-city ownership rather than selecting one.

## Boundaries and failure behavior

TTC accepts only `ka` or `en` upstream. A BFF `ru` request uses deterministic
English fallback while preserving the existing `LocalizedText` response shape.
Raw TTC identifiers, including a literal `1:` prefix, are encoded into the
existing four-segment public ID contract and decoded only immediately before an
upstream request. The public `providerId` value is also encoded; no raw TTC ID
is returned.

Only documented TTC v2 GET forms are used:

- bus route directory, bounded bulk stop directory, stop detail/arrival, and stop/route membership;
- direction-specific route polyline and vehicle positions;
- stop arrival times; and
- a leave-now `WALK,BUS` quick plan.

The implementation never attempts a provider call at startup. At request time
it applies bounded request/connect/socket timeouts, a 256 KiB response limit,
two or fewer retries for safe GET failures only, a bounded `Retry-After`, and
a local concurrency/start-rate budget. Cancellation propagates. It does not
decode provider error bodies or include provider request details in public
errors/telemetry. Invalid JSON becomes a safe 502 classification; malformed
required values or unrepresentable normalized data are rejected before caches
or public responses.

Each local concurrency permit spans the response headers, bounded body read,
JSON decode, or cancellation of an error response. Thus active TTC body streams
cannot exceed `TTC_MAXIMUM_CONCURRENT_REQUESTS`.

The TTC positions response has no documented vehicle identifier or observation
timestamp. The BFF keeps a bounded, coroutine-safe, process-local tracker per
route/direction for at most three minutes. It carries an opaque ephemeral ID
only across an unambiguous nearest-neighbour match with distance, speed,
heading, and next-stop guards; colliding or contested records are filtered
rather than assigned a list-index identity. A restart, expiry, or ambiguous
match may create a different ID. `observedAt` is BFF receipt time,
`maxAgeSeconds` is zero, and no response is marked stale. Arrival items claim
`OFFICIAL_REALTIME` only when TTC marks that item realtime; negative or invalid
minutes reject the upstream response.

The leave-now planner accepts only plans whose every leg can be represented
without inventing continuity. Transit endpoint IDs are accepted only when the
documented intermediate-stop list proves both endpoint coordinates, and a
direction is published only when the matching F/B route-stop order proves it
unambiguously. Other incomplete or direction-ambiguous itineraries are omitted;
no last-known-good journey data is kept. Walking duration is derived from the
validated itinerary timestamps and must equal TTC's declared duration.

## Conservative operation

Keep the existing directory cache at its conservative 1–6 hour range and the
shape cache at 1–24 hours. The outer `TransitService` owns those caches and
single-flight behavior; the TTC adapter intentionally adds no cache or
last-known-good store. Nearby stops use one bounded `/stops` operation (with the
same bounded safe-GET retries) under the normal cancellable client budget; there
is no route-by-route directory fan-out or rate-slot waiting. The bulk TTC stop DTO does not contain route
memberships, so its normalized `routeIds` is intentionally empty. A nearby-stop
view can therefore initially omit route badges. Direction-specific route stops
retain their known route membership, while arrivals resolve their normalized
route IDs through TTC's stop-routes form. The checked-in control example keeps
the city disabled while retaining the adapter's intrinsic `stops: true` value,
so setting only `enabled: true` exposes nearby stops; an operator can instead
reduce `stops` through the existing private runtime control document.

`TTC_MAXIMUM_CONCURRENT_REQUESTS` is limited to 1–4 and defaults to 2.
`TTC_MAXIMUM_STARTS_PER_MINUTE` is limited to 1–120 and defaults to 60. Keep
both low unless an operator has written provider approval and observed evidence
for a higher setting. Retry settings are bounded: `TTC_MAXIMUM_RETRIES` is 0–2
and defaults to 1. Timeout settings are validated at startup; use defaults
unless monitoring supports a documented change.

Synthetic probing remains globally disabled by default. If it is enabled,
`TTC_PROBE_STOP_ID` is a server-only raw target and must be secret-managed like
provider configuration; it is never exported. `TTC_PROBE_REALTIME_EXPECTED`
must be true only when a fresh realtime arrival is actually expected. Probe
schema failures use the existing interlock and capability-control recovery
process described in [bff-observability.md](bff-observability.md).
