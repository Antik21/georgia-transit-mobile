# Georgia Transit Mobile

Kotlin Multiplatform shell for a Georgia public-transit app. Shared Compose UI currently demonstrates three connected screens: city selection, a local MapLibre renderer prototype, and multi-route selection. Android and iOS hosts render the same common UI.

## Stack

- Kotlin 2.4.10, Compose Multiplatform 1.12.0 and Material 3
- Navigation 3, common lifecycle ViewModels, Koin and Orbit MVI
- Coroutines, serialization, datetime and immutable collections
- Ktor client with OkHttp on Android and Darwin on iOS
- Separate Java 17/Kotlin JVM Transit BFF: Ktor 3.3.3 Netty and kotlinx serialization
- MapLibre Native: Android OpenGL SDK 13.6.0 and iOS SPM product `MapLibre` 6.29.0

## Maps

The screen uses a same-BFF MapLibre style by default. The BFF endpoints
`/v1/map/style.json` and `/v1/map/tiles/{z}/{x}/{y}.png` proxy the public OpenStreetMap raster
tiles configured by the operator; the generated style contains only BFF tile URLs and the required
OpenStreetMap attribution, never the upstream URL. Both Sandbox and Prod therefore show a basemap
without an API key. The bundled, source-free local style remains an emergency fallback when map
assets are disabled or unavailable. Stops, vehicles, and route polylines continue to come only from
the shared, SDK-free typed render state, and synthetic transit data is never presented as real data.

Public OSM tiles are a best-effort dependency with no SLA. The proxy identifies Georgia Transit,
serves a seven-day cache policy, does not prefetch or offer offline downloads, and retains visible
OSM attribution. Operator-owned Georgia vector tiles remain the long-term escape hatch if public
capacity or policy becomes a product problem. See [ADR 0002](docs/adr/0002-maplibre-native-and-map-assets.md)
for the renderer and owned-asset direction and
[ADR 0015](docs/adr/0015-public-osm-raster-basemap.md) for the accepted temporary production source.

Android version declarations remain in `gradle/libs.versions.toml`. iOS uses a
direct Xcode Swift Package Manager dependency instead of CocoaPods; its exact
MapLibre `6.29.0` package reference and
`iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`
are the source of truth because Gradle does not resolve Swift packages for the
direct `embedAndSignAppleFrameworkForXcode` host.

## Startup and selected-city recovery

Both native hosts begin with the same plain `#F7FAF9` launch background; the
shared Compose splash owns all visible loading and recovery content. It has no
native logo or text duplicate and adds no artificial visibility delay.

Before reading the city snapshot, bootstrap loads a narrow non-secret runtime
configuration from an Android or iOS host adapter. The current adapters return
only the shared five-second bootstrap bound and the compatible selected-city
cache schema set (version 1). They do not contain a BFF URL, provider URL,
provider identifier, credential, or other secret. Missing or invalid settings
end at the splash retry state. The configuration source itself has a separate,
trusted one-second guard before those loaded settings are accepted; it cannot be
extended by an invalid configuration value.

With valid settings, bootstrap validates the platform-stored selected-city
snapshot. Android stores the full city record (including its capabilities) in
`SharedPreferences`; iOS stores the same record in `NSUserDefaults`. A first
launch, removed/disabled city, malformed cache, or incompatible schema opens
City Selection directly. City Selection owns the initial `/v1/cities` refresh
and exposes loading, empty, retryable-error, and stale-offline states without
turning a first-choice catalogue failure into a Splash error. A matching cached
city is revalidated against the current snapshot before Map opens; if that
refresh fails or times out, only a structurally valid, enabled cached city can
open Map offline. Read/write/clear storage failures degrade only durable
restoration: they cannot block the current City Selection or Map transition.

Verify startup explicitly on both platforms:

1. Cold start after clearing app data (Android) or deleting/reinstalling the
   app (iOS): City Selection is shown.
2. Select an enabled city (Batumi in Prod), continue to Map, terminate the app,
   then cold start again: Map restores the selected city.
3. Put the app in the background and return while on City Selection, Map, and
   Routes: the existing common navigation state remains valid for the restored
   city.

The production Koin binding is the shared BFF repository. Preview data remains
available only for Compose previews, tests, or explicit local construction; it
is never a production fallback.

## Mobile environments, BFF endpoint, and offline catalog cache

Both native hosts expose exactly two application environments. `Sandbox` uses local development
networking and is installed with a distinct name and bundle/application ID; `Prod` uses the
non-secret HTTPS origin `https://antik21-georgia-transit-bff.onrender.com`. Neither environment
contains a provider URL or provider key, and preview data is never a runtime fallback.

