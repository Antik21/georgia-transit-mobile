# ADR 0002: MapLibre Native and BFF-controlled map assets

Status: Accepted (version and licensing review: 2026-09-06)

## Context

Georgia Transit needs one map renderer decision for Android and iOS that is
usable by a commercial production app without a mandatory paid plan, map key,
or per-request provider fee. Reviewing only an SDK is insufficient: styles,
glyphs, sprites, tiles, search, attribution, quotas, privacy, and cache policy
also determine whether the result is genuinely operable.

The application boundary remains the Transit BFF. A mobile app must not send
requests to map, tile, geocoding, or transit providers directly, and it must
not contain provider URLs, credentials, or provider-specific DTOs. Public OSM
and other community/demo tile endpoints are intentionally not production
infrastructure.

## Decision

Use the direct native MapLibre Native SDKs behind one narrow Compose
`PlatformMap` expect/actual contract. The common contract carries only a typed
`MapViewport` (`GeoPoint` center and zoom); it has no native SDK type, key,
URL, provider DTO, or Koin/service lookup. Android native code owns
`MapView`/lifecycle/MapLibre layers; Swift owns `MLNMapView`, `MLNStyle`, and
`CLLocationCoordinate2D`, passing generic `UIView` factory/update closures into
the iOS-only composition bridge. This is deliberately not MapLibre Compose.

