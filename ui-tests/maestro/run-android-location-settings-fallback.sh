#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <emulator-serial> <debug-apk>" >&2
  exit 64
fi

android_serial=$1
apk_path=$2
app_id=com.denis.georgiatransit
coarse_permission=android.permission.ACCESS_COARSE_LOCATION
fine_permission=android.permission.ACCESS_FINE_LOCATION
script_dir=$(cd "$(dirname "$0")" && pwd)
flow_path="$script_dir/flows/location-settings-fallback-smoke.yaml"
permission_preferences='<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="requested" value="true" /></map>'

if [[ $android_serial != emulator-* ]]; then
  echo "Refusing non-emulator Android target: $android_serial" >&2
  exit 65
fi
if [[ ! -f $apk_path ]]; then
  echo "APK not found: $apk_path" >&2
  exit 66
fi
if [[ $(adb -s "$android_serial" get-state 2>/dev/null) != device ]]; then
  echo "Android emulator is not ready: $android_serial" >&2
  exit 69
fi

adb -s "$android_serial" install -r "$apk_path"
adb -s "$android_serial" shell pm clear "$app_id"
adb -s "$android_serial" shell pm revoke "$app_id" "$coarse_permission" 2>/dev/null || true
adb -s "$android_serial" shell pm revoke "$app_id" "$fine_permission" 2>/dev/null || true
adb -s "$android_serial" shell pm set-permission-flags "$app_id" "$coarse_permission" user-set user-fixed
adb -s "$android_serial" shell pm set-permission-flags "$app_id" "$fine_permission" user-set user-fixed
preferences_path="/data/user/0/$app_id/shared_prefs/location_permission.xml"
adb -s "$android_serial" shell run-as "$app_id" mkdir -p "/data/user/0/$app_id/shared_prefs"
printf '%s\n' "$permission_preferences" |
  adb -s "$android_serial" shell run-as "$app_id" tee "$preferences_path" >/dev/null

maestro --device "$android_serial" test "$flow_path"

permission_dump=$(adb -s "$android_serial" shell dumpsys package "$app_id")
if grep -E 'ACCESS_(COARSE|FINE)_LOCATION: granted=true' <<<"$permission_dump" >/dev/null; then
  echo "Foreground location was unexpectedly granted during the fallback flow" >&2
  exit 1
fi
