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
flow_path="$script_dir/flows/stop-arrivals-smoke.yaml"
fixture_port=8080
fixture_log=$(mktemp "${TMPDIR:-/tmp}/georgia-transit-stop-arrivals.XXXXXX.log")
fixture_health=$(mktemp "${TMPDIR:-/tmp}/georgia-transit-stop-arrivals.XXXXXX.json")

if [[ ! -f $fixture_path || ! -f $flow_path ]]; then
  echo "Missing stop-arrivals test-only fixture or flow" >&2
  exit 66
fi
if ! command -v node >/dev/null || ! command -v curl >/dev/null || ! command -v maestro >/dev/null; then
  echo "node, curl, and maestro are required" >&2
  exit 69
fi

STOP_ARRIVALS_FIXTURE_PORT="$fixture_port" node "$fixture_path" >"$fixture_log" 2>&1 &
fixture_pid=$!

fixture_is_our_process() {
  kill -0 "$fixture_pid" 2>/dev/null || return 1
  local fixture_command
  fixture_command=$(ps -p "$fixture_pid" -o command= 2>/dev/null || true)
  [[ $fixture_command == *"$fixture_path"* ]]
}

fixture_health_matches() {
  node -e '
    const fs = require("node:fs");
    const health = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    process.exit(health.status === "ready" && health.mode === "test-only-stop-arrivals-fixture" ? 0 : 1);
  ' "$fixture_health"
}

verify_owned_fixture() {
  fixture_is_our_process &&
    curl --fail --silent "http://127.0.0.1:$fixture_port/healthz" >"$fixture_health" &&
    fixture_health_matches
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
  if verify_owned_fixture; then
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
    if [[ ! -f $artifact ]] || [[ $(adb -s "$target" get-state 2>/dev/null) != device ]]; then
      echo "Android debug APK or emulator is not ready" >&2
      exit 72
    fi
    adb -s "$target" install -r "$artifact"
    adb -s "$target" shell pm clear "$app_id"
    maestro --device "$target" test "$flow_path"
    ;;
  ios)
    if [[ ! -d $artifact ]]; then
      echo "iOS app bundle not found: $artifact" >&2
      exit 73
    fi
    if ! xcrun simctl list devices booted | grep -F "($target) (Booted)" >/dev/null; then
      echo "Refusing target that is not an explicitly booted iOS simulator: $target" >&2
      exit 74
    fi
    xcrun simctl terminate "$target" "$app_id" 2>/dev/null || true
    xcrun simctl uninstall "$target" "$app_id" 2>/dev/null || true
    xcrun simctl install "$target" "$artifact"
    maestro --device "$target" test "$flow_path"
    ;;
  *)
    echo "Unknown platform: $platform" >&2
    exit 76
    ;;
esac

verify_owned_fixture || { echo "Fixture ownership or health changed during the stop-arrivals smoke flow" >&2; exit 70; }