| Component | Decision | License, price, attribution, quota/ownership |
| --- | --- | --- |
| Android renderer | `org.maplibre.gl:android-sdk-opengl:13.6.0` | [BSD-2-Clause](https://central.sonatype.com/artifact/org.maplibre.gl/android-sdk-opengl/13.6.0); $0 SDK license, no key or mandated map plan. MapLibre attribution UI remains available; source attribution belongs to the selected assets. |
| iOS renderer | SPM `https://github.com/maplibre/maplibre-gl-native-distribution`, exact `6.29.0`, product `MapLibre` | [BSD-2-Clause](https://github.com/maplibre/maplibre-native/blob/ios-v6.29.0/LICENSE.md); $0 SDK license, no key or mandated map plan. The exact version is in Xcode's SPM project reference and checked-in `Package.resolved`, not Gradle, because Swift Package Manager resolves the binary XCFramework for the Swift host. |
| Production style | Versioned, owned style JSON at BFF-controlled `/v1/maps-style/...` | No third-party style is licensed or fetched by the client. Operator-owned, versioned, and quota-controlled; $0 software/API license. Retain all source attributions rendered/linked by the style and attribution manifest. |
| Glyphs | Owned generated PBF glyph sets from [Noto Sans Georgian](https://github.com/notofonts/noto-fonts/blob/main/LICENSE) (or another explicitly reviewed OFL font) | SIL OFL-1.1 font notice is retained; generated files are served by the BFF under operator control. No provider quota and $0 font/API license. |
| Sprites | Owned, versioned sprite JSON/PNG | Original Georgia Transit artwork only unless a separately reviewed asset records its license and attribution. Operator-controlled, no external quota, $0 software/API license. |
| Tiles/data | Georgia-scoped OpenStreetMap pipeline: [OSM data ODbL](https://www.openstreetmap.org/copyright), [Planetiler Apache-2.0](https://github.com/onthegomap/planetiler/blob/main/LICENSE), and [Martin MIT or Apache-2.0](https://github.com/maplibre/martin#license) serving BFF endpoints such as `/v1/maps-tiles/...` | Show/offer the required OSM contributors and ODbL attribution plus any imported-source notices. Tiles are generated, hosted, versioned, cached, and rate-limited by the operator; no public tile endpoint. Software/API license cost is $0. |
| Search/geocoding | MVP has no general geocoder: city, transit-stop, and route search are BFF-owned normalized transit search. If address geocoding is later needed, self-host [Nominatim GPL-3.0](https://github.com/osm-search/Nominatim/blob/master/COPYING) behind the BFF. | Never call public Nominatim. OSM/ODbL attribution still applies to derived results; BFF sets quotas. Nominatim software/API license cost is $0. |

“$0” in this ADR means the software or API license has no mandatory paid plan,
key, or per-request fee. Storage, tile generation, compute, monitoring,
egress, CDN, backups, support, and operations have variable infrastructure
costs and are explicitly **not** claimed to be zero.

### Production path and escape hatch

1. Build a Georgia extract from OSM data using Planetiler; publish only the
   selected, versioned vector tiles and style assets from infrastructure owned
   by Georgia Transit.
2. Martin (or a reviewed replacement) serves tile packages behind the Transit
   BFF. The BFF owns version selection, cache headers, authorization/rate
   limits, attribution manifest, and a provider interface, so a new tile host
   or generator changes server composition rather than the mobile feature API.
3. The app receives only BFF map asset URLs/configuration through a future
   reviewed runtime contract; MapLibre consumes standard style/vector-tile
   formats. No provider secret is placed in either app.

The DEN-75 prototype intentionally does not implement that production path. It
uses a fully local JSON background style and synthetic local GeoJSON marker and
line centered on the selected city. It disables MapLibre connectivity on Android
and uses a bundled iOS style; it has no provider URL/key, remote style/tile,
location permission, stop/vehicle data, BFF request, or production basemap.
Full MapScreen data and production asset delivery remain DEN-58 work.

## Feature assessment

| Capability | Assessment and delivery constraint |
| --- | --- |
| Offline/cache | MapLibre Native has on-device resource caching. Production must set BFF cache/version headers and invalidation policy. A true offline region/package flow needs explicit Georgia extracts, download limits, storage budget, expiry, and attribution retention; it is not supplied by this prototype. |
| Marker/polyline animation | Native source updates and camera animation support smooth marker/polyline updates. Update GeoJSON/vector sources at a bounded cadence, batch changes, and do not create one native view per vehicle. Animation data remains BFF-normalized. |
| Clustering | GeoJSON source clustering is available for smaller local sets; large stop/vehicle sets should be preclustered or tiled by the BFF. Cluster behavior and click expansion remain future product work. |
| Performance | Vector tiles, compact Georgia scope, layer/source grouping, zoom visibility, cache limits, and measured device budgets are required. Benchmark pan/zoom, route redraw, and representative vehicle counts on the supported Android/iOS floors before production rollout. |
| Accessibility | Native map controls/gestures must be exposed to TalkBack/VoiceOver and retain required attribution. The product must also offer accessible non-map stop/route results and textual state; a canvas-only map is never the sole way to use transit data. The prototype marks its native map as a local preview and preserves the common preview note. |

## Consequences and risks

- Direct native SDK adapters avoid a second Compose-specific map stack, but
  Android lifecycle and Swift/UIView interoperability must be compiled on both
  platforms whenever this boundary changes.
- Android lifecycle forwards `onStart`, `onResume`, `onPause`, `onStop`, and
  `onDestroy` idempotently and exactly once per MapView instance. iOS releases
  its `MLNMapView` delegate/location manager when the interop UIView is released.
- The MapLibre SDK AAR declares optional location permissions transitively; the
  app manifest explicitly removes coarse/fine location. No iOS location usage
  description, ATS exception, or location request is added.
- ODbL attribution and any imported-source requirements must be maintained in
  the BFF style/asset manifest. Infrastructure ownership shifts quota and abuse
  protection to operations rather than eliminating cost.
- Version update ownership differs by ecosystem: Android remains in
  `gradle/libs.versions.toml`; the iOS XCFramework must remain exact in the
  Xcode SPM package reference and lockfile because Gradle does not resolve or
  link Swift packages in the direct `embedAndSignAppleFrameworkForXcode` host.

## Alternatives

- **Google Maps SDK:** rejected. Its API-key/billing commercial model does not
  meet this decision's owned-asset, no-mandatory-provider-plan constraint.
- **Mapbox:** rejected. Its token, usage-metered commercial terms, and hosted
  asset model do not meet the no mandatory paid-plan/key/per-request-fee
  requirement.
- **MapTiler:** rejected. Its key/quota/commercial hosting model does not meet
  the owned production asset requirement.
- **Public OSM tiles, public Nominatim, and MapLibre/demo endpoints:** rejected
  for production because community/demo availability and quotas are not a
  Georgia Transit SLA or cost/abuse-control boundary.
- **MapLibre Compose wrapper:** rejected for now. It adds another UI abstraction
  and release cadence without improving the BFF asset boundary; direct native
  SDKs are smaller, explicit adapter surfaces.

## Verification

Implemented verification is local-only:

```text
./gradlew :androidApp:assembleDebug
xcodebuild -resolvePackageDependencies -project iosApp/iosApp.xcodeproj -scheme iosApp
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

The Android and iOS prototypes must render their local background/layers and
move the camera when City Selection changes. No network inspection can show a
provider request because this prototype defines none; production verification
will additionally require BFF-only endpoint checks, attribution review, cache
and offline behavior, performance measurements, accessibility smoke testing,
and operational quota/load testing.
