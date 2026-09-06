# ADR 0001: Shared Compose KMP shell

Status: Accepted

## Context

Georgia Transit needs one Android/iOS product implementation with narrow native
boundaries for OS concerns. The first deliverable is an architectural shell: it
must prove a common UI, typed navigation, construction, and target setup without
pretending that preview transit data, a Canvas drawing, or a client factory is a
production provider integration.

The neighboring Habit Lab project is not a source of domain decisions. Its
habit-specific persistence, deep links, gesture workaround, and provider
assumptions do not apply here.

This ADR is the accepted inventory and acceptance record for the current shell.
It records what exists, the boundaries that it establishes, and deliberately
unmade choices so that later work can extend it without introducing a parallel
architecture.

## Accepted scaffold inventory

### Modules, hosts, targets, and source sets

| Item | Current accepted state |
| --- | --- |
| Gradle modules | `:shared` is the KMP product module; `:androidApp` is the Android application host and depends on `:shared`. |
| iOS host | `iosApp/iosApp.xcodeproj` contains the Swift/Xcode `iosApp` host. It imports the `Shared` framework, starts Koin once in `GeorgiaTransitApp`, and embeds the common `MainViewController`. It is not a third Gradle module. |
| Kotlin targets | `android`, `iosArm64`, and `iosSimulatorArm64`. `iosX64` is created only when the build host is an Intel macOS machine (`HostManager.hostIsMac` and `Architecture.X64`). |
| Source sets | `commonMain`, `commonTest`, `androidMain`, and `iosMain`. The iOS targets use the normal KMP hierarchy below `iosMain`; Android-only and iOS-only implementations remain in their respective target source sets. |
| Framework | Every Kotlin/Native target builds a static framework named `Shared`. Xcode links that framework from the shared build output. |
| Platform/toolchain floor | Android compiles with SDK 37, has min SDK 33 and target SDK 36. Android Kotlin and Java compile for Java 17. The Xcode target deploys to iOS 16.0. iOS validation requires macOS, Xcode, and the chosen simulator runtime. |

### Build and dependency baseline

The Gradle wrapper uses Gradle 9.3.1 and pins its distribution with SHA-256
`b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`.
`gradle/libs.versions.toml` is the version declaration point for this project.
The current baseline is:

| Concern | Current version or implementation |
| --- | --- |
| Android Gradle Plugin | 9.1.1 |
| Kotlin and Java target | Kotlin 2.4.10; Java/JVM 17 |
| Compose | Compose Multiplatform 1.12.0; Material 3 1.9.0; Compose resources, runtime, foundation, and UI preview are common dependencies |
| Android host | `androidx.activity:activity-compose` 1.13.0 |
| Lifecycle and navigation | JetBrains lifecycle 2.11.0-beta01; Navigation3 UI 1.1.1 |
| DI and UI state | Koin 4.2.2; Orbit 11.0.0 |
| Common utilities | coroutines 1.10.2, kotlinx.serialization 1.9.0, kotlinx.datetime 0.8.0, and kotlinx.collections.immutable 0.4.0 |
| HTTP | Ktor 3.3.3: common core/content negotiation/kotlinx JSON, OkHttp on Android, and Darwin on iOS |
| Test-only utilities already declared | Turbine 1.2.1, Orbit test, coroutines test, and Kotlin test |

No version in this ADR substitutes for the catalog: the catalog remains the
authoritative implementation value. No source, plugin, dependency, SDK, or
runtime configuration is changed by this ADR.

### What the shell currently implements

- `commonMain` owns the Compose application, localized Compose resources, the
  shared theme and automation bridge, and the connected City Selection, Map,
  and Routes preview screens. Android and iOS render that common UI.
- `Navigation3AppHost` is the only common navigation stack. Its current serializable
  destinations are `CitySelection`, `Map`, and `Routes`; the in-memory
  `TransitSession` holds the selected city and routes rather than placing them
  in a route.
- Koin is used for application construction. Its module constructs the preview
  repository/session and the ViewModels. The app-level navigation entry adapter
  obtains entry-scoped ViewModels; screen and ViewModel feature code does not
  resolve Koin. Every current ViewModel receives `TransitRepository` and
  `TransitSession` through its constructor.
- Orbit owns the current immutable `ViewState` plus action/effect flow for each
  screen. This is shell UI state, not a second state-management stack.
- `createTransitHttpClient()` provides a Ktor expect/actual client factory with
  JSON content negotiation. Android supplies the OkHttp engine and iOS supplies
  the Darwin engine. There is no production BFF DTO, endpoint, base URL,
  authorization scheme, request implementation, or contract bound to that
  factory yet.
- `PreviewTransitRepository` and `RuntimeTransitSession` provide local preview
  cities/routes and an in-memory session only. Their Tbilisi/Batumi/Kutaisi data,
  namespaced-looking preview route IDs, and capability flags are not provider
  contracts. Kutaisi is disabled in this preview and remains feature-gated until
  an authorized BFF capability contract enables it.
- The map is a Compose `Canvas` preview placeholder. No map SDK, tiles, style,
  geocoder, provider, or map runtime adapter has been selected. There is no
  persistence, offline cache, database, or cache stack in the scaffold.

## Decision

Retain this single shared Compose KMP shell and extend it through its existing
boundaries.

### Layer and platform boundaries

`shared` is organized by package layers, not premature feature Gradle modules.
Some layers are intentionally not populated by the preview yet; the accepted
dependency directions are nevertheless fixed.

