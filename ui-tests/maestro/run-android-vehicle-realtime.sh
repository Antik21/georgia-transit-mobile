#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <emulator-serial> <debug-apk>" >&2
  exit 64
fi

android_serial=$1
apk_path=$2
app_id=com.denis.georgiatransit
script_dir=$(cd "$(dirname "$0")" && pwd)
flow_path="$script_dir/flows/vehicle-realtime-smoke.yaml"

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
maestro --device "$android_serial" test "$flow_path"
