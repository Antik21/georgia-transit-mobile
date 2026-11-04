# ADR 0018: MVI ownership for Map presentation

Status: Accepted

## Context

`MapViewModel.kt` is 2073 lines and currently owns city/selection reconciliation, nearby and selected-route stop requests, arrivals polling, vehicle polling and animation, camera/location commands, and render projection. Some existing classes already separate calculations and geometry requests, but their existence does not establish the desired design. Shared presentation must retain its single Orbit state/effect owner and the common navigation stack.

## Decision

Use an MVI flow within the existing Orbit container: typed `Action` enters `MapViewModel`; independent asynchronous handlers emit feature-specific typed results; small pure reducers compute immutable next state; `SideEffect` carries one-shot commands. `MapViewModel` remains the entry-scoped action dispatcher, Orbit `ContainerHost`, and sole `ViewState`/`SideEffect` publisher. Give each handler exclusive ownership of its jobs, generations, locks, and feature-local cache. Run long requests and polling outside Orbit `intent`; apply results in short reductions after checking their context key. Pure projectors/mappers/policies handle display transformation and viewport rules. Keep business rules in domain interactors and BFF transport, mapping, and cache behind domain contracts in data. Existing coordinators and reducers are candidates for reuse, replacement, or further splitting based on these contracts, not precedents to copy. The migration sequence and observable invariants are in [the plan](../development/map-viewmodel-decomposition.md).

## Consequences

The ViewModel becomes a small MVI composition boundary while request cancellation and stale-response protection remain testable per workflow. Handlers are built for the Map entry from constructor-injected dependencies. Cross-workflow inputs pass through the ViewModel, which must reconcile them before publishing a new render state. This adds typed result and reducer contracts and requires careful preservation of revision semantics during migration. Kotlin files are kept at or below 500 lines by responsibility-based decomposition, including existing oversized helpers when touched.

## Alternatives

- Multiple ViewModels or Orbit containers for one Map entry: rejected because they would divide state/effect ownership and complicate atomic city/selection transitions.
- One generic event bus or all-purpose `MapCoordinator`: rejected because it would recreate the same accumulation behind another name.
- Moving polling and UI state into domain/data: rejected because those lifecycles and render projections belong to presentation; domain/data keep their existing boundaries.
- Splitting the current file into extensions of `MapViewModel`: rejected because mutable ownership and coupling would remain unchanged.

## Verification

No runtime behavior changes in this ADR. Implement in slices and use the test and platform gates in [the plan](../development/map-viewmodel-decomposition.md).