| Layer | Responsibility | May depend on |
| --- | --- | --- |
| `core` | cross-cutting, platform-neutral primitives | no other shared layer |
| `domain` | transit entities/value objects, repository contracts, use cases, typed errors | `core` |
| `data` | normalized BFF DTOs/client/cache/repository implementations and explicit mappings | `core`, `domain` |
| `presentation` | immutable UI state, actions/effects, mappers, ViewModels, screens, and automation IDs | `core`, `domain` |
| `app` | the sole common Navigation3 stack and application assembly at the UI boundary | `presentation` |
| `di` | object construction and bindings only | all layers, only for composition |

`shared/src/commonMain` owns product behavior and product screens. `androidApp`,
`androidMain`, the Swift/Xcode host, and `iosMain` are adapter boundaries for OS
objects. Permissions, location, map rendering, lifecycle, and safe runtime
configuration must enter common product code only through narrow platform
contracts/callbacks; Android, SwiftUI/UIKit, Foundation, CoreLocation, map SDK,
and other native types must not leak into `domain` or `presentation`.

Mobile clients talk only to the normalized Transit BFF. Provider-specific DTOs,
URLs, identifiers, and secrets stay behind the BFF boundary; they never enter
the mobile app. Routes may carry typed identifiers or arguments only, never
domain entities or `ViewState`. ViewModels are constructor-injected and never
resolve Koin themselves. The existing Navigation3 stack is the sole navigation
implementation.

### Runtime configuration and secrets

No provider secret belongs in a mobile client or version control. If the app
later needs non-secret runtime configuration, a platform adapter may supply it
through a narrow common contract after validation; it must not become a provider
credential channel. Provider credentials are a Transit BFF secret-manager
responsibility.

The current scaffold intentionally has no base-URL configuration, secret
placeholder, secret service, or provider-credential implementation. This ADR
does not add one.

### Extension rules

- Keep package layers in `:shared` until a documented reason justifies a new
  Gradle module. A new navigation, DI, networking, serialization, UI-state, or
  persistence stack is not permitted alongside the selected one.
- Add or change dependency versions only in `gradle/libs.versions.toml`. Before
  adding/upgrading a production dependency, establish that an existing approved
  API does not fit; review free-for-production license/cost, maintenance,
  transitive graph, KMP targets/minimum OS, and compatibility; then compile both
  Android and iOS targets. Architecture-changing choices require a new ADR.
- Map selection belongs to DEN-75. Its decision must separately review the SDK
  *and* tiles/styles/geocoding for cost, quotas, attribution, privacy, and
  production rights. A public community tile endpoint is not production
  infrastructure without an explicit approval.

### Future BFF/client boundary

Future work may introduce a normalized `/v1` BFF client, opaque namespaced IDs,
and explicit repository mappings. The BFF/client contract must define capability
delivery, cache/ETag policy, bounded retry behavior, stale/offline/loading/empty
and error handling at its boundary; none is implemented by the preview shell.
The UI must derive availability from city capabilities. Kutaisi remains gated,
and any approximate ETA must be explicitly marked approximate and withheld when
the underlying data is unreliable.

## Consequences

Android and iOS share one Compose UI, navigation implementation, state model,
and domain-facing contracts while native host code stays thin. Constructor
injection keeps ViewModels testable and prevents service location from spreading
through feature code. The preview makes the navigation flow demonstrable without
claiming an external provider integration.

The deliberately unselected BFF contract, persistence/cache policy, production
map infrastructure, permissions/location adapters, and safe runtime-config
adapter remain delivery work and must not be filled with mock data in a
production path. Recent toolchain versions and the lifecycle beta increase
compatibility risk, so Android and iOS must both be compiled whenever the
relevant implementation, toolchain, or dependency changes.

## Alternatives considered

- **Separate Android and iOS UIs:** rejected because it duplicates product
  navigation and state behavior, inviting platform drift.
- **Copying Habit Lab wholesale:** rejected because it imports irrelevant
  domain, persistence, deep-link, and workaround decisions.
- **Selecting a map SDK/tile provider in the shell:** rejected because no
  production license/cost/attribution/privacy/rights review exists; DEN-75 owns
  that decision.
- **Direct provider clients or client-side secrets:** rejected because the
  mobile boundary is the Transit BFF, which owns provider integrations and
  credentials.
- **A cache/database or second stack before a BFF contract:** rejected because
  cache semantics must follow the normalized contract and explicit stale/offline
  policy rather than preview data.

## Verification

The repository-prescribed baseline is:

```text
./gradlew :shared:testAndroidHostTest
./gradlew :androidApp:assembleDebug
./gradlew :shared:iosSimulatorArm64Test
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

For UI/navigation changes, also exercise
`ui-tests/maestro/flows/shell-smoke.yaml` on an explicitly selected Android
emulator and iOS simulator, recording target/runtime and result. The repository
does not prescribe raw-coordinate or localized-text selectors; the flow uses
automation IDs. Android success does not constitute iOS evidence. A missing
macOS/Xcode/simulator runtime must be reported as a blocker, not represented as
a passing iOS validation.

DEN-44 changes no product behavior, dependencies, or tests. Baseline
verification also corrects only the Gradle wrapper execute bit and the iOS host
call to the actual Kotlin/Native export. This ADR records the prescribed checks;
the manager/PR records the exact checks that ran.
