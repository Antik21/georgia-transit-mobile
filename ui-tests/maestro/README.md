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
