# ADR 0033 · Direct Amplitude delivery on mobile

Status: Accepted

## Decision

Android and iOS send product analytics directly to the existing Geo Transit Amplitude project in the US region. The native hosts own the SDK instances and receive SDK-independent events from the common `ProductAnalytics` class. Firebase remains the source for crash and selected-city diagnostics.

The Android Kotlin SDK is pinned to 1.36.0 and the iOS Swift SDK to 1.20.1. Both are MIT-licensed and support the existing platform targets. A free Amplitude plan supports Analytics. The Android catalog and Swift package lock pin the dependencies. Both app bundles include the [Amplitude license notice](../../shared/src/mobileMain/composeResources/files/licenses/amplitude.txt).
[Android source and license](https://github.com/amplitude/Amplitude-Kotlin/tree/v1.36.0) · [iOS source and license](https://github.com/amplitude/Amplitude-Swift/tree/v1.20.1) · [Amplitude pricing](https://amplitude.com/pricing)

Use the existing random installation ID as the SDK device ID. The shared product-event rules and stable deduplication IDs still apply. Native SDKs group events with their 30-minute session timeout and buffer delivery locally. Enable application lifecycle autocapture for installation, update, foreground, and background events. Keep automatic interaction, screen, and network capture, remote autocapture configuration, and SDK diagnostics disabled. Disable advertising and vendor identifiers and IP-based location enrichment. The SDKs add standard app and device context such as app version, platform, and OS version. The production configuration contains the project's public ingestion key and uses the US endpoint. Sandbox analytics require an explicit test ingestion key.
[Android tracking options](https://amplitude.com/docs/sdks/analytics/android/android-kotlin-sdk/instrument-and-configure) · [iOS tracking options](https://amplitude.com/docs/sdks/analytics/ios/ios-swift-sdk/instrument-and-configure) · [Amplitude key types](https://amplitude.com/docs/admin/account-management/manage-your-api-keys-and-secret-keys)

`AnalyticsCatalog` is the source for event names, property keys, and cross-client values. The shared Kotlin client and the BFF compile that same source. Web and Telegram consume the Kotlin/JS client and keep posting to `/v1/analytics`. The BFF also keeps accepting landing and older mobile payloads. Amplitude is a telemetry exception to the mobile BFF boundary. Transit and map requests still go through the BFF.

## Verification

Build both mobile environments on Android and iOS, run the shared analytics and BFF contract tests, and build the web client. Check application lifecycle and explicit product events in the Amplitude live event stream and verify that their device ID matches the one stored by the app.
