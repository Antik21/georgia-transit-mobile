# Batumi Theta API evaluation

**Evaluation date:** 2026-09-10

**Decision:** **NO-GO** for DEN-55 and for enabling any production Batumi
capability.

The published examples and public responses are sufficient to inform a future
experimental, server-side parser design after permission has been obtained.
They are not sufficient evidence for a production-ready integration. Anonymous
GET access is an observation, not permission to reuse, operate against, cache,
or redistribute the service or its data.

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

## Missing production evidence

No `ETag` was observed on successful `/api` responses, and no rate-limit
response or `Retry-After` was observed. QPS limits, a service-level objective
or availability commitment, support contact, data-retention terms, and a
revocation process were not provided or found in the sources reviewed for this
evaluation. No stress test was performed. The brief public samples also do not
establish valid-empty or night-time behavior, freshness semantics, or an
upstream observation timestamp.

Consequently, the following required gates remain open:

1. Establish the legal operator identity for the origin and data.
2. Obtain written permission or a license covering reuse and redistribution,
   with required attribution recorded and approved.
3. Obtain an approved QPS and cache policy.
4. Obtain an SLA or operational contact and a documented revocation process.
5. Collect time-separated contract samples, including valid-empty and
   night-time behavior.
6. Establish data-freshness and timestamp semantics suitable for realtime
   presentation.

All gates must be independently reviewed before a Batumi adapter may be
registered, and before the existing capability control can make Batumi
effective. An unreviewed adapter, a reachable endpoint, or a public client
repository is not a substitute for any gate.

## Conditions for a future experimental adapter

If written permission later authorizes a limited experimental implementation,
it must retain the existing Transit BFF boundary and remain disabled until a
separate production review. At minimum, it must:

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
- keep `getLiveData`, arrivals, and trip planning disabled; and
- keep Batumi absent from effective cities until legal, operational, and
  production-contract review is complete.

These constraints do not approve traffic to the origin. They describe the
minimum safety envelope for a later, expressly authorized experiment.
