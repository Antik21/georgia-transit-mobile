# Libraries and licenses

`gradle/libs.versions.toml` is the only dependency/version declaration point. Approved families are Compose/Material, Navigation 3/lifecycle, Koin, Orbit, coroutines/serialization/datetime/immutable collections, and Ktor client engines.

Before adding/upgrading, confirm no existing API fits; check KMP targets/minimum OS, compatibility, maintenance, transitive graph, license/notices, and production cost; then compile both targets. Parallel libraries for the same concern require an ADR.

MapLibre Native is the approved renderer. ADR 0015 temporarily approves public OpenStreetMap raster
tiles for normal interactive Sandbox and Prod use through the BFF, with visible attribution, an
identifiable User-Agent, seven-day caching, no prefetch/offline downloads, and an accepted lack of
SLA. Any replacement provider still requires a separate review of cost, quota, attribution, privacy,
and production rights.
