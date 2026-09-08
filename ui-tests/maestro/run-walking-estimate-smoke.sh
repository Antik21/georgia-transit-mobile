#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <android|ios> <emulator-serial|booted-simulator-udid> <debug-apk|ios-app-bundle>" >&2
  exit 64
fi

platform=$1
target=$2
artifact=$3
app_id=com.denis.georgiatransit
script_dir=$(cd "$(dirname "$0")" && pwd)
fixture_path="$script_dir/fixtures/stop-arrivals-fixture-server.mjs"
health_validator_path="$script_dir/fixtures/validate-walking-health.mjs"
flow_path="$script_dir/flows/walking-estimate-smoke.yaml"
fixture_port=8080
fixture_log=$(mktemp "${TMPDIR:-/tmp}/georgia-transit-walking.XXXXXX.log")
fixture_health=$(mktemp "${TMPDIR:-/tmp}/georgia-transit-walking.XXXXXX.json")

if [[ ! -f $fixture_path || ! -f $health_validator_path || ! -f $flow_path ]]; then
  echo "Missing walking-estimate test fixture or flow" >&2
  exit 66
fi
if ! command -v node >/dev/null || ! command -v curl >/dev/null || ! command -v maestro >/dev/null; then
  echo "node, curl, and maestro are required" >&2
  exit 69
fi

STOP_ARRIVALS_FIXTURE_PORT="$fixture_port" node "$fixture_path" >"$fixture_log" 2>&1 &
fixture_pid=$!

fixture_is_ours() {
  kill -0 "$fixture_pid" 2>/dev/null || return 1
  [[ $(ps -p "$fixture_pid" -o command= 2>/dev/null || true) == *"$fixture_path"* ]]
}

read_health() {
  fixture_is_ours && curl --fail --silent "http://127.0.0.1:$fixture_port/healthz" >"$fixture_health"
}

health_contract_matches() {
  node "$health_validator_path" "$fixture_health" "$1"
}

cleanup() {
  if fixture_is_ours; then kill "$fixture_pid" 2>/dev/null || true; fi
  wait "$fixture_pid" 2>/dev/null || true
  rm -f "$fixture_log" "$fixture_health"
}
trap cleanup EXIT

ready=false
for _ in {1..20}; do
  if read_health && health_contract_matches 0; then ready=true; break; fi
  fixture_is_ours || break
  sleep 0.25
done
if [[ $ready != true ]]; then
  echo "Expected owned loopback fixture on port $fixture_port; see $fixture_log" >&2
  exit 70
fi

case "$platform" in
  android)
    if [[ $target != emulator-* ]]; then echo "Refusing non-emulator Android target: $target" >&2; exit 71; fi
    if [[ ! -f $artifact ]] || [[ $(adb -s "$target" get-state 2>/dev/null) != device ]]; then
      echo "Android debug APK or emulator is not ready" >&2; exit 72
    fi
    adb -s "$target" install -r "$artifact"
    adb -s "$target" shell pm clear "$app_id"
    adb -s "$target" shell pm grant "$app_id" android.permission.ACCESS_COARSE_LOCATION
    adb -s "$target" shell pm grant "$app_id" android.permission.ACCESS_FINE_LOCATION
    adb -s "$target" emu geo fix 44.8271 41.7151
    maestro --device "$target" test "$flow_path"
    ;;
  ios)
    if [[ ! -d $artifact ]]; then echo "iOS app bundle not found: $artifact" >&2; exit 73; fi
    if ! xcrun simctl list devices booted | grep -F "($target) (Booted)" >/dev/null; then
      echo "Refusing target that is not an explicitly booted iOS simulator: $target" >&2; exit 74
    fi
    xcrun simctl terminate "$target" "$app_id" 2>/dev/null || true
    xcrun simctl uninstall "$target" "$app_id" 2>/dev/null || true
    xcrun simctl install "$target" "$artifact"
    xcrun simctl privacy "$target" reset all "$app_id"
    xcrun simctl privacy "$target" grant location "$app_id"
    xcrun simctl location "$target" set 41.7151,44.8271
    maestro --device "$target" test "$flow_path"
    ;;
  *) echo "Unknown platform: $platform" >&2; exit 76 ;;
esac

if ! read_health; then echo "Fixture ownership or health changed during smoke" >&2; exit 70; fi
# Exact keys plus scalar/static value checks ensure this owned fixture exposes only its bounded
# counter and static health fields: no nested request payload or coordinate-bearing field can pass.
health_contract_matches 1 || { echo "Walking fixture health contract is not strict and coordinate-free" >&2; exit 75; }
# Defense in depth for the fixture process's captured stdout/stderr; this does not inspect memory.
if grep -Fq -e "41.7151" -e "44.8271" "$fixture_log"; then
  echo "Injected coordinates leaked into fixture stdout/stderr" >&2
  exit 75
fi
