# Libraries and licenses

`gradle/libs.versions.toml` is the only dependency/version declaration point. Approved families are Compose/Material, Navigation 3/lifecycle, Koin, Orbit, coroutines/serialization/datetime/immutable collections, and Ktor client engines.

Before adding/upgrading, confirm no existing API fits; check KMP targets/minimum OS, compatibility, maintenance, transitive graph, license/notices, and production cost; then compile both targets. Parallel libraries for the same concern require an ADR.

MapLibre Native is the approved renderer. ADR 0015 temporarily approves public OpenStreetMap raster
tiles for normal interactive Sandbox and Prod use through the BFF, with visible attribution, an
identifiable User-Agent, seven-day caching, no prefetch/offline downloads, and an accepted lack of
SLA. Any replacement provider still requires a separate review of cost, quota, attribution, privacy,
and production rights.

## Web client (`webApp`)

`webApp/package.json` and its lockfile are the only npm declaration point, scoped to the browser
client from [ADR 0020](../../docs/adr/0020-telegram-mini-app-web-client.md). Shared behaviour still
comes from `:shared` through Kotlin/JS; npm packages may not add a second state, networking, or
navigation stack. Versions are pinned exactly and upgraded under the same review as above.

| package | role | license |
| --- | --- | --- |
| `maplibre-gl` | browser map renderer (counterpart of MapLibre Native) | BSD-3-Clause |
| `vite` | dev server and bundler (dev only) | MIT |
| `typescript` | type checking (dev only) | Apache-2.0 |

Telegram's WebApp script is loaded from `telegram.org` as the platform requires; it is not
bundled or redistributed, and its use is governed by the Telegram Bot Platform terms. The client
reads no Telegram identity data.
