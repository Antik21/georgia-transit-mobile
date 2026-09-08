#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <simulator-udid> <ios-app-bundle>" >&2
  exit 64
fi

simulator_udid=$1
app_path=$2
app_id=com.denis.georgiatransit
script_dir=$(cd "$(dirname "$0")" && pwd)
flow_path="$script_dir/flows/vehicle-realtime-smoke.yaml"

if [[ ! -d $app_path ]]; then
  echo "iOS app bundle not found: $app_path" >&2
  exit 66
fi
if ! xcrun simctl list devices booted | grep -F "($simulator_udid) (Booted)" >/dev/null; then
  echo "Refusing target that is not an explicitly booted iOS simulator: $simulator_udid" >&2
  exit 65
fi

xcrun simctl terminate "$simulator_udid" "$app_id" 2>/dev/null || true
xcrun simctl uninstall "$simulator_udid" "$app_id" 2>/dev/null || true
xcrun simctl install "$simulator_udid" "$app_path"
maestro --device "$simulator_udid" test "$flow_path"
