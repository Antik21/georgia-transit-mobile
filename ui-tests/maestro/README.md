# Maestro runtime checks

The granted-location flow must be launched through a platform harness. Each harness resets only
the Georgia Transit app, installs the supplied build, grants foreground location, injects a known
Tbilisi simulator fix, and then runs a flow whose `launchApp` step does not clear app state.

Android accepts emulator serials only:

```shell
ui-tests/maestro/run-android-location-granted.sh \
  emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Android also has a deterministic settings-required fallback check. Its harness resets the app,
records that a permission request occurred, applies permanent-denial permission flags, verifies
that no foreground permission was granted during the run, and checks that city/map/routes remain
usable without exposing `map.user-location`. The Android-only flow also declares `all: deny` to
prevent Maestro's launch-time permission normalization from overriding the external denial:

```shell
ui-tests/maestro/run-android-location-settings-fallback.sh \
  emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

iOS accepts a booted Simulator UDID only:

```shell
ui-tests/maestro/run-ios-location-granted.sh \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app
```

## Disabled Tbilisi attribution

This flow requires a Debug artifact whose BFF catalog contains Tbilisi with `stops=false` and
the Transitous/OpenStreetMap attribution links. It taps the disabled Tbilisi row, verifies that
Continue has no enabled stable-ID node, activates each attribution link, and waits for the app's
city-selection screen to disappear. It uses only automation IDs and never selects Tbilisi for
navigation.

Android accepts an explicit emulator serial only:

```shell
ui-tests/maestro/run-android-tbilisi-attribution-disabled.sh \
  emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

iOS accepts an explicitly booted Simulator UDID only:

```shell
ui-tests/maestro/run-ios-tbilisi-attribution-disabled.sh \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app
```

The injected latitude/longitude are setup data, not selector coordinates. Maestro assertions use
only locale-independent automation IDs. Do not substitute a physical Android device or Apple
device for either argument.

## Offline route-cache reopen

`run-offline-route-cache-smoke.sh` starts and verifies an owned loopback-only handwritten fixture,
installs and resets the app, and seeds a route catalog through the city, map, and routes screens.
It then terminates the app without clearing native storage, stops only its verified fixture PID,
proves that port 8080 is no longer serving, and relaunches with `clearState: false`. The reopen flow
requires `routes.option` and rejects `routes.error`, using stable automation IDs only. Exact 48-hour
aging remains an injected-clock repository test; this device flow proves the separate cold-process
offline-reopen behavior while the persisted catalog is fresh.

Use only an explicit Android emulator serial or explicitly booted iOS Simulator UDID:

```shell
ui-tests/maestro/run-offline-route-cache-smoke.sh android emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk

ui-tests/maestro/run-offline-route-cache-smoke.sh ios \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app
```

## Realtime vehicles

This flow is intentionally limited to the opt-in local development BFF fixture. Start the BFF on
the host before launching either Debug app; production configuration has no fallback endpoint:

```shell
BFF_MODE=development BFF_FIXTURES_ENABLED=true ./gradlew :transitBff:run
```

Then run the vehicle flow on an explicitly selected emulator or booted Simulator:

```shell
ui-tests/maestro/run-android-vehicle-realtime.sh \
  emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk

ui-tests/maestro/run-ios-vehicle-realtime.sh \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app
```

The flow asserts only fixed automation IDs, including `map.vehicles.live-nonempty`; it cannot
pass on a loading, timeout, stale, or fresh-empty vehicle state. It has no localized text or
coordinate selector. The fixture contains only handwritten development data and never carries a
provider secret.

### Repeatable 250 / 1000 vehicle samples

`run-vehicle-load-sample.sh` starts a separate loopback-only handwritten test fake (never the
production BFF), captures a post-first-render baseline, then records evidence after three and six
map enter/leave cycles in one verified app process. It accepts only an Android `emulator-*`
target or an explicitly booted iOS Simulator, and only the required 250 or 1000 fixture sizes.
The output directory is deliberately caller-owned so generated evidence is not tracked as source.

```shell
ui-tests/maestro/run-vehicle-load-sample.sh android emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk 250 /tmp/den-61-android-250