Android builds `sandboxDebug` with `./gradlew :androidApp:assembleSandbox` and `prodRelease` with
`./gradlew :androidApp:assembleProd`. Sandbox defaults to `http://10.0.2.2:8080` in the emulator or
`http://127.0.0.1:8080` on a physical device through `adb reverse tcp:8080 tcp:8080`. A private LAN
origin can be supplied locally with `-PSANDBOX_BFF_BASE_URL=http://192.168.x.y:8080`. iOS provides
the shared `Sandbox` and `Prod` schemes. Sandbox defaults to `http://127.0.0.1:8080`; create the
ignored `iosApp/Configuration/Sandbox.local.xcconfig` with a `BFF_BASE_URL` override for a physical
device on the same network.

Plain HTTP is accepted only by Sandbox composition and only for loopback, RFC 1918/link-local,
IPv6 private/link-local, or `.local` hosts. Public HTTP and malformed origins fail closed. Prod
pins HTTPS independently in its native composition and packages neither cleartext exceptions nor
the local network inspector.

The common Ktor client applies a five-second connect bound, eight-second bounds
for vehicles/arrivals, and 20-second bounds for directory and journey calls.
It makes at most two GET retries, only for transport timeouts/connect failures
or 408/429/502/503/504, using exponential backoff with jitter and a bounded
`Retry-After`. BFF error envelopes map to typed domain failures; cancellation is
always rethrown.

Offline durable last-known-good data is intentionally narrow: only the
city/capability snapshot and route-list snapshots are persisted. Android uses
`SharedPreferences`; iOS uses `NSUserDefaults`. Entries are schema-versioned,
size-bounded, corruption-evicted, and limited to 12 route lists. Route keys
include city, locale, and mode. City/capability snapshots retain their one-hour
freshness policy; route-list catalogs are fresh only when their validation age
is strictly less than 48 hours, and revalidate at age 48 hours or later without
deleting LKG. A route-list `304` retains its payload and original fetch time,
then advances validation time (and adopts a response ETag when supplied). On
transient transport/429/502/503/504 failures, stale LKG is returned with
explicit failure metadata. A cached route list is accepted only when its
embedded request exactly matches, every route belongs to that request's city,
and a mode-filtered request contains only that mode; otherwise it is evicted.
The initial off-UI hydration scans at most the 12 bounded route entries and
publishes each valid eligible locale/mode variant into the immutable snapshot,
so the default route snapshot is available before a route network response. A
valid cached city snapshot prevents publishing and evicts variants for absent
or route-disabled cities. A route-list `CITY_NOT_FOUND` or
`CAPABILITY_NOT_AVAILABLE` response invalidates every bounded locale/mode
variant and in-memory snapshot for that typed city; it remains a typed failure,
not a future `CacheValid` result. A provider-ID-change response from every
city-scoped BFF endpoint that declares it has the same city-wide invalidation
without affecting another city. A route-list 200 with a city or mode mismatch
fails closed without replacing LKG or its timestamps. Stops, shapes, realtime
vehicles, arrivals, and journeys are never durably stored.

## Transit BFF

`:transitBff` is a standalone JVM server module; it does not enter `shared`,
Android, or iOS. It exposes the normalized, capability-driven `/v1` contract in
[the OpenAPI 3.1 document](docs/openapi/transit-bff-v1.yaml). The server has a
city-scoped provider adapter boundary: provider DTOs, URLs, credentials, and
secrets must stay inside a reviewed server adapter and operator secret manager.
No mobile app calls a provider directly.

The synthetic `demo` city is enabled only with both `BFF_MODE=development` and
`BFF_FIXTURES_ENABLED=true`; it is useful for local contract development, but
is not real provider readiness. `/v1/cities` labels it
`DEVELOPMENT_FIXTURE`/`FIXTURE` and always returns all seven granular capability
booleans. Kutaisi remains absent because it has no reviewed adapter; an absent city returns
`CITY_NOT_FOUND`. Batumi is available through its reviewed two-origin adapter only after explicit
operator activation and capability-control approval. Trip planning is capability-gated for every
configured city. In `production`, fixtures are rejected and startup fails closed when no reviewed
adapter has been explicitly activated.

The BFF also contains a server-only, disabled-by-default Transitous Tbilisi
best-effort adapter for arrivals/schedule and approved routing. It does not add
provider types, URLs, or credentials to mobile code, does not use
`/api/v6/map/trips`, and never represents its data as official arrivals or GPS
vehicle positions. Hosted requests are impossible unless the deployer supplies
explicit policy-eligibility evidence, a meaningful contact acknowledgement, a
release version, and an operator-owned control document. Routing has a separate
written-upstream-approval gate. Transitous policy limits hosted API use to FOSS,
non-commercial, light use; commercial use is forbidden and routing/heavy or
multi-user use needs operator approval. MOTIS's MIT software licence does not
grant hosted API/data rights. See the [Transitous operator runbook](docs/development/transitous-fallback.md)
and [ADR 0006](docs/adr/0006-transitous-best-effort-fallback.md); production
activation remains blocked without that written eligibility/approval.

