# ADR 0026 — Kutaisi AZRY transit feed

- Status: Accepted for implementation
- Date: 2026-11-19
- Extends [ADR 0024](0024-kutaisi-osm-stop-catalog.md). The planning decision is
  superseded by [ADR 0028](0028-kutaisi-untimed-journeys.md).

## Decision

- Register a separate AZRY adapter for Kutaisi when the operator enables it and supplies
  `PIS_GATEWAY_KEY` as a server runtime secret. Restrict requests to the known HTTPS
  gateway and prevent credential forwarding on redirects. Keep AZRY and the static OSM
  adapter mutually exclusive as the authoritative source for the city.
- Keep the product map, existing tile proxy and normal city capability control. Production
  activation requires the existing durable schema interlock. The key, AZRY payloads, and
  AZRY-specific fields stay behind the BFF.
- Advertise routes, stops, geometry, official stop predictions, GPS, and date-based timetables
  through the city snapshot. The optional `schedules` capability defaults to false for older
  documents and cities. The endpoint uses the local service date and returns UTC times with
  the service time zone. Keep the journey planner disabled for Kutaisi.
- Use the server's snapshot receipt time for data freshness. Vehicle records contain no
  GPS capture time or trip ID in the observed feed. Match predictions to the unique
  route serving the stop, using the pattern when necessary. Omit unmatched rows.
- Reuse the route and map screens and the selected city's capabilities. Scheduled rows in
  the existing stop sheet derive a countdown from the scheduled time and retain the schedule
  label. The full direction timetable is available through the BFF.

## Operations

Review production use and refresh limits with the data provider before publishing. Keep
AZRY credentials in the hosting secret store and use the current operator document to add
Kutaisi alongside Batumi. The adapter performs only read requests, with four concurrent
requests and at most 120 request starts per minute per BFF instance. Route catalogues and
schedules use bounded caches, and the vehicle cache shares one snapshot across the variants
of a route. The sample operator document enables only the capabilities supplied by AZRY.
