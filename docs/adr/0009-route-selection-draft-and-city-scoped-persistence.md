# ADR 0009: Draft route selection and city-scoped persistence

Status: Accepted (2026-09-09)

## Context

Route selection controls map overlays and one bounded realtime polling loop per selected route.
Writing every checkbox change directly to the shared session makes Back indistinguishable from
confirmation, can make a partially edited selection visible on Map, and lets a process restart
lose the useful committed selection. The existing native key-value adapter already persists a
small selected-city snapshot; adding another client persistence stack for at most ten opaque route
IDs would broaden the mobile architecture without need.

The existing schema v1 snapshot contains only a city. Restored Navigation 3 destinations must not
carry selection entities or a route screen ViewState, and system Back must have the same discard
contract as an explicit cancellation action.

## Decision

Routes owns an entry-local immutable draft in its ViewModel. The draft is initialized once from
the committed `TransitSession` selection when the entry sees a city, and it is reset only for a
city or routes-capability change. Search, locale relabelling, and ordinary session-flow echoes do
not replace that draft. Confirm validates an available authoritative catalog for the currently
active city, verifies every opaque ID is in that catalog, and atomically replaces the committed
set before it emits a typed `Confirmed` effect. Cancel and system Back emit a separate typed
dismissal path and only pop the existing Navigation 3 Routes entry; neither applies a draft.

Use the common `RouteSelectionPolicy.MaximumSelectedRoutes` value of 10 in presentation,
session, bootstrap, and persistence validation. At the cap, selected rows remain enabled so a user
can deselect them, while unselected rows are disabled with accessible limit context. The Map sees
only the committed replacement set, so it restarts polling once for the confirmed selection rather
than once per checkbox edit.

Extend the existing single `CachedCitySnapshot` to schema v2 with `selectedRouteIds`. Native
`SharedPreferences` and `NSUserDefaults` adapters write that one serialized snapshot. Schema v1 is
strictly city-only and restores an empty route set even if a crafted v1 payload contains
`selectedRouteIds`. Schema v2 selections with blank, oversized, or malformed route fields fail
closed to an empty route set while retaining a valid city; an invalid city/snapshot envelope is
cleared as before.
After bootstrap validates the city, it refreshes that city's route catalog within the remaining
bootstrap budget and restores/persists only persisted IDs found in fresh or validated cache data.
A stale-offline catalog, catalog failure, timeout, or offline city fallback restores the valid city
with an empty route set. A city change
writes the new city with an empty selection; a same-city refresh keeps the committed selection;
clearing the city removes the entire durable snapshot. A persistence failure never rolls back a
valid in-memory selection, while cancellation is rethrown.

`TransitSession.selectRoutes` requires the active `CityId` and rejects stale-city or oversized
sets. It intentionally does not parse opaque route IDs; catalog membership is the Routes
ViewModel's authoritative validation. An authoritative catalog refresh may reconcile absent
committed IDs and persist its surviving set.

## Consequences

- The user can inspect and revise a multi-route draft without causing map/polling churn; an empty
  confirmed set intentionally clears all route overlays.
- A cold start restores only a bounded selection that survives the accepted current-city route
  catalog. Bootstrap hydrates that catalog before Map is created, so Map receives resolved IDs
  without a synthetic visible empty-to-full route toggle.
- Existing city-only stores migrate on their next valid bootstrap/write. No SQL/database,
  DataStore, or parallel navigation/state/persistence stack is introduced.
- QA must cover empty, partial, cap, cancel/system-Back, repeated editing, city switch, catalog
  reconciliation, and v1/v2/corrupt persistence scenarios on both shared and UI boundaries.

## Alternatives

- **Commit every toggle immediately:** rejected because cancel cannot reliably discard edits and
  Map repeatedly restarts realtime work.
- **Put draft IDs in the Navigation 3 destination or a separate saved-state stack:** rejected
  because routes carry typed arguments only and the session remains the committed selection owner.
- **Persist one key per route or add a database/preferences framework:** rejected because the
  existing bounded atomic snapshot and native adapters are sufficient for ten opaque IDs.
- **Infer city ownership by parsing route IDs:** rejected because BFF IDs are opaque; membership
  is verified against the current authoritative city catalog.

## Verification

```text
./gradlew --no-daemon --no-build-cache :shared:compileKotlinIosSimulatorArm64
./gradlew --no-daemon --no-build-cache :androidApp:assembleDebug
./gradlew --no-daemon --no-build-cache spotlessCheck
```

QA additionally updates shared session/bootstrap/routes/navigation tests and stable-selector UI
flows for `routes.cancel`, `routes.selection-count`, and `routes.selection-warning`.
