# ADR 0030: Selected city and handled failure telemetry

Status: Accepted

## Context

Geo Transit needs to measure which city people select in the app and diagnose failures
that the app handles. [ADR 0029](0029-native-crash-reporting.md) established the native
Firebase boundary for automatic crash reports. This ADR extends it to selected-city
analytics and handled diagnostics.

## Decision

The transit session provides the canonical city ID when selection changes or is restored.
Clearing the session clears the `selected_city` Analytics user property. The value follows
the selected city and is independent of location permission. The linked Google Analytics
resource exposes it as the user-scoped **Selected city** dimension.

Shared startup, cache, selected-city persistence, and BFF response handling report a fixed
set of non-fatal categories. The native adapters record them with Crashlytics. Each category
is recorded once per app process to leave room for distinct failures in the SDK's bounded
buffer. Expected offline outcomes, request cancellation, and documented BFF errors follow
their normal application paths. Diagnostic reports use fixed descriptions and codes without
exception messages, request bodies, URLs, request identifiers, or user coordinates.

Android uses Firebase Analytics 24.0.0 from the existing Firebase BoM and disables
advertising ID collection. iOS uses the existing Firebase Apple SDK with the
`FirebaseAnalyticsCore` product. Both native apps deny ad storage, ad user data, and ad
personalization by default. The native SDKs stay in the application hosts. Web retains
the shared transit session with a no-op telemetry adapter.
[Firebase Android release notes](https://firebase.google.com/support/release-notes/android) ·
[iOS Analytics data collection](https://firebase.google.com/docs/analytics/ios/configure-data-collection) ·
[Crashlytics Android reports](https://firebase.google.com/docs/crashlytics/android/customize-crash-reports) ·
[Crashlytics iOS reports](https://firebase.google.com/docs/crashlytics/ios/customize-crash-reports)

## Consequences

Analytics collects measurement events and installation-level identifiers. The selected
city is product usage data. Include these data in the store privacy disclosures for mobile
releases. Crashlytics also receives handled failure categories from the same Firebase app
registrations.
[Google Play data disclosure](https://firebase.google.com/docs/android/play-data-disclosure) ·
[App Store data disclosure](https://firebase.google.com/docs/ios/app-store-data-collection)

## Alternatives

An event for each city selection would track transitions rather than the current city as a
user attribute. Sending exception objects directly could expose request and storage values
in exception messages.

## Verification

In Sandbox, select a city and check `selected_city` in Analytics DebugView. Reopen the app
to verify restored city selection. Use a malformed response from a local BFF to check that
the app stays open and Crashlytics receives a non-fatal diagnostic.
