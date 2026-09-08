#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <android|ios> <emulator-serial|simulator-udid> <debug-artifact> <250|1000> <evidence-dir>" >&2
  exit 64
fi

platform=$1
target=$2
artifact=$3
vehicle_count=$4
evidence_dir=$5
app_id=com.denis.georgiatransit
script_dir=$(cd "$(dirname "$0")" && pwd)
fixture_path="$script_dir/fixtures/vehicle-load-fixture-server.mjs"
flow_path="$script_dir/flows/vehicle-realtime-enter-leave.yaml"
prime_flow_path="$script_dir/flows/vehicle-realtime-smoke.yaml"

if [[ $vehicle_count != 250 && $vehicle_count != 1000 ]]; then
  echo "Only the required 250 or 1000 vehicle samples are accepted: $vehicle_count" >&2
  exit 65
fi
if [[ ! -f $fixture_path || ! -f $flow_path || ! -f $prime_flow_path ]]; then
  echo "Missing test-only fixture or flow" >&2
  exit 66
fi
if ! command -v node >/dev/null || ! command -v maestro >/dev/null || ! command -v curl >/dev/null; then
  echo "node, maestro, and curl are required" >&2
  exit 69
fi

mkdir -p "$evidence_dir"
fixture_log="$evidence_dir/fixture-${platform}-${vehicle_count}.log"
fixture_health="$evidence_dir/fixture-${platform}-${vehicle_count}.json"
fixture_port=8080
VEHICLE_FIXTURE_PORT="$fixture_port" VEHICLE_FIXTURE_COUNT="$vehicle_count" node "$fixture_path" >"$fixture_log" 2>&1 &
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
    const expectedCount = Number(process.argv[2]);
    process.exit(
      health.status === "ready" &&
      health.mode === "test-only-vehicle-load-fixture" &&
      health.vehicleCount === expectedCount ? 0 : 1,
    );
  ' "$fixture_health" "$vehicle_count"
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
  echo "Expected fixture PID $fixture_pid and its exact health payload; refusing a foreign or unavailable port $fixture_port. See $fixture_log" >&2
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
    maestro --device "$target" test "$prime_flow_path"
    sleep 10
    verify_owned_fixture || { echo "Fixture ownership/health changed before Android baseline measurement" >&2; exit 70; }
    adb -s "$target" shell dumpsys meminfo "$app_id" >"$evidence_dir/android-${vehicle_count}-baseline-meminfo.txt"
    adb -s "$target" shell dumpsys gfxinfo "$app_id" reset
    maestro --device "$target" test "$flow_path"
    sleep 10
    verify_owned_fixture || { echo "Fixture ownership/health changed before Android three-cycle measurement" >&2; exit 70; }
    adb -s "$target" shell dumpsys meminfo "$app_id" >"$evidence_dir/android-${vehicle_count}-after-3-cycles-meminfo.txt"
    maestro --device "$target" test "$flow_path"
    sleep 10
    verify_owned_fixture || { echo "Fixture ownership/health changed before Android six-cycle measurement" >&2; exit 70; }
    adb -s "$target" shell dumpsys gfxinfo "$app_id" >"$evidence_dir/android-${vehicle_count}-gfxinfo.txt"
    adb -s "$target" shell dumpsys meminfo "$app_id" >"$evidence_dir/android-${vehicle_count}-after-6-cycles-meminfo.txt"
    ;;
  ios)
    if ! command -v vmmap >/dev/null; then
      echo "vmmap is required for iOS Simulator physical-footprint capture" >&2
      exit 69
    fi
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
    maestro --device "$target" test "$prime_flow_path"
    xcrun simctl launch "$target" "$app_id" >/dev/null
    sleep 10
    verify_owned_fixture || { echo "Fixture ownership/health changed before iOS baseline measurement" >&2; exit 70; }
    ios_baseline_pid=$(xcrun simctl spawn "$target" launchctl list | awk '/UIKitApplication:com\.denis\.georgiatransit/ { print $1; exit }')
    if [[ -z $ios_baseline_pid ]]; then
      echo "Unable to locate simulator process for baseline memory capture" >&2
      exit 75
    fi
    ps -p "$ios_baseline_pid" -o pid=,rss=,command= >"$evidence_dir/ios-${vehicle_count}-baseline-host-ps.txt"
    vmmap "$ios_baseline_pid" >"$evidence_dir/ios-${vehicle_count}-baseline-vmmap.txt"
    maestro --device "$target" test "$flow_path"
    xcrun simctl launch "$target" "$app_id" >/dev/null
    sleep 10
    verify_owned_fixture || { echo "Fixture ownership/health changed before iOS three-cycle measurement" >&2; exit 70; }
    ios_after_three_pid=$(xcrun simctl spawn "$target" launchctl list | awk '/UIKitApplication:com\.denis\.georgiatransit/ { print $1; exit }')
    if [[ -z $ios_after_three_pid ]]; then
      echo "Unable to locate simulator process after three cycles" >&2
      exit 75
    fi
    if [[ $ios_after_three_pid != $ios_baseline_pid ]]; then
      echo "Simulator app process changed during three-cycle sample; refusing leak comparison" >&2
      exit 75
    fi
    ps -p "$ios_after_three_pid" -o pid=,rss=,command= >"$evidence_dir/ios-${vehicle_count}-after-3-cycles-host-ps.txt"
    vmmap "$ios_after_three_pid" >"$evidence_dir/ios-${vehicle_count}-after-3-cycles-vmmap.txt"
    maestro --device "$target" test "$flow_path"
    xcrun simctl launch "$target" "$app_id" >/dev/null
    sleep 10
    verify_owned_fixture || { echo "Fixture ownership/health changed before iOS six-cycle measurement" >&2; exit 70; }
    ios_pid=$(xcrun simctl spawn "$target" launchctl list | awk '/UIKitApplication:com\.denis\.georgiatransit/ { print $1; exit }')
    if [[ -z $ios_pid ]]; then
      echo "Unable to locate simulator process for memory capture" >&2
      exit 75
    fi
    if [[ $ios_pid != $ios_baseline_pid ]]; then
      echo "Simulator app process changed during six-cycle sample; refusing leak comparison" >&2
      exit 75
    fi
    ps -p "$ios_pid" -o pid=,rss=,command= >"$evidence_dir/ios-${vehicle_count}-after-6-cycles-host-ps.txt"
    vmmap "$ios_pid" >"$evidence_dir/ios-${vehicle_count}-after-6-cycles-vmmap.txt"
    ;;
  *)
    echo "Unknown platform: $platform" >&2
    exit 76
    ;;
esac
