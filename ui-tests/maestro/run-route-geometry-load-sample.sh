#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <android|ios> <emulator-serial|booted-simulator-udid> <debug-apk|ios-app-bundle> <evidence-dir>" >&2
  exit 64
fi

platform=$1
target=$2
artifact=$3
evidence_dir=$4
app_id=com.denis.georgiatransit
script_dir=$(cd "$(dirname "$0")" && pwd)
fixture_path="$script_dir/fixtures/route-geometry-fixture-server.mjs"
prime_flow="$script_dir/flows/route-geometry-load-prime.yaml"
cycle_flow="$script_dir/flows/route-geometry-enter-leave.yaml"
fixture_port=8080

if [[ ! -f $fixture_path || ! -f $prime_flow || ! -f $cycle_flow ]]; then
  echo "Missing DEN-69 test-only fixture or performance flow" >&2
  exit 66
fi
if ! command -v node >/dev/null || ! command -v curl >/dev/null || ! command -v maestro >/dev/null; then
  echo "node, curl, and maestro are required" >&2
  exit 69
fi
mkdir -p "$evidence_dir"
fixture_log="$evidence_dir/route-geometry-fixture-${platform}.log"
fixture_health="$evidence_dir/route-geometry-fixture-${platform}.json"
ROUTE_GEOMETRY_FIXTURE_PORT="$fixture_port" ROUTE_GEOMETRY_PARTIAL_FAILURE=false node "$fixture_path" >"$fixture_log" 2>&1 &
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
    process.exit(
      health.status === "ready" && health.mode === "test-only-route-geometry-fixture" &&
      health.routes === 10 && health.directions === 20 && health.partialFailure === false &&
      health.partialDirectionRequests === 0 && health.partialFailureMode === "disabled" &&
      health.partialAutomaticFailureAttempts === 0 ? 0 : 1,
    );
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
    if ! command -v adb >/dev/null || [[ ! -f $artifact ]] || [[ $(adb -s "$target" get-state 2>/dev/null) != device ]]; then
      echo "Android debug APK or emulator is not ready" >&2
      exit 72
    fi
    adb -s "$target" install -r "$artifact"
    adb -s "$target" shell pm clear "$app_id"
    maestro --device "$target" test "$prime_flow" | tee "$evidence_dir/android-route-geometry-prime-maestro.txt"
    verify_owned_fixture || { echo "Fixture ownership changed before Android baseline" >&2; exit 70; }
    adb -s "$target" shell dumpsys meminfo "$app_id" >"$evidence_dir/android-route-geometry-baseline-meminfo.txt"
    adb -s "$target" shell dumpsys gfxinfo "$app_id" reset
    maestro --device "$target" test "$cycle_flow" | tee "$evidence_dir/android-route-geometry-3-cycles-maestro.txt"
    verify_owned_fixture || { echo "Fixture ownership changed before Android three-cycle capture" >&2; exit 70; }
    adb -s "$target" shell dumpsys meminfo "$app_id" >"$evidence_dir/android-route-geometry-after-3-cycles-meminfo.txt"
    maestro --device "$target" test "$cycle_flow" | tee "$evidence_dir/android-route-geometry-6-cycles-maestro.txt"
    verify_owned_fixture || { echo "Fixture ownership changed before Android six-cycle capture" >&2; exit 70; }
    adb -s "$target" shell dumpsys gfxinfo "$app_id" >"$evidence_dir/android-route-geometry-gfxinfo.txt"
    adb -s "$target" shell dumpsys meminfo "$app_id" >"$evidence_dir/android-route-geometry-after-6-cycles-meminfo.txt"
    ;;
  ios)
    if ! command -v xcrun >/dev/null || ! command -v vmmap >/dev/null || [[ ! -d $artifact ]]; then
      echo "xcrun, vmmap, and an iOS app bundle are required" >&2
      exit 73
    fi
    if ! xcrun simctl list devices booted | grep -F "($target) (Booted)" >/dev/null; then
      echo "Refusing target that is not an explicitly booted iOS simulator: $target" >&2
      exit 74
    fi
    xcrun simctl terminate "$target" "$app_id" 2>/dev/null || true
    xcrun simctl uninstall "$target" "$app_id" 2>/dev/null || true
    xcrun simctl install "$target" "$artifact"
    maestro --device "$target" test "$prime_flow" | tee "$evidence_dir/ios-route-geometry-prime-maestro.txt"
    xcrun simctl launch "$target" "$app_id" >/dev/null
    verify_owned_fixture || { echo "Fixture ownership changed before iOS baseline" >&2; exit 70; }
    ios_baseline_pid=$(xcrun simctl spawn "$target" launchctl list | awk '/UIKitApplication:com\.denis\.georgiatransit/ { print $1; exit }')
    if [[ -z $ios_baseline_pid ]]; then
      echo "Unable to locate simulator process for baseline memory capture" >&2
      exit 75
    fi
    ps -p "$ios_baseline_pid" -o pid=,rss=,command= >"$evidence_dir/ios-route-geometry-baseline-host-ps.txt"
    vmmap "$ios_baseline_pid" >"$evidence_dir/ios-route-geometry-baseline-vmmap.txt"
    maestro --device "$target" test "$cycle_flow" | tee "$evidence_dir/ios-route-geometry-3-cycles-maestro.txt"
    verify_owned_fixture || { echo "Fixture ownership changed before iOS three-cycle capture" >&2; exit 70; }
    ios_after_three_pid=$(xcrun simctl spawn "$target" launchctl list | awk '/UIKitApplication:com\.denis\.georgiatransit/ { print $1; exit }')
    if [[ $ios_after_three_pid != $ios_baseline_pid ]]; then
      echo "Simulator app process changed during three-cycle sample; refusing leak comparison" >&2
      exit 75
    fi
    ps -p "$ios_after_three_pid" -o pid=,rss=,command= >"$evidence_dir/ios-route-geometry-after-3-cycles-host-ps.txt"
    vmmap "$ios_after_three_pid" >"$evidence_dir/ios-route-geometry-after-3-cycles-vmmap.txt"
    maestro --device "$target" test "$cycle_flow" | tee "$evidence_dir/ios-route-geometry-6-cycles-maestro.txt"
    verify_owned_fixture || { echo "Fixture ownership changed before iOS six-cycle capture" >&2; exit 70; }
    ios_after_six_pid=$(xcrun simctl spawn "$target" launchctl list | awk '/UIKitApplication:com\.denis\.georgiatransit/ { print $1; exit }')
    if [[ $ios_after_six_pid != $ios_baseline_pid ]]; then
      echo "Simulator app process changed during six-cycle sample; refusing leak comparison" >&2
      exit 75
    fi
    ps -p "$ios_after_six_pid" -o pid=,rss=,command= >"$evidence_dir/ios-route-geometry-after-6-cycles-host-ps.txt"
    vmmap "$ios_after_six_pid" >"$evidence_dir/ios-route-geometry-after-6-cycles-vmmap.txt"
    ;;
  *)
    echo "Unknown platform: $platform" >&2
    exit 76
    ;;
esac
