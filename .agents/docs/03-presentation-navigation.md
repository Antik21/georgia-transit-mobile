# Presentation and navigation

`Navigation3AppHost` is the sole owner and mutator of one typed Navigation 3 stack for both platforms. Current destinations are `CitySelection`, `Map`, and `Routes`. Selection state belongs to the injected `TransitSession`, not route objects.

Each entry resolves an entry-scoped ViewModel at `NavigationEntryKoinComposition`; feature code does not use Koin. Screens forward typed navigation effects. City confirmation resets the root to Map, route selection is pushed above Map, confirmation commits its validated draft then pops it, while cancel/system Back discard the draft and pop it; changing city resets to CitySelection.

Future deep links and process restoration must validate prerequisites. No restored stack may open Map or Routes without a valid city. Incompatible persisted route changes require an ADR/version bump.
