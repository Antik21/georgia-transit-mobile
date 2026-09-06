# Libraries and licenses

`gradle/libs.versions.toml` is the only dependency/version declaration point. Approved families are Compose/Material, Navigation 3/lifecycle, Koin, Orbit, coroutines/serialization/datetime/immutable collections, and Ktor client engines.

Before adding/upgrading, confirm no existing API fits; check KMP targets/minimum OS, compatibility, maintenance, transitive graph, license/notices, and production cost; then compile both targets. Parallel libraries for the same concern require an ADR.

No map SDK or tile provider is approved by this shell. A future choice must be open-source or have a permanent free production grant and separately document tile/API cost, quotas, attribution, privacy, and production rights. Community OSM tile endpoints are not production infrastructure by default.

