#!/bin/sh

set -eu

readonly image="${1:-transit-bff:smoke}"
readonly volume="transit-bff-smoke-${GITHUB_RUN_ID:-local}-$$"
container=""

cleanup() {
    if [ -n "$container" ]; then
        docker rm --force "$container" >/dev/null 2>&1 || true
    fi
    docker volume rm "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

docker volume create "$volume" >/dev/null

start_container() {
    container="$(
        docker run --detach \
            --publish 127.0.0.1::10000 \
            --volume "${volume}:/app/storage" \
            --env PORT=10000 \
            --env BFF_MODE=production \
            --env BFF_HOST=0.0.0.0 \
            --env BFF_FIXTURES_ENABLED=false \
            --env BFF_MAP_ENABLED=true \
            --env 'BFF_MAP_TILE_TEMPLATE=https://tile.openstreetmap.org/{z}/{x}/{y}.png' \
            --env 'BFF_MAP_ATTRIBUTION=<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>' \
            --env BFF_MAP_PUBLIC_BASE_URL=https://smoke.invalid \
            --env BFF_METRICS_ENABLED=false \
            --env BFF_PROBES_ENABLED=false \
            --env TRANSITOUS_ENABLED=false \
            --env TTC_ENABLED=false \
            --env BATUMI_THETA_ENABLED=true \
            --env BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT=I_APPROVE_THETA_PRODUCTION_USE \
            --env BFF_SCHEMA_INTERLOCK_ENABLED=true \
            --env BFF_CAPABILITY_CONTROL_PATH=/app/storage/control/capabilities.json \
            --env BFF_CAPABILITY_CONTROL_STATE_DIR=/app/storage/state \
            "$image"
    )"
}

wait_until_ready() {
    address="$(docker port "$container" 10000/tcp)"
    attempt=0
    while [ "$attempt" -lt 45 ]; do
        if curl --fail --silent --show-error "http://${address}/healthz" >/dev/null 2>&1 && \
            response="$(curl --fail --silent --show-error "http://${address}/v1/cities" 2>/dev/null)"; then
            printf '%s' "$response" | grep --quiet '"id":"batumi"'
            printf '%s' "$response" | grep --quiet '"readiness":"PRODUCTION_READY"'
            printf '%s' "$response" | grep --quiet '"source":"REVIEWED_ADAPTER"'
            [ "$(printf '%s' "$response" | grep -o '"id":' | wc -l | tr -d ' ')" -eq 1 ]
            style="$(curl --fail --silent --show-error "http://${address}/v1/map/style.json")"
            printf '%s' "$style" | grep --quiet 'https://smoke.invalid/v1/map/tiles/{z}/{x}/{y}.png'
            printf '%s' "$style" | grep --quiet 'OpenStreetMap contributors'
            return
        fi
        attempt=$((attempt + 1))
        sleep 1
    done
    docker logs "$container" >&2
    return 1
}

assert_private_state() {
    [ "$(docker exec "$container" stat -c '%U:%G:%a' /app/storage)" = "transit:transit:700" ]
    [ "$(docker exec "$container" stat -c '%U:%G:%a' /app/storage/control)" = "transit:transit:700" ]
    [ "$(docker exec "$container" stat -c '%U:%G:%a' /app/storage/state)" = "transit:transit:700" ]
    case "$(docker exec "$container" stat -c '%U:%G:%a' /app/storage/control/capabilities.json)" in
        transit:transit:400|transit:transit:600) ;;
        *) return 1 ;;
    esac
}

snapshot_private_state() {
    docker exec "$container" sh -eu -c '
        test -f /app/storage/control/capabilities.json
        test -f /app/storage/state/history-index.json
        set -- /app/storage/state/documents/*.json
        test -f "$1"
        for path in \
            /app/storage/control/capabilities.json \
            /app/storage/state/history-index.json \
            "$@"
        do
            stat -c "%n|%i|%Y|%s|%U:%G:%a" "$path"
            sha256sum "$path"
        done
    ' | sort
}

start_container
wait_until_ready
assert_private_state
state_before_restart="$(snapshot_private_state)"
docker rm --force "$container" >/dev/null
container=""

# The second start must reuse the same control/history/interlock storage without rewriting it.
start_container
wait_until_ready
assert_private_state
state_after_restart="$(snapshot_private_state)"
[ "$state_before_restart" = "$state_after_restart" ]
