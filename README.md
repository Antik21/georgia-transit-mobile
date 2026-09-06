# Georgia Transit Mobile

Kotlin Multiplatform shell for a Georgia public-transit app. Shared Compose UI currently demonstrates three connected screens: city selection, a local MapLibre renderer prototype, and multi-route selection. Android and iOS hosts render the same common UI.

## Stack

- Kotlin 2.4.10, Compose Multiplatform 1.12.0 and Material 3
- Navigation 3, common lifecycle ViewModels, Koin and Orbit MVI
- Coroutines, serialization, datetime and immutable collections
- Ktor client with OkHttp on Android and Darwin on iOS
- MapLibre Native: Android OpenGL SDK 13.6.0 and iOS SPM product `MapLibre` 6.29.0

## Maps

The screen currently renders only a local MapLibre JSON background plus a
synthetic city-center marker and short line. It has no production basemap,
transit data, BFF request, provider key, location permission, or remote map
asset. This keeps the renderer/layer/camera boundary verifiable without using a
public/community endpoint.

Production map assets are a future BFF concern: Georgia-scoped OSM data will be
generated with Planetiler, served by owned infrastructure (for example Martin),
and exposed only through BFF-controlled style/tile endpoints. Owned styles,
sprites, and OFL-licensed Noto Sans Georgian glyphs must retain their attribution
manifest. The SDK/software/API license cost is $0; hosting, storage, egress,
CDN, generation, and operations cost are variable. See
[ADR 0002](docs/adr/0002-maplibre-native-and-map-assets.md) for exact licenses,
attribution, quotas, alternatives, cache/offline, accessibility, and geocoding
rules.

Android version declarations remain in `gradle/libs.versions.toml`. iOS uses a
direct Xcode Swift Package Manager dependency instead of CocoaPods; its exact
MapLibre `6.29.0` package reference and
`iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`
are the source of truth because Gradle does not resolve Swift packages for the
direct `embedAndSignAppleFrameworkForXcode` host.

## Build

Prerequisites: JDK 17 and Android SDK 37. Apple builds require macOS, Xcode, and an iOS Simulator runtime.

```text
./gradlew :shared:testAndroidHostTest
./gradlew :androidApp:assembleDebug
./gradlew :shared:iosSimulatorArm64Test
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

On Windows, the Android build and emulator flow are available; iOS simulator execution still requires macOS and Xcode.

The cross-platform stable-ID scenario is in ui-tests/maestro/flows/shell-smoke.yaml.

## Documentation

Start with [AGENTS.md](AGENTS.md), the [toolchain](.agents/docs/01-stack-toolchain.md), [architecture boundaries](.agents/docs/02-architecture-boundaries.md), [ADR 0001](docs/adr/0001-kmp-shell.md), and [ADR 0002](docs/adr/0002-maplibre-native-and-map-assets.md).
