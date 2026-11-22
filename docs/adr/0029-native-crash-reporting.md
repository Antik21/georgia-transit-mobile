# ADR 0029: Native crash reporting with Firebase Crashlytics

Status: Accepted

## Context

The Android and iOS hosts need crash diagnostics for Sandbox and Prod. The app's public
transport traffic and the journey coordinate boundary remain owned by the Transit BFF.

## Decision

Use the official SDKs in the native application targets. Android uses Crashlytics 21.1.0
from Firebase BoM 35.2.0, Crashlytics Gradle plugin 3.0.8, and Google services plugin 4.6.0.
iOS uses the FirebaseCrashlytics SwiftPM product from Firebase Apple SDK 13.2.0.
Crashlytics is available at no cost for mobile apps.
[Firebase Android release notes](https://firebase.google.com/support/release-notes/android) ·
[Firebase Apple release notes](https://firebase.google.com/support/release-notes/ios) ·
[Firebase pricing](https://firebase.google.com/pricing)

Allow the native SDKs to send crash diagnostics directly to Firebase. Use a distinct Firebase
app registration for each platform and environment in the `georgia-transit-mobile` project.
Include the public Firebase SDK configuration files in the app sources so CI and release
builds use the same registrations. Keep Firebase APIs out of `shared`.
Exclude Analytics, custom crash logs, custom keys, and user IDs from this integration.
Never attach trip endpoints, device location, request URLs or bodies, or provider data
to crash reports.
[Firebase client configuration](https://firebase.google.com/docs/projects/learn-more#config-files-objects)

## Consequences

The SDKs are Apache 2.0 and support the existing Android and iOS deployment targets.
The iOS SDK requires Xcode 26.2 or newer.
[Android SDK license](https://github.com/firebase/firebase-android-sdk/blob/master/LICENSE) ·
[Apple SDK license](https://github.com/firebase/firebase-ios-sdk/blob/13.2.0/LICENSE)

Crashlytics collects crash stacks, device and app metadata, and installation identifiers.
Google begins deleting crash data and associated identifiers after 90 days. Include this
diagnostic collection in store privacy disclosures for builds with Firebase configured.
[Firebase privacy](https://firebase.google.com/support/privacy) ·
[Google Play data disclosure](https://firebase.google.com/docs/android/play-data-disclosure) ·
[App Store data disclosure](https://firebase.google.com/docs/ios/app-store-data-collection)

## Alternatives

Forwarding crash events through the BFF would require collecting and decoding native crash
data, running a symbolication pipeline, and operating a separate diagnostics store.

## Verification

Build Sandbox and Prod on each platform. With Firebase configuration supplied, trigger a
test crash in Sandbox on each platform and verify the reports and symbolication in Firebase.
