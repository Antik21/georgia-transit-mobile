# Compose screen rule

New product screens live in `shared/src/commonMain` and render on Android and iOS. Platform differences enter through narrow capabilities/callbacks, never platform checks inside composables.

Each independent screen gets its own package and files: `<Feature>Screen.kt`, `<Feature>State.kt`, `<Feature>ViewModel.kt`, an optional `UiMapper`, and `sections/` when the layout has at least three meaningful blocks.

The public `Screen` collects Orbit state/effects and forwards typed `NavigationEffect` to the common Nav3 host. A private stateless `Content` accepts a complete immutable `ViewState` and one `onAction` callback. Add a private preview with handwritten state. Screen code must not resolve Koin or mutate the back stack.

State files use immutable UI models and sealed `Action`, `SideEffect`, `NavigationEffect`, and `ViewEffect` contracts. Persistent display data belongs in state; one-shot navigation/platform commands are effects. Every event enters through `dispatchAction(Action)` with an exhaustive `when`.

ViewModels are entry-scoped common lifecycle ViewModels and Orbit `ContainerHost`s. Start observation in `container(onCreate = ...)`, guard duplicate loading/saving, keep business rules in domain interactors, and map domain data in one mapper when transformation is non-trivial.

User-facing strings use Compose resources. Interactive/asserted nodes use stable, locale-independent automation IDs. Do not encode runtime values as IDs. Verify target compilation and the affected flow on Android and iOS; when the current host cannot execute one platform, state the missing host/toolchain instead of claiming parity.

