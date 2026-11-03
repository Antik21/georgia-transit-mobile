# Compose screen rule

New product screens live in `shared/src/commonMain` and render on Android and iOS. Platform differences enter through narrow capabilities/callbacks, never platform checks inside composables.

Each independent screen gets its own package and files: `<Feature>Screen.kt`, `<Feature>State.kt`, `<Feature>ViewModel.kt`, an optional `UiMapper`, and `sections/` when the layout has at least three meaningful blocks.

Decompose screens by responsibility, state ownership, and isolated testability while following the 500-line Kotlin-file limit in [architecture boundaries](../docs/02-architecture-boundaries.md). Refactor when one composable mixes state collection, navigation/effects, data transformation, and multiple independent layout regions; when a child receives state it does not use; or when a meaningful region cannot be previewed or tested on its own. The screen entry point stays thin and stateful, while the render/content layer and semantic sections stay stateless for screen/business state.

Extract sections along product-visible boundaries such as a map canvas, controls, results, status overlays, or a sheet. A section receives only the immutable values it renders and explicit, narrowly scoped callbacks; do not pass a ViewModel, router, `MutableState`, the entire screen state, generic props/callback bags, or a catch-all event solely to shorten its signature. Keep simple element state at the lowest common UI owner, move complex UI-only behavior to a plain state holder, and keep business state in the screen ViewModel.

Every UI-emitting reusable section accepts one `modifier: Modifier = Modifier` and applies it once to its root. Keep effects next to the owner of the state or lifecycle they synchronize, with explicit keys; composition itself must not start I/O, navigation, or imperative work. Preserve stable automation IDs when extracting sections, and prefer behavior or rendered-semantics tests over tests that depend on a composable living in one specific source file.

The public `Screen` collects Orbit state/effects and forwards typed `NavigationEffect` to the common Nav3 host. A private stateless `Content` accepts a complete immutable `ViewState` and one `onAction` callback. Add a private preview with handwritten state. Screen code must not resolve Koin or mutate the back stack.

State files use immutable UI models and sealed `Action`, `SideEffect`, `NavigationEffect`, and `ViewEffect` contracts. Persistent display data belongs in state; one-shot navigation/platform commands are effects. Every event enters through `dispatchAction(Action)` with an exhaustive `when`.

ViewModels are entry-scoped common lifecycle ViewModels and Orbit `ContainerHost`s. Start observation in `container(onCreate = ...)`, guard duplicate loading/saving, keep business rules in domain interactors, and use focused pure mappers when transformation is non-trivial. For a multi-workflow screen, use the approach established for Map in [ADR 0018](../../docs/adr/0018-map-presentation-mvi.md):

1. Keep one entry-scoped ViewModel and one Orbit container as the only publishers of `ViewState` and `SideEffect`. Route typed user `Action` through an exhaustive dispatcher; navigation and platform commands leave as effects.
2. Give each independent workflow a focused owner for its jobs, request generation, locks, and temporary cache. Build it for the ViewModel entry from constructor-injected dependencies. For Map, nearby stops, supplemental stops, arrivals, vehicles, session selection, camera/stop selection, and rendering have separate owners. Do not create a generic coordinator that owns all workflows.
3. Let workflows report feature-specific typed events or outcomes. Run network calls and polling outside Orbit `intent`. Before applying an outcome inside the scheduled intent, verify its generation and relevant city, locale, selection, visibility, and capability; cancellation alone is insufficient for a late response. Invalidate affected owners and clear stale projections before publishing a city or selection transition.
4. Use pure reducers for immutable state transitions and focused projectors/mappers/policies for display transformation. Keep feature-local mutable snapshots inside their owner; do not duplicate `ViewState` there. Cross-workflow coordination and final state publication stay in the ViewModel. Handlers must not receive `ContainerHost`, resolve Koin, own navigation, or directly mutate another handler.

Keep a simple screen simple: introduce a workflow owner when it has an independent lifecycle or state, not solely to reduce line count. Extract by responsibility while enforcing the 500-line source-file limit.

User-facing strings use Compose resources. Interactive/asserted nodes use stable, locale-independent automation IDs. Do not encode runtime values as IDs. Verify target compilation and the affected flow on Android and iOS; when the current host cannot execute one platform, state the missing host/toolchain instead of claiming parity.
