# ADR 0032 · Localized vector basemap through the BFF

- Status: Accepted
- Date: 2026-11-23
- Supersedes: [ADR 0015](0015-public-osm-raster-basemap.md)
- Related: [ADR 0002](0002-maplibre-native-and-map-assets.md)

## Context

The previous raster tiles contained pre-rendered Georgian street names. Georgia Transit needs
a basemap whose labels follow the Georgian, Russian, or English interface language. The local
comparison led to a choice of an OpenFreeMap style inspired by Natural from VersaTiles.

## Decision

- Use OpenFreeMap's OpenMapTiles vector data with a BFF-owned style inspired by the
  [VersaTiles Natural theme](https://github.com/versatiles-org/versatiles-style#styles-overview).
  The palette, road hierarchy, building treatment, and label contrast match the approved preview.
- Serve `/v1/map/style.json?lang=ka|ru|en`, with Georgian as the default. Look for the chosen language,
  then the Georgian tag, then the local name. Treat empty translated names as missing. Other app
  languages use Georgian labels. All languages share the vector tiles.
- The BFF serves the style independently of the upstream. It follows OpenFreeMap's published
  TileJSON release, validates the template, and downloads bounded, cached tiles and glyphs for
  interactive views. It sends an identifiable User-Agent and forwards no user or device identifiers.
- Retire the raster source and its settings switch. On a style or asset failure, the clients use
  the bundled background and render their existing stops, routes, and vehicles.
- `BFF_MAP_ENABLED=false` disables the map endpoints. The production Blueprint enables them.
  Publish the web bundle with the BFF because the web allowlist changes from raster to vector
  assets. The URL for the MapLibre style remains stable for existing mobile clients.

## Provider review

OpenFreeMap [allows commercial use](https://openfreemap.org/) and provides its public service
without keys, fees, published interactive request quotas, or an SLA. Its [terms of service](https://openfreemap.org/tos/)
prohibit automated data collection except for their specified offline use. This integration proxies
assets requested by interactive views and performs no prefetch or offline downloads. OpenFreeMap
may change or discontinue the service. Its [privacy policy](https://openfreemap.org/privacy/) explains
request logging and Cloudflare processing. Client requests terminate at the BFF, which forwards only
the upstream resource coordinates and normal HTTP cache validators. The style includes OpenFreeMap,
OpenMapTiles, and OpenStreetMap attribution. OpenStreetMap data is available under the
[Open Database License](https://www.openstreetmap.org/copyright). Noto Sans glyphs come from
OpenFreeMap and use the [SIL Open Font License 1.1](https://github.com/hyperknot/openfreemap/blob/main/LICENSE.md#fonts).
The style references the provider's glyph endpoint through the BFF.

## Alternatives and verification

Public OSM vector and VersaTiles data also carry multilingual names. OSMF discourages caching proxies
and VersaTiles requests prior permission for proxying. Self-hosted regional tiles allow direct
control of availability and editions, with hosting and update costs. Keep these as migration options
if OpenFreeMap capacity or availability changes.

Exercise the style, vector tile and glyph contracts with local fakes. Verify the BFF's upstream
connection and render all three languages in a normal interactive map viewport. Check a forced
vector failure and recovery with transit overlays present.
