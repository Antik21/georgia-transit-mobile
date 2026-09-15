# Batumi Theta API evaluation

> Historical evaluation: its 2026-09-10 NO-GO decision was superseded on 2026-09-15 by the
> explicit operator production approval recorded in
> [ADR 0013](../adr/0013-batumi-theta-production-approval.md). The observations and safety
> constraints below remain relevant evidence; the former authorization blocker no longer defines
> the project's production classification. This evaluation covered the Theta catalog and legacy
> route-live endpoints only. The later production approval also covers the distinct, hard-coded
> BatBus bulk live endpoint `https://batbus.app/api/getAllBuses`, evaluated in ADR 0011.

**Evaluation date:** 2026-09-10

**Decision at the time of evaluation:** **NO-GO** for enabling any production Batumi capability. A tightly bounded,
disabled-by-default development implementation may exist only behind the BFF capability control;
it does not authorize traffic to the origin or change this production decision.

At the time of evaluation, the published examples and public responses were sufficient to inform
a future experimental, server-side parser design after permission had been obtained. They were not
sufficient evidence for a production-ready integration. Anonymous GET access alone was an
observation, not permission to reuse, operate against, cache, or redistribute the service or its
data. ADR 0013 records the subsequent operator decision for the bounded two-origin adapter.

## Scope and sources

The evaluation compared these sources:

- the Linear Transit API specification (internal reference);
- the direct Theta origin; and
- the public [Batumi-Buses-v2 client](https://github.com/raf003771-netizen/Batumi-Buses-v2).

The public client demonstrates prior usage, but it routes traffic through an
unverified third-party Cloudflare proxy. Its ownership and operator status are
not confirmed, so it must not be used by the BFF, mobile clients, development
tooling, or probes. The repository declares no license; its source proves usage
only, not rights to the origin, data, or proxy.

## Direct-origin observations

The current direct-origin base prefix is:

```text
https://thetamaps.site:54321/api
```

The bare `/getDbData` and `/getBusLocsOnRoute` paths returned HTML `404`
responses. The paths under the `/api` prefix behaved differently:

| Request | Observed response | Cache observation |
| --- | --- | --- |
| `GET /api/getDbData` | `200` JSON wrapper with `data` | `Cache-Control: public, max-age=600` |
| `GET /api/getBusLocsOnRoute?routeId=<catalog RouteIdGeoGps>` | `200` JSON wrapper with a `data` array | `Cache-Control: public, max-age=4` |

No authentication header, cookie, or API key was observed in these anonymous
GET responses. That absence neither identifies the operator nor grants a right
to automated, production, cached, or redistributed use.

The catalog snapshot was internally consistent at the time of evaluation:

| Entity | Count |
| --- | ---: |
| Routes | 28 |
| Stops | 578 |
| Route geometries | 28 |
| Geometry points | 6,306 |
| Route-status entries | 28 |

The route keys, geometry keys, route-status keys, and route references attached
to stops agreed. This is a useful design signal, not a time-separated contract
guarantee.

## Observed schema

The observed contract differs from earlier assumptions and must not be inferred
from the public client alone.

- Route records use `RouteIdGeoGps`, `RouteNameGeoGps`, `RouteNameKA`,
  `RouteNameEN`, `RouteIsCircle`, and `RouteSortOrder`.
- Stop records use `BusStopIdGeoGps`, `BusStopNumber`, localized names, and
  numeric coordinates. Their route references are object values carrying
  `Status`, `Order`, and time values, rather than a simple route-ID list.
- Route geometry is an array of point objects in the form `[{lat, lon}]`; it
  is not an array of coordinate-pair arrays.
- Observed vehicle objects carry only `Name`, `Lat`, `Lon`, and `Status`.
  There was no `BusId`, timestamp, route, bearing, or next-stop field.

Five probes for one valid catalog route retained four stable opaque `Name`
values and the same vehicle field shape. Response-body hashes changed on the
observed cache cadence, consistent with `max-age=4`. Five additional valid
catalog routes each returned between one and eight vehicles. These samples do
not establish continuity, identity, location freshness, or service coverage.

For an invalid, missing, or blank `routeId`, the endpoint returned HTTP `200`
with `{data:null}` in all observed cases. Therefore the upstream response
cannot currently distinguish an unknown route from a valid route with no
vehicles, and a BFF must not manufacture that distinction.

## Production evidence missing at the time of evaluation

No `ETag` was observed on successful `/api` responses, and no rate-limit
response or `Retry-After` was observed. QPS limits, a service-level objective
or availability commitment, support contact, data-retention terms, and a
revocation process were not provided or found in the sources reviewed for this
evaluation. No stress test was performed. The brief public samples also do not
establish valid-empty or night-time behavior, freshness semantics, or an
upstream observation timestamp.

Consequently, the following gates were recorded as open at the time:

1. Establish the legal operator identity for the origin and data.
2. Obtain written permission or a license covering reuse and redistribution,
   with required attribution recorded and approved.
3. Obtain an approved QPS and cache policy.
4. Obtain an SLA or operational contact and a documented revocation process.
5. Collect time-separated contract samples, including valid-empty and
   night-time behavior.
6. Establish data-freshness and timestamp semantics suitable for realtime
   presentation.

At the time, all gates had to be independently reviewed before a Batumi adapter could be
registered and before capability control could make Batumi effective. ADR 0013 records the later
operator review and production approval; reachability alone remains insufficient for any future
provider decision.

## Conditions originally set for an experimental adapter

The evaluation required any later limited experimental implementation to retain the Transit BFF
boundary and remain disabled until a separate production review. At minimum, it had to:

- run server-side only; mobile clients must never call Theta directly;
- prevalidate each requested route against a BFF-owned directory before the
  route-vehicle request;
- accept the actual observed shapes plus defensive fallbacks for safely
  representable contract variation;
- assign BFF receipt time as `observedAt`, since the observed payload has no
  source timestamp;
- treat `Name` only as a provisional identity, never as a permanent vehicle
  identifier;
- use bounded single-flight and cache behavior, with at most 60 seconds of
  stale data;
- include a schema interlock and immediate capability kill switch;
- never call `getLiveData` or expose trip planning; any BFF-derived ETA must be clearly labelled
  approximate, non-official, and withheld when movement/direction confidence is insufficient; and
- keep Batumi absent from effective cities until legal, operational, and
  production-contract review is complete.

At the time, these constraints did not approve traffic to the Theta origin. They now remain the
minimum safety envelope incorporated into the expressly authorized two-origin production adapter
by ADR 0013.
