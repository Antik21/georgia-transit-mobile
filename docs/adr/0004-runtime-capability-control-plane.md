# ADR 0004: Operator-managed runtime capability control plane

Status: Accepted (2026-09-07)

## Context

City/provider availability can change faster than a mobile or BFF binary can
be released. The BFF already has one city-scoped adapter boundary and normalized
six-feature capabilities, but intrinsic adapter registration alone cannot make
an emergency city or per-feature kill switch safe. A stale city TTL can also
continue exposing a city after an operator disables it.

The control must not move provider decisions, credentials, or a remote-config
stack into shared Android/iOS code. It must survive a transient control-file
failure without accidentally exposing a provider, retain auditable rollback
state, and be safe under concurrent HTTP requests and runtime reloads.

## Decision

Keep the control plane inside `:transitBff`. A configured operator-owned JSON
document is polled at a bounded 5–300 second interval and decoded with strict
unknown-key, duplicate-key, type, revision, city, source/readiness, and
intrinsic-capability validation. The document has only normalized city IDs,
enablement, availability metadata, and the six booleans. It never accepts a
provider URL, provider DTO, credential, secret, or arbitrary metadata.

An adapter defines an intrinsic `City` capability ceiling and normalized
availability pair. The effective snapshot is their intersection with an
operator document. The pair is immutable from the document: `FIXTURE` must be
`DEVELOPMENT_FIXTURE` and explicit development fixture mode; `REVIEWED_ADAPTER`
must be `PRODUCTION_READY` and match a registered reviewed adapter. An unknown
city—including unregistered Kutaisi—or an attempt to enable beyond intrinsic
capability is rejected. Disabled cities are absent and return `CITY_NOT_FOUND`;
disabled features return the existing 501 before a provider call.

`UNREVIEWED`/`UNREVIEWED_ADAPTER` is the intrinsic safe default for an adapter
that has not explicitly declared production review. It is never eligible for an
effective city, preventing a newly registered adapter from becoming live by
accidental default.

The accepted result is a single immutable, atomically swapped snapshot. Every
service operation captures one snapshot before making a provider decision.
`/v1/cities` reads it directly rather than through a TTL cache; a newer snapshot
invalidates directory/shape caches and changes cache generations. Calls already
started may complete under the snapshot that admitted them, but no new call
observes stale effective flags.

The BFF persists an accepted strict document and digest in an operator-owned
state directory before publishing it. A bounded history (default 20 documents,
each at most 64 KiB) permits recovery after a transient missing/malformed input
and a rollback by atomically restoring an exact earlier document. Reusing a
historical revision with different bytes is rejected; restoring exact historical
bytes is recorded as a rollback. Structured audit logs include only safe
revision, transition, generation/count, and fixed failure class. They omit the
document, paths, provider details, request data, and secrets. No admin HTTP
mutation endpoint is introduced.

The filesystem boundary is fail-closed and POSIX-only. Control documents must
be private regular files owned by the BFF process user; the state directory and
its generated history directory/files use exact `0700`/`0600` permissions.
Existing parent directories cannot be group/world writable, and every checked
path component must be non-symlink. The BFF requires POSIX ownership/
permission metadata and same-filesystem atomic moves rather than falling back
to a permissive or non-atomic operation. It writes and `force(true)`s a private
temporary file before each rename, then forces the private parent directory
after each document/index rename. Failure to establish either durability barrier
prevents publishing the candidate runtime snapshot. It verifies final-file
identity and size around no-follow bounded reads, while never logging a path or
document.

History persistence durably writes the document, durably replaces the history
index, then performs best-effort garbage collection. Thus a failure before the
index swap retains the old index and all indexed documents; a failed post-rename
directory force leaves the candidate unpublished and recovery selects a
complete integrity-checked index if preserved. A cleanup failure retains extra
private files safely and produces a redacted audit event for later collection.

With no control document/state pair, the baseline is closed. The existing
`BFF_MODE=development` plus `BFF_FIXTURES_ENABLED=true` path is the sole
exception: it can expose the explicitly synthetic `demo` fixture, labelled
`DEVELOPMENT_FIXTURE`/`FIXTURE`. Production continues to fail at startup when
no reviewed adapter exists, and a future registered production adapter remains
closed until a valid operator document is accepted or restored.

## Consequences

- Mobile clients consume one authoritative effective BFF snapshot; DEN-49 and
  DEN-47 own their client/UI handling. This ADR adds no mobile remote-config,
  networking, serialization, DI, state, or persistence stack.
- Operators must provision ownership-restricted document and state paths,
  use physical non-symlink paths on a POSIX filesystem, update the live
  document atomically, and preserve the state directory across BFF restarts.
  The exact procedure is in
  [`bff-capability-control.md`](../development/bff-capability-control.md).
- An invalid update causes an auditable rejection and retains last known good
  behavior rather than partially applying a city or capability. Initial
  production absence/invalidity is intentionally not treated as readiness.
- Future adapter reviews must declare the normalized availability pair and
  verify its adapter's licence, cost, quota, attribution, security, and
  provider contract before registering a city.

## Alternatives

- **Mobile remote-config or per-platform flags:** rejected because they would
  duplicate the BFF authority, risk version skew, and cannot prevent direct
  provider calls or protect provider details.
- **An unauthenticated/admin HTTP mutation endpoint:** rejected because it
  enlarges the public attack surface and adds authentication/authorization
  infrastructure before an operator control service has been chosen.
- **Adapter registration only:** rejected because binary-only emergency
  disablement cannot meet a short operational response window.
- **Best-effort JSON parsing or partial merge:** rejected because an unknown
  capability or malformed update could unintentionally expose a provider.
- **Discard last-known-good state on any file error:** rejected because a
  transient filesystem/distribution failure would turn a valid running service
  into a needless outage.

## Verification

```text
./gradlew --no-daemon --no-build-cache spotlessCheck
./gradlew --no-daemon --no-build-cache :transitBff:compileKotlin
./gradlew --no-daemon --no-build-cache :transitBff:check :transitBff:installDist

# Development fixture only; no production readiness is implied.
BFF_MODE=development BFF_FIXTURES_ENABLED=true ./gradlew :transitBff:run
curl -i http://127.0.0.1:8080/v1/cities
```

Before enabling a future reviewed adapter, verify an atomic enable/disable,
each of the six feature gates, unknown/malformed/duplicate documents,
unchanged-revision rejection, exact historical rollback, restart restoration,
cache invalidation, cancellation/shutdown, audit redaction, and Android/iOS
consumer behavior separately.
