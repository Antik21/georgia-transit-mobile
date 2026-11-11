# ADR 0020 — Telegram Mini App web client

Status: Accepted

## Context

[ADR 0019](0019-browser-ready-shared-client.md) introduced a Kotlin/JS runtime with city selection, stop selection and arrivals. The first web slice needs a browser UI, a map renderer and a host that can run inside Telegram's WebView.

## Decision

- Put the browser entry point in `webApp`. Use TypeScript and DOM rendering for the shell, city and route pickers, nearby stops and ETA. The Kotlin/JS ESM boundary remains the owner of session and feature state. It exposes map camera and stop coordinates and forwards viewport events to the existing `MapViewModel`.
- Use MapLibre GL JS under BSD-3-Clause. The map consumes the BFF raster style and tile endpoints with the existing area, zoom, budget and caching controls in [ADR 0015](0015-public-osm-raster-basemap.md). The web UI preserves visible source attribution and can still show nearby stops and ETA if the map is unavailable. No provider URL or token is needed by the browser.
- Serve the static client and `/v1/` on the same HTTPS origin. Forward `/v1/` to the Transit BFF through the host's reverse proxy. The dev server uses that contract on loopback. MapLibre's tile URL is rebased from the BFF's public hostname to the same origin. The application does not add a second transit HTTP client or require global BFF CORS.
- Use Telegram's official WebApp SDK for readiness, expansion, theme and the ETA panel's Back Button. Transit data is public. The app reads no Telegram identity or authorization payload. Browser operation uses the same screens and state.
- Open route selection above the map through the shared navigation policy. The entry-scoped `RoutesViewModel` owns the draft. Confirmation applies the full replacement to `TransitSession`, while cancel and Telegram Back discard the draft. The existing map entry renders the shared geometry and legend for the committed selection.
- Keep live vehicle rendering, location and Telegram bot setup for follow-up work.

## Verification

Build the Kotlin/JS library and the TypeScript web client, then run the Chromium smoke with the local fixture. It exercises city selection and restoration, map and list stop selection, ETA, the Telegram Back Button switching cities, and route search, confirmation, cancel, and restoration. A fixture raster tile and the shared route geometry verify that the production bundle can render both layers. The test uses a same-origin tile fixture.

## References

- [MapLibre GL JS license](https://github.com/maplibre/maplibre-gl-js/blob/main/LICENSE.txt)
- [Telegram Mini Apps](https://core.telegram.org/bots/webapps)
