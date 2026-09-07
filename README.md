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

With valid settings, bootstrap loads the repository's current city/capability
snapshot and validates the platform-stored selected-city snapshot. Android
stores the full city record (including its capabilities) in `SharedPreferences`;
iOS stores the same record in `NSUserDefaults`. A first launch,
removed/disabled city, malformed cache, or incompatible schema opens City
Selection. A matching current city opens Map and refreshes its cached snapshot.
If snapshot loading fails or times out, only a structurally valid, enabled
cached city can open Map offline; otherwise the splash presents an accessible
retry state instead of remaining indefinitely loading. Read/write/clear storage
failures degrade only durable restoration: they cannot block the current City
Selection or Map transition.

Verify startup explicitly on both platforms:

1. Cold start after clearing app data (Android) or deleting/reinstalling the
   app (iOS): City Selection is shown.
2. Select Tbilisi or Batumi, continue to Map, terminate the app, then cold
   start again: Map restores the selected city.
3. Put the app in the background and return while on City Selection, Map, and
   Routes: the existing common navigation state remains valid for the restored
   city.

The checked-in preview repository and platform runtime-config adapters are
local and immediately available, so they do not exercise timeout, unavailable,
or invalid-configuration recovery at runtime. Those paths are nevertheless
bounded and terminal for a future BFF repository or host adapter that reports a
failure.

## Build and quality checks

Prerequisites:

- JDK 17.
- Android SDK platform 37.0 and build tools 37.0.0. Set `ANDROID_HOME` to the SDK root, or put the local SDK path in an uncommitted `local.properties` file such as `sdk.dir=/absolute/path/to/android-sdk`.
- For Apple targets, macOS with Xcode and an installed iOS Simulator runtime on Apple Silicon. The shared `iosSimulatorArm64` target needs an `arm64` host; `iosX64` is available only on Intel macOS.

`local.properties`, `ANDROID_HOME` values, signing material, and provider configuration are local-only. Do not commit them. This shell does not require secrets or signing to run its quality gates.

Run the same quality checks locally with:

```text
./gradlew --no-daemon --no-build-cache spotlessCheck
./gradlew --no-daemon --no-build-cache :shared:testAndroidHostTest :shared:checkKotlinGradlePluginConfigurationErrors :androidApp:lintDebug :androidApp:assembleDebug
SIMULATOR_UDID="<an available iOS Simulator UDID from xcrun simctl list devices available>"
./gradlew --no-daemon --no-build-cache :shared:iosSimulatorArm64Test --device "$SIMULATOR_UDID" :shared:checkXcodeProjectConfiguration
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
git diff --check
```

The two iOS commands require macOS, Xcode, and a simulator runtime. The iOS test task's supported `--device` option binds it to the selected simulator UDID; CI creates, boots, and deletes that temporary device for runtime tests. The unsigned Xcode compile/link gate uses Xcode's supported generic iOS Simulator destination. On Windows, Android work is available but cannot provide iOS simulator evidence.

Formatting is enforced with the Apache-2.0, no-cost Spotless Gradle plugin (`8.10.2`), which is a build-only dependency. To avoid unrelated rewrapping/import churn in the existing Kotlin baseline, its current rule set checks trailing whitespace in Kotlin and Gradle Kotlin files, and trailing whitespace plus one final newline in `README.md`, `.gitignore`, and GitHub Actions YAML. It does not apply a Kotlin style formatter or change runtime dependencies.

## Continuous integration

GitHub Actions runs three stable checks for pushes and pull requests targeting `main`:

- `Quality` checks formatting.
- `Android` runs shared Android-host unit tests, Kotlin Gradle configuration checks, Android lint, and the debug build.
- `iOS` validates an Apple Silicon runner, runs arm64 simulator tests against an explicitly created temporary simulator and the Xcode configuration check, then builds the unsigned app for Xcode's generic iOS Simulator destination.

The workflow gives `gradle/actions/setup-gradle` sole ownership of the Gradle User Home cache. Pull requests and non-`main` branches restore caches read-only; `main` may write them after success. It does not cache repository `.gradle`, build outputs, `DerivedData`, `local.properties`, secret/configuration files, or generated app artifacts. Gradle invocations disable the build cache so a cached artifact cannot hide a generation failure.

Repository administrators should configure branch protection to require the exact `Quality`, `Android`, and `iOS` checks before merging into `main`. This repository documentation does not claim that protection is already enabled.

The cross-platform stable-ID scenario is in ui-tests/maestro/flows/shell-smoke.yaml.

## Documentation

Start with [AGENTS.md](AGENTS.md), the [toolchain](.agents/docs/01-stack-toolchain.md), [architecture boundaries](.agents/docs/02-architecture-boundaries.md), [ADR 0001](docs/adr/0001-kmp-shell.md), and [ADR 0002](docs/adr/0002-maplibre-native-and-map-assets.md).

For the Debug-only Android/iOS local HTTP inspector, including smoke, clear,
privacy, license, and Release-absence checks, see
[network inspector development guide](docs/development/network-inspector.md).