The BFF also contains a separate server-only, disabled-by-default TTC Tbilisi
adapter. It is registered only after an explicit HTTPS endpoint and nonblank
credential arrive through the deployment secret boundary, and production also
requires the existing schema interlock plus capability-control document. TTC
and Transitous cannot both own Tbilisi. TTC raw IDs, provider DTOs, endpoint,
credential, and request headers remain inside the BFF. See the
[TTC operator runbook](docs/development/ttc-adapter.md) and its disabled
[capability-control example](transitBff/ttc-capability-control.example.json).

Batumi's composed adapter is disabled by default and can be enabled for production only with an
explicit operator acknowledgement, the durable schema interlock, and a private capability-control
document. The approval and acknowledgement cover exactly the fixed Theta catalog origin
`https://thetamaps.site:54321/api` and the hard-coded BatBus city-wide live endpoint
`https://batbus.app/api/getAllBuses`; neither currently requires credentials. A single production
instance polls the live endpoint once every five seconds and refreshes the healthy catalog at most
once every ten minutes. The city is exposed as `PRODUCTION_READY`/`REVIEWED_ADAPTER`; routes, stops,
geometry, vehicle positions, and clearly labelled approximate arrivals are available, while
official arrivals and trip planning remain disabled. See the
[Batumi production runbook](docs/development/batumi-theta.md) and
[ADR 0013](docs/adr/0013-batumi-theta-production-approval.md).

For a future reviewed adapter, an operator-owned JSON capability document can
atomically enable/disable cities and individual features at runtime. The BFF
strictly validates and polls the document, applies an immutable effective
snapshot, retains bounded local last-known-good history for exact rollback, and
logs redacted audit evidence. A missing/invalid initial production document is
closed; later malformed updates retain the last accepted snapshot. The BFF
provides no admin mutation endpoint. See the
[capability-control runbook](docs/development/bff-capability-control.md),
the [Transitous operator runbook](docs/development/transitous-fallback.md),
[`transitBff/.env.example`](transitBff/.env.example), and
[ADR 0004](docs/adr/0004-runtime-capability-control-plane.md). Mobile BFF
client/UI consumption is owned by DEN-49/DEN-47; this server control plane does
not introduce a mobile configuration or networking stack.

Start the development fixture without storing any environment file in the
repository. Leave the server process running in one terminal, then use a
second terminal for requests:

```text
# terminal 1
BFF_MODE=development BFF_FIXTURES_ENABLED=true ./gradlew :transitBff:run

# terminal 2, after the server reports that it has started
curl -i http://127.0.0.1:8080/healthz
curl -i 'http://127.0.0.1:8080/v1/cities/demo/routes?locale=en'
```

[`transitBff/.env.example`](transitBff/.env.example) documents the accepted
environment variable names, conservative cache defaults, and non-secret
placeholders. `BFF_HOST`, `BFF_PORT`, `BFF_DIRECTORY_CACHE_TTL_SECONDS`
(1–6 hours), `BFF_SHAPE_CACHE_TTL_SECONDS` (1–24 hours), and
`BFF_REALTIME_SINGLE_FLIGHT_SECONDS`, control-document path/state pairing,
poll interval (5–300 seconds), and history bound (2–50 revisions) are parsed
strictly at startup. Render's `PORT` is used when the explicit `BFF_PORT`
override is absent. `healthz` reports effective readiness and mode without
configuration or credential details. Every response has `X-Request-ID`; errors use the documented
`{error:{code,message,retryAfterSeconds?,requestId}}` envelope.

Build a portable local distribution with:

```text
./gradlew :transitBff:check :transitBff:installDist
BFF_MODE=development BFF_FIXTURES_ENABLED=true \
  transitBff/build/install/transitBff/bin/transitBff
```

`installDist` remains the portable application artifact. Production packages it
in the checked-in multi-stage Docker image and deploys the Batumi-only BFF through
the Git-backed [`render.yaml`](render.yaml) Blueprint after successful GitHub checks.
The Render web service uses a persistent disk for capability history and durable
schema interlocks; applying it creates a paid resource and is therefore a separate
operator action. The Blueprint needs no deploy-hook secret, and current Batumi
origins need no credentials. Future provider credentials belong only in Render's
runtime secret store, never in GitHub build secrets, mobile artifacts, or image
layers. See the [Render deployment runbook](docs/development/render-deployment.md),
[ADR 0003](docs/adr/0003-transit-bff-runtime-and-provider-boundary.md) for the
production boundary and caching/single-flight constraints, and
[ADR 0004](docs/adr/0004-runtime-capability-control-plane.md) for kill switches,
audit, last-known-good state, and rollback. [ADR 0014](docs/adr/0014-render-production-deployment.md)
records the production hosting and durable-storage decision.

