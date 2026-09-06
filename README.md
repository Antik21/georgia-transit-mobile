# Georgia Transit Mobile

Kotlin Multiplatform shell for a Georgia public-transit app. Shared Compose UI currently demonstrates three connected screens: city selection, map preview, and multi-route selection. Android and iOS hosts render the same common UI.

## Stack

- Kotlin 2.4.10, Compose Multiplatform 1.12.0 and Material 3
- Navigation 3, common lifecycle ViewModels, Koin and Orbit MVI
- Coroutines, serialization, datetime and immutable collections
- Ktor client with OkHttp on Android and Darwin on iOS

The map itself is intentionally a renderer placeholder. A production MapLibre-compatible SDK and tile provider must pass the license, cost, attribution, and production-usage review before adoption.

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

Start with [AGENTS.md](AGENTS.md), the [toolchain](.agents/docs/01-stack-toolchain.md), [architecture boundaries](.agents/docs/02-architecture-boundaries.md), and [ADR 0001](docs/adr/0001-kmp-shell.md), the accepted inventory and extension rules for this shell.