ui-tests/maestro/run-vehicle-load-sample.sh ios \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app 1000 /tmp/den-61-ios-1000
```

Android captures `dumpsys gfxinfo` (frame percentiles and jank) plus `meminfo`. iOS captures
the Simulator app host process with `vmmap` (physical footprint) and `ps` (RSS). This Xcode 26.6
runtime exposes no Core Animation/FPS `xctrace` template; its Time Profiler command also failed to
honor a ten-second limit in the verification run, so do not infer an iOS FPS or jank number from
the simulator evidence. Native source checks are only adapter-wiring smoke tests; 250/1000 runtime
flows and their captured artifacts are the behavior and memory evidence.

## Stop arrivals

`run-stop-arrivals-smoke.sh` starts a handwritten, test-only BFF fake on loopback port 8080,
checks its PID and exact health response before and after the flow, then stops only that owned
process. The fake is not a production BFF adapter, has no provider credentials, and never enables release behavior.
It returns a known demo stop, route label, official source, and arriving row
after a short delay so the flow proves the accessible sheet's loading, content, close, and reopen
states using only stable IDs.

Use only an explicit emulator or booted iOS Simulator:

```shell
ui-tests/maestro/run-stop-arrivals-smoke.sh android emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk

ui-tests/maestro/run-stop-arrivals-smoke.sh ios \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app
```

## Walking estimate

The walking flow reuses the owned stop-arrivals fixture, grants foreground location, injects a
known simulator fix, opens the stop sheet, and requires both an arrivals row and
`map.walking-estimate.ready`. This smoke covers only the owned test fixture boundary: the harness
requires at least one no-store POST, validates an exact flat `/healthz` schema containing only
static health fields and a bounded request counter, and scans captured fixture stdout/stderr for
the injected coordinates as defense in depth. It does not inspect process memory and does not
claim to prove production BFF retention behavior. Production non-retention is covered by BFF
unit/contract tests plus structural inspection of its no-cache/no-LKG request path. Use only an
explicit emulator/simulator:

```shell
ui-tests/maestro/run-walking-estimate-smoke.sh android emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk

ui-tests/maestro/run-walking-estimate-smoke.sh ios \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app
```

## Route geometry (DEN-69)

`run-route-geometry-smoke.sh` owns a handwritten loopback-only BFF fake on port 8080. It serves
exactly ten catalogue routes, each with two distinct encoded shapes (both precision 5 and 6), and
sets `routeGeometry=true`. The partial direction returns a documented HTTP 502
`UPSTREAM_BAD_RESPONSE` for exactly three requests: the initial GET and TransitBffClient's two
automatic retries. The resulting common Retryable legend exposes its retry control; the fourth
request, from that user action, succeeds. The uncapped health counter must be exactly `0 → 3 → 4`,
so the harness fails on duplicate or unexpected automatic calls. The fixture has no provider
contract or secret and is never bundled with either app.

The Maestro flow uses fixed automation IDs. Where Compose LazyColumn exposes only the visible
repeated `routes.option` nodes, bounded relative swipes inside the list bring fixed nonlocalized
`G1`–`G10` short names (owned only by this loopback fixture) into the tap viewport; the stable
option ancestor is then tapped. Each tap waits for recomposition; the final
`routes.selection-warning` is the strict 10/10 assertion and stays portable across Android's
checked and iOS's selected accessibility semantics. The relative gestures are not device-pixel
coordinates or product data selectors. It then returns to the map, checks the legend/chips,
focuses a chip, reaches the controlled partial/retry state, removes a route, and returns through
Routes. It deliberately does not claim that semantics nodes prove native line geometry. Ordered
decoded points and the grouped Android/iOS source boundary are verified by common and host tests;
this flow is interaction evidence only.

Use only an explicit Android emulator or explicitly booted iOS Simulator:

```shell
ui-tests/maestro/run-route-geometry-smoke.sh android emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk

ui-tests/maestro/run-route-geometry-smoke.sh ios \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app
```

`run-route-geometry-load-sample.sh` uses the same fake with every shape successful, primes the
maximum 10-route / 20-direction selection, then records three and six leave/return cycles. Its
final argument is caller-owned and intentionally untracked. The priming flow asserts the legend
and one visible chip: the strict 10/10 assertion occurs before Confirm, while the horizontal chip
row intentionally virtualizes off-screen chips; common coordinator tests and fixture requests
cover all twenty directions.

```shell
ui-tests/maestro/run-route-geometry-load-sample.sh android emulator-5554 \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk /tmp/den-69-android

ui-tests/maestro/run-route-geometry-load-sample.sh ios \
  19C4B36C-E2E9-43C3-BB33-B762FFDA5A08 \
  /absolute/path/to/Build/Products/Debug-iphonesimulator/iosApp.app /tmp/den-69-ios
```

Android captures `dumpsys gfxinfo` and `dumpsys meminfo`; iOS Simulator captures `vmmap` and the
host process RSS. Simulator output cannot prove iOS FPS or jank, so physical-device performance
remains a separate required measurement when release thresholds need that evidence.
