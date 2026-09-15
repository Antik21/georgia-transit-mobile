# ADR 0015: Public OpenStreetMap raster basemap by default

- Status: Accepted
- Date: 2026-09-15
- Supersedes: the production public-tile rejection in ADR 0002 and map-proxy-disabled decision in
  ADR 0014

## Context

Sandbox and Prod already render transit routes, stops, and live vehicles with MapLibre, but the
production composition falls back to a source-free background. The product owner accepts the
operational risk of a best-effort public basemap for the current development and early-production
stage and prefers to respond to capacity or availability problems when they occur.

OpenStreetMap data is available under ODbL, while the public `tile.openstreetmap.org` service is a
donation-funded, capacity-limited service with no SLA. Its policy permits ordinary interactive map
viewing but requires visible attribution, an identifiable User-Agent, compliant caching, and no
bulk download, prefetch, or offline feature. Access may be limited or withdrawn without notice.

## Decision

- Enable the existing same-origin BFF raster style and tile routes in Sandbox and Prod.
- Configure production with `https://tile.openstreetmap.org/{z}/{x}/{y}.png` and expose only the
  Render BFF origin to mobile clients.
- Send a stable `GeorgiaTransit` User-Agent with a public repository contact URL.
- Cache successful tiles in the BFF and advertise a seven-day client cache lifetime. Do not add
  bulk download, region prefetch, or offline map support while this source is active.
- Restrict tile coordinates to the Batumi/Adjara operating area and zooms 8–19, bound cache memory
  by bytes, cap concurrency, and reject upstream cache misses after the global request budget is
  exhausted. These controls prevent the public BFF from becoming an unrestricted tile relay.
- Display and link `© OpenStreetMap contributors` through the MapLibre source attribution.
- Keep the source configurable on the BFF. Mobile binaries contain neither the upstream URL nor a
  provider key, and the local source-free style remains the failure fallback.
- Treat the public service as a zero-fee, best-effort dependency rather than an SLA-backed service.
  No GitHub or Render secret is required.

## Consequences

- Both shipped mobile compositions request `/v1/map/style.json` from their configured BFF by
  default and display streets and place context when the upstream is available.
- A Render restart clears the small in-memory BFF tile cache, and public OSM can throttle or block
  traffic. A map-tile failure must not remove transit geometry or live vehicle data.
- The long-term escape hatch remains operator-hosted Georgia tiles generated from OSM data, or a
  separately reviewed hosted provider. That work is triggered by observed reliability, traffic,
  policy, offline, or performance needs.
- OSM tile policy and attribution requirements must be rechecked before adding prefetch, offline
  regions, a CDN, a different proxy topology, or materially increasing traffic.

## Verification

```text
./gradlew --no-daemon --no-build-cache :transitBff:check :shared:allTests
render blueprints validate
curl https://antik21-georgia-transit-bff.onrender.com/v1/map/style.json
```

Exercise real tiles only through a normal interactive emulator/device viewport; CI and headless
smoke checks validate the style contract without requesting public OSM tiles.

## References

- [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
- [OpenStreetMap copyright and ODbL attribution](https://www.openstreetmap.org/copyright)