## Build and quality checks

Prerequisites:

- JDK 17.
- Android SDK platform 37.0 and build tools 37.0.0. Set `ANDROID_HOME` to the SDK root, or put the local SDK path in an uncommitted `local.properties` file such as `sdk.dir=/absolute/path/to/android-sdk`.
- For Apple targets, macOS with Xcode and an installed iOS Simulator runtime on Apple Silicon. The shared `iosSimulatorArm64` target needs an `arm64` host; `iosX64` is available only on Intel macOS.

`local.properties`, `ANDROID_HOME` values, signing material, and provider configuration are local-only. Do not commit them. This shell does not require secrets or signing to run its quality gates.

Run the same quality checks locally with:

```text
./gradlew --no-daemon --no-build-cache spotlessCheck
./gradlew --no-daemon --no-build-cache :transitBff:compileKotlin :transitBff:check :transitBff:installDist
./gradlew --no-daemon --no-build-cache :shared:testAndroidHostTest :shared:checkKotlinGradlePluginConfigurationErrors :androidApp:lintSandboxDebug :androidApp:lintProdRelease :androidApp:assembleSandbox :androidApp:assembleProd
SIMULATOR_UDID="<an available iOS Simulator UDID from xcrun simctl list devices available>"
./gradlew --no-daemon --no-build-cache :shared:iosSimulatorArm64Test --device "$SIMULATOR_UDID" :shared:checkXcodeProjectConfiguration
xcodebuild -project iosApp/iosApp.xcodeproj -scheme Sandbox -sdk iphonesimulator -configuration Sandbox -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project iosApp/iosApp.xcodeproj -scheme Prod -sdk iphonesimulator -configuration Prod -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
git diff --check
```

The two iOS commands require macOS, Xcode, and a simulator runtime. The iOS test task's supported `--device` option binds it to the selected simulator UDID; CI creates, boots, and deletes that temporary device for runtime tests. The unsigned Xcode compile/link gate uses Xcode's supported generic iOS Simulator destination. On Windows, Android work is available but cannot provide iOS simulator evidence.

Formatting is enforced with the Apache-2.0, no-cost Spotless Gradle plugin (`8.10.2`), which is a build-only dependency. To avoid unrelated rewrapping/import churn in the existing Kotlin baseline, its current rule set checks trailing whitespace in Kotlin and Gradle Kotlin files, and trailing whitespace plus one final newline in repository docs, the BFF sample environment file, OpenAPI YAML, and GitHub Actions YAML. It does not apply a Kotlin style formatter or change runtime dependencies.

## Continuous integration

GitHub Actions runs four stable checks for pushes and pull requests targeting `main`:

- `Quality` checks formatting.
- `Transit BFF` checks formatting plus the BFF check and build surfaces.
- `Android` runs shared Android-host unit tests, Kotlin Gradle configuration checks, lint, and builds both Sandbox and Prod.
- `iOS` validates an Apple Silicon runner, runs arm64 simulator tests against an explicitly created temporary simulator and the Xcode configuration check, then builds unsigned Sandbox and Prod apps for Xcode's generic iOS Simulator destination.

The workflow gives `gradle/actions/setup-gradle` sole ownership of the Gradle User Home cache. Pull requests and non-`main` branches restore caches read-only; `main` may write them after success. It does not cache repository `.gradle`, build outputs, `DerivedData`, `local.properties`, secret/configuration files, or generated app artifacts. Gradle invocations disable the build cache so a cached artifact cannot hide a generation failure.

Repository administrators should configure branch protection to require the exact `Quality`, `Transit BFF`, `Android`, and `iOS` checks before merging into `main`. This repository documentation does not claim that protection is already enabled.

The cross-platform stable-ID scenario is in ui-tests/maestro/flows/shell-smoke.yaml.

## Documentation

Start with [AGENTS.md](AGENTS.md), the [toolchain](.agents/docs/01-stack-toolchain.md), [architecture boundaries](.agents/docs/02-architecture-boundaries.md), [ADR 0001](docs/adr/0001-kmp-shell.md), [ADR 0002](docs/adr/0002-maplibre-native-and-map-assets.md), and [ADR 0003](docs/adr/0003-transit-bff-runtime-and-provider-boundary.md).

For the Sandbox-only Android/iOS local HTTP inspector, including smoke, clear,
privacy, license, and Prod-absence checks, see
[network inspector development guide](docs/development/network-inspector.md).
