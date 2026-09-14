#!/bin/sh

set -eu

readonly runtime_user="transit"
readonly runtime_group="transit"
readonly storage_root="${BFF_STORAGE_ROOT:-/app/storage}"
readonly control_directory="${storage_root}/control"
readonly state_directory="${storage_root}/state"
readonly control_file="${control_directory}/capabilities.json"
readonly bundled_control_file="/app/config/batumi-capabilities.json"

fail() {
    printf '%s\n' "transit-bff startup: $1" >&2
    exit 1
}

path_exists() {
    [ -e "$1" ] || [ -L "$1" ]
}

require_private_directory() {
    directory="$1"
    if path_exists "$directory"; then
        [ -d "$directory" ] && [ ! -L "$directory" ] || fail "private path is not a physical directory"
        [ "$(stat -c '%U:%G:%a' "$directory")" = "${runtime_user}:${runtime_group}:700" ] || \
            fail "private directory owner or mode is invalid"
    else
        install -d -o "$runtime_user" -g "$runtime_group" -m 0700 "$directory"
    fi
}

[ "$(id -u)" -eq 0 ] || fail "entrypoint must initialize the mounted disk as root"
[ -f "$bundled_control_file" ] && [ ! -L "$bundled_control_file" ] || \
    fail "bundled Batumi capability document is unavailable"

if path_exists "$storage_root"; then
    [ -d "$storage_root" ] && [ ! -L "$storage_root" ] || fail "storage root is not a physical directory"
else
    mkdir -p "$storage_root"
fi
chown "$runtime_user:$runtime_group" "$storage_root"
chmod 0700 "$storage_root"

require_private_directory "$control_directory"
require_private_directory "$state_directory"

if path_exists "$control_file"; then
    [ -f "$control_file" ] && [ ! -L "$control_file" ] || fail "capability document is not a physical regular file"
    case "$(stat -c '%U:%G:%a' "$control_file")" in
        "${runtime_user}:${runtime_group}:400"|"${runtime_user}:${runtime_group}:600") ;;
        *) fail "capability document owner or mode is invalid" ;;
    esac
else
    temporary_file="$(mktemp "${control_directory}/.capabilities.XXXXXX")"
    trap 'rm -f "$temporary_file"' EXIT HUP INT TERM
    install -o "$runtime_user" -g "$runtime_group" -m 0600 "$bundled_control_file" "$temporary_file"
    sync -f "$temporary_file"
    mv -T "$temporary_file" "$control_file"
    sync -f "$control_directory"
    trap - EXIT HUP INT TERM
fi

exec gosu "$runtime_user:$runtime_group" /app/bin/transitBff
