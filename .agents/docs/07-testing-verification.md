# Testing and verification

Portable model, repository, mapper, ViewModel, and navigation tests live in `shared/src/commonTest`. Platform adapter tests stay in target source sets. Test behavior and invariants through domain/capability fakes.

Run the narrow test during iteration, then `:shared:testAndroidHostTest` and `:androidApp:assembleDebug`. UI/navigation work also exercises ui-tests/maestro/flows/shell-smoke.yaml on an explicit Android emulator and iOS simulator. Capture the target/runtime and result. Never use localized text or raw coordinates as the selector contract.

Before release, require Android and iOS host builds, key smoke paths, accessibility/localization, and applicable offline/stale/error states. A Windows Android pass cannot substitute for iOS simulator evidence.

