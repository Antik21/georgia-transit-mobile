# ADR 0024 — Kutaisi OSM stop catalog

- Status: Accepted
- Date: 2026-11-19
- Extends [ADR 0015](0015-public-osm-raster-basemap.md) with one opt-in operating area.

## Context

OpenStreetMap provides bus stop locations and labels for Kutaisi. The OSM bus relations reviewed
against the municipal passenger site have missing stops, different variants or sections, or
different terminal definitions. See the [data review](../development/kutaisi-integration.md).

## Decision

- Bundle a reviewed and reproducible extract inside the Kutaisi BFF adapter. Serve it through
  the normalized stop directory and nearby search. Keep the data available under ODbL with
  the extract's attribution and source notice. Existing clients show the source link from
  the normalized city attribution.
- Register the adapter only with an explicit server opt-in. Require the existing capability
  control document to expose the city, and make stops the sole intrinsic capability.
- Include a bounded Kutaisi raster source in the existing BFF style, and allow tile requests
  for that area while the effective Kutaisi stop capability is enabled. Keep the Batumi source
  and existing global tile cache, concurrency and request budget. The same tile template and
  attribution apply to both areas. The tile proxy remains a best-effort dependency.
- Review each new stop snapshot before bundling it. For later capabilities, require source
  evidence for the active service, complete directions and geometry, and applicable reuse terms.

## Consequences

The map and nearest-stop search use a dated catalog that can have coverage gaps and incomplete
labels. The BFF can serve the catalog without an upstream request. Interactive basemap requests
reuse the existing rate and cache limits and remain subject to the [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/).
