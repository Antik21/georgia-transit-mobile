# ADR 0001: Shared Compose KMP shell

Status: Accepted

## Context

Georgia Transit needs one Android/iOS codebase and a thin boundary for location, maps, lifecycle, and secure runtime configuration. The neighboring Habit Lab project provides a proven KMP structure, but its habit domain, Room schema, deep links, and temporary iOS gesture workaround are unrelated.

## Decision

Use shared Compose UI, Navigation 3, common lifecycle ViewModels, Koin composition, Orbit MVI, serialization/coroutines/datetime/immutable collections, and Ktor with platform engines. Keep package layers `core/domain/data/presentation/app/di`. Provide three preview-data screens while production BFF/cache/map adapters remain behind explicit interfaces.

## Consequences

Android and iOS render one UI/navigation implementation. Very recent toolchain versions increase compatibility risk and require both-platform CI. Map SDK and tiles remain intentionally unselected until the mandatory free-production/license review.

## Alternatives

Separate native UIs would duplicate state/navigation. Copying Habit Lab wholesale would import irrelevant domain/persistence and brittle workarounds.

## Verification

Compile common tests, Android APK, iOS framework/Xcode target on macOS, then run the same stable-ID smoke flow on Android emulator and iOS simulator.
