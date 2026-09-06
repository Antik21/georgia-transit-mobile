# Stack and toolchain

The wrapper pins Gradle 9.3.1 with checksum. Project versions are centralized in `gradle/libs.versions.toml`: AGP 9.1.1, Kotlin 2.4.10, Compose Multiplatform 1.12.0, Material 3 1.9.0, Java 17, compile SDK 37, min SDK 33, target SDK 36, Koin 4.2.2, Navigation 3 1.1.1, Orbit 11.0.0, and Ktor 3.3.3.

Targets are Android, iOS arm64 device, and iOS arm64 simulator. `iosX64` exists only on Intel macOS. The Xcode target supports iOS 16+ and builds static framework `Shared`.

Commands:

```text
./gradlew :shared:testAndroidHostTest
./gradlew :androidApp:assembleDebug
./gradlew :shared:iosSimulatorArm64Test
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

iOS compilation, simulator execution, and Xcode validation require macOS. A skipped native task on Windows is not iOS evidence.

