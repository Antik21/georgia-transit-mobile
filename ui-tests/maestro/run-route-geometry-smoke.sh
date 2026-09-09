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
fixture_path="$script_dir/fixtures/route-geometry-fixture-server.mjs"
flow_path="$script_dir/flows/route-geometry-smoke.yaml"
fixture_port=8080
fixture_log=$(mktemp "${TMPDIR:-/tmp}/georgia-transit-route-geometry.XXXXXX.log")
fixture_health=$(mktemp "${TMPDIR:-/tmp}/georgia-transit-route-geometry.XXXXXX.json")

if [[ ! -f $fixture_path || ! -f $flow_path ]]; then
  echo "Missing route-geometry test-only fixture or flow" >&2
  exit 66
fi
if ! command -v node >/dev/null || ! command -v curl >/dev/null || ! command -v maestro >/dev/null; then
  echo "node, curl, and maestro are required" >&2
  exit 69
fi

ROUTE_GEOMETRY_FIXTURE_PORT="$fixture_port" ROUTE_GEOMETRY_PARTIAL_FAILURE=true node "$fixture_path" >"$fixture_log" 2>&1 &
fixture_pid=$!

fixture_is_our_process() {
  kill -0 "$fixture_pid" 2>/dev/null || return 1
  local fixture_command
  fixture_command=$(ps -p "$fixture_pid" -o command= 2>/dev/null || true)
  [[ $fixture_command == *"$fixture_path"* ]]
}

fixture_health_matches() {
  local expected_partial_requests=$1
  node -e '
    const fs = require("node:fs");
    const health = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const expectedPartialRequests = Number(process.argv[2]);
    process.exit(
      health.status === "ready" &&
      health.mode === "test-only-route-geometry-fixture" &&
      health.routes === 10 && health.directions === 20 &&
      health.precision5 === true && health.precision6 === true && health.partialFailure === true &&
      health.partialFailureMode === "retryable-upstream-bad-response" &&
      health.partialAutomaticFailureAttempts === 3 &&
      health.partialDirectionRequests === expectedPartialRequests ? 0 : 1,
    );
  ' "$fixture_health" "$expected_partial_requests"
}

verify_owned_fixture() {
  local expected_partial_requests=$1
  fixture_is_our_process &&
    curl --fail --silent "http://127.0.0.1:$fixture_port/healthz" >"$fixture_health" &&
    fixture_health_matches "$expected_partial_requests"
}

cleanup() {
  if fixture_is_our_process; then
    kill "$fixture_pid" 2>/dev/null || true
  fi
  wait "$fixture_pid" 2>/dev/null || true
  rm -f "$fixture_log" "$fixture_health"
}
trap cleanup EXIT

fixture_ready=false
for _ in {1..20}; do
  if verify_owned_fixture 0; then
    fixture_ready=true
    break
  fi
  if ! fixture_is_our_process; then
    break
  fi
  sleep 0.25
done
if [[ $fixture_ready != true ]]; then
  echo "Expected fixture PID $fixture_pid and exact loopback health payload; refusing foreign or unavailable port $fixture_port. See $fixture_log" >&2
  exit 70
fi

case "$platform" in
  android)
    if [[ $target != emulator-* ]]; then
      echo "Refusing non-emulator Android target: $target" >&2
      exit 71
    fi
    if ! command -v adb >/dev/null || [[ ! -f $artifact ]] || [[ $(adb -s "$target" get-state 2>/dev/null) != device ]]; then
      echo "Android debug APK or emulator is not ready" >&2
      exit 72
    fi
    adb -s "$target" install -r "$artifact"
    adb -s "$target" shell pm clear "$app_id"
    ;;
  ios)
    if ! command -v xcrun >/dev/null || [[ ! -d $artifact ]]; then
      echo "iOS app bundle or xcrun not found" >&2
      exit 73
    fi
    if ! xcrun simctl list devices booted | grep -F "($target) (Booted)" >/dev/null; then
      echo "Refusing target that is not an explicitly booted iOS simulator: $target" >&2
      exit 74
    fi
    xcrun simctl terminate "$target" "$app_id" 2>/dev/null || true
    xcrun simctl uninstall "$target" "$app_id" 2>/dev/null || true
    xcrun simctl install "$target" "$artifact"
    ;;
  *)
    echo "Unknown platform: $platform" >&2
    exit 76
    ;;
esac

maestro --device "$target" test "$flow_path"
verify_owned_fixture 4 || { echo "Fixture did not prove exactly three automatic failures followed by one user retry" >&2; exit 70; }
