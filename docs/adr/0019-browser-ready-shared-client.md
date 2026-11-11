# ADR 0019: Prepare the shared client for a browser target

Status: Accepted

## Context

A future Telegram Mini App needs the Georgia Transit city selection, map, and route selection workflows in a browser. The existing `:shared` module owns these workflows and also contains the Compose screens and host used by Android and iOS. Browser compatibility requires a source-set boundary between the portable workflows and mobile rendering. The [preparation plan](../development/telegram-web-preparation.md) defines the migration and verification steps.

## Decision

Add a Kotlin/JS browser target to the existing `:shared` module. Keep the current package layers and split code by platform responsibility.

| Source set | Responsibility |
| --- | --- |
| `commonMain` | Domain use cases and models, Transit BFF transport contracts and DTO mapping, repositories and session, Orbit ViewModels and their typed actions, states and effects, navigation policy, and portable map and location contracts. |
| `mobileMain` | Compose screens, resources, previews, the `Navigation3AppHost`, its entry-scoped Koin composition, and the Compose map and location integration shared by Android and iOS. |
| `androidMain` and `iosMain` | Platform implementations for native maps, location, storage, runtime configuration, and the existing mobile entry points. |
| `jsMain` | Browser Ktor engine and adapters for `TransitCacheStore`, `SelectedCityStore`, and `RuntimeBootstrapConfiguration`. Compose the existing repositories, session, and ViewModels and expose a small typed JavaScript/TypeScript boundary. |

Keep Navigation 3 as the mobile stack owner. Keep `NavigationTransitionPolicy` and `SessionNavigationEventApplier` in `commonMain` for a future browser navigation host. Typed destination arguments carry identifiers, and the session owns the selected city and committed route selection. Defer the JS navigation stack, integration with the common transition policy and event applier, and the Routes entry until the web UI stage.

Limit the initial JS boundary to session bootstrap, city selection, and opening a map entry for stop arrivals. The runtime creates an isolated Koin application using the shared `appModule` with browser adapters. It projects the existing `CitySelectionViewModel` and `MapViewModel` state into exported types, forwards the supported actions, and reports bootstrap and city confirmation destinations through callbacks. Opening the map entry requires a selected city. The JS runtime and map bridge own their subscriptions and ViewModel stores. Disposal cancels their work and closes the session's HTTP client and Koin application. Keep Koin, Orbit, Kotlin Flow, native SDK objects, and provider contracts internal. The browser uses Transit BFF through the shared repository contracts.

Defer the Telegram bot and SDK integration, web UI, browser map renderer selection, production hosting, and BFF CORS configuration to a subsequent stage. Android and iOS continue using their existing native MapLibre and location integrations.

## Consequences

The browser preparation reuses the same business rules and state transitions for the city and stop-arrival workflows. The typed JS boundary covers this initial slice. Mobile Compose resources and entry points require verification after the source-set move. Browser storage and bootstrap behavior have their own platform checks.

## Alternatives

- Split portable logic and mobile UI into separate Gradle modules. This would add module and dependency configuration to the existing package-layer architecture. The source-set split is sufficient to give the browser a portable dependency graph while preserving the existing shared mobile module and iOS framework entry points.
- Add a TypeScript implementation of the same workflows. This would duplicate BFF mapping, session rules, and MVI behavior across clients.
- Bring the mobile Compose host to the browser in this stage. This would require browser rendering and map integration decisions before verifying reuse of the shared workflows.

## Verification

- The JS tests cover browser storage persistence and fallback, and the stop-to-ETA path and disposal through a fake repository. JS compilation and test execution are blocked. The [preparation plan](../development/telegram-web-preparation.md) records the attempted commands and blockers.
- Once unblocked, compile the browser target, type-check a TypeScript consumer against the generated declarations, and run a browser smoke test with BFF fixtures for bootstrap, city selection and restoration, opening the map entry, selecting a stop, and receiving ETA data. Check that disposal releases subscriptions and background work.
- Run the shared host tests and build Sandbox and Prod on Android and iOS. Verify resource generation and the existing entry points. Run the shell, route selection, map, and location smoke paths on the Android emulator and iOS simulator.
