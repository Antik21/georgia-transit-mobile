# Transit BFF capability control plane

`GET /v1/cities` is the authoritative, effective city/capability snapshot. Its
`capabilities` object always carries all seven normalized feature booleans, and
`availability` identifies only a normalized readiness/source class:

- `DEVELOPMENT_FIXTURE` plus `FIXTURE` is synthetic data, valid only with the
  explicit development fixture opt-in. It is never production transit readiness.
- `PRODUCTION_READY` plus `REVIEWED_ADAPTER` means a reviewed BFF adapter was
  registered by the deployed server. It does not reveal a provider name, URL,
  identifier, credential, or adapter implementation detail.
- `UNREVIEWED` plus `UNREVIEWED_ADAPTER` is the intrinsic safe default for an
  adapter that was not explicitly marked reviewed. It is never accepted into
  the effective city snapshot, except the explicitly configured development-only Batumi Theta
  adapter described in [its runbook](batumi-theta.md); production always rejects that exception.

Mobile BFF-client and UI runtime consumption belong to DEN-49 and DEN-47. This
control plane exposes the authoritative BFF snapshot; it does not create a
second mobile configuration, networking, persistence, or feature-flag stack.

## Effective behavior

The operator document can only reduce the registered adapter's intrinsic
capabilities. It cannot name an unregistered city, enable a false intrinsic
feature, or change an adapter's normalized `availability` metadata. A disabled
city is omitted from `/v1/cities`; every endpoint scoped to it returns
`CITY_NOT_FOUND`. A disabled feature remains visible as `false` for an enabled
city and returns `CAPABILITY_NOT_AVAILABLE` (501) before any provider call.

| API operation | Effective feature gates |
| --- | --- |
| routes and route detail | `routes` |
| direction stops | `stops` and `routes` |
| route shape | `routeGeometry` and `routes` |
| nearby stops | `stops` |
| vehicles | `vehiclePositions` and `routes` |
| arrivals | `arrivals` (`officialArrivals` only controls an official-source claim) |
| journeys | `tripPlanning` |
| walking estimate | `tripPlanning` |

Each request captures one immutable snapshot. A newly accepted revision clears
the BFF's directory/shape caches and changes cache generations, so the former
city-list TTL cannot delay a kill switch and an old cache generation cannot be
served to a new request. Calls already in progress finish under the snapshot
they started with.

## Operator document and activation

Set both `BFF_CAPABILITY_CONTROL_PATH` and
`BFF_CAPABILITY_CONTROL_STATE_DIR`; a start-up error rejects a configuration
that sets only one. The BFF reads the document from the first path at start and
then polls it every `BFF_CAPABILITY_CONTROL_POLL_SECONDS` (5–300 seconds,
default 30). It accepts at most 64 KiB of strict JSON with exactly this shape:

```json
{
  "revision": "release-20260907-1",
  "cities": [
    {
      "id": "registered-city-id",
      "enabled": true,
      "availability": {
        "readiness": "PRODUCTION_READY",
        "source": "REVIEWED_ADAPTER"
      },
      "capabilities": {
        "routes": true,
        "stops": true,
        "routeGeometry": false,
        "vehiclePositions": false,
        "officialArrivals": true,
        "tripPlanning": false,
        "arrivals": true
      }
    }
  ]
}
```

All fields are required for newly written documents. The decoder accepts an omitted
`arrivals` only for backward-compatible existing documents, where it is treated as the
same value as `officialArrivals`; new documents must state it explicitly. Unknown keys, duplicate JSON keys, duplicate or blank
city/revision values, non-boolean feature values, an unknown city, capability
expansion, or an unsafe/adapter-mismatched source/readiness pair are rejected.
Omitting a registered city disables it. A document may use an empty `cities`
array to kill all cities deliberately. Kutaisi has no registered reviewed
adapter in this repository and is therefore absent by default; attempting to
name it is rejected rather than enabling it.

`schemaInterlockAcknowledgements` is an optional, explicit recovery-only top-level
field. It may contain bounded normalized entries such as
`{"cityId":"tbilisi","capability":"arrivals"}`. It never carries a provider
URL, stop ID, probe target, credential, request detail, or free-form note. A
new revision with a matching acknowledgement is the only way to clear that
city/capability's durable synthetic-probe schema-drift latch; a restart never
clears it. See [bff-observability.md](bff-observability.md) for the required
evidence and recovery sequence.

The reviewed TTC Tbilisi adapter has a disabled-by-default operator template at
[`ttc-capability-control.example.json`](../../transitBff/ttc-capability-control.example.json).
Its server-only activation, secret injection, runtime kill switch, bounded
request budget, and bulk-directory behavior guidance are documented in the
[TTC operator runbook](ttc-adapter.md). The example deliberately carries no
provider endpoint, credential, header, raw identifier, or payload.

Write the next document to a temporary file in the same operator-owned
directory, validate its ownership and permissions locally, then atomically
rename it over `BFF_CAPABILITY_CONTROL_PATH`. The runtime requires an absolute,
physical (no symlink component) macOS/Linux POSIX path. The control file must
be a regular file owned by the BFF process user with mode `0400` or `0600`; its
existing parent directories must not be group/world writable. The state
directory must be pre-provisioned for the BFF process user with exact mode
`0700`; the BFF creates `documents/` and history files with `0700`/`0600`.
POSIX ownership/permission metadata, a no-symlink path, and atomic rename in
the same filesystem are mandatory. The BFF forces temporary-file content before
each rename and forces the parent directory after it; if any ownership, atomic
move, file force, or directory force is unavailable, the candidate is not
published and the prior in-memory snapshot remains active. It never falls back
to a less secure or non-durable filesystem operation. Use a physical path (for
example, the output of `pwd -P`) rather than a convenience symlink. Do not
expose this path through a web server and do not use an HTTP admin mutation
endpoint; this BFF provides none. The checked-in
[`development-fixture-capability-control.example.json`](../../transitBff/development-fixture-capability-control.example.json)
is synthetic development input only. It is accepted only with
`BFF_MODE=development` and `BFF_FIXTURES_ENABLED=true`.

Without a control path, the server starts closed except for the existing
explicit `development` plus `BFF_FIXTURES_ENABLED=true` fixture mode. A missing
or invalid initial production document consequently exposes no city. A future
production adapter must supply this control path/state pair before an operator
can expose it.

## Audit, last-known-good state, and rollback

Before changing the live atomic snapshot, the BFF persists an accepted document
to the operator-owned state directory. The state contains only the validated
normalized document and a SHA-256 integrity fingerprint; strict input forbids
secrets, provider URLs, and provider-specific payloads. It retains the most
recent `BFF_CAPABILITY_CONTROL_HISTORY_LIMIT` revisions (2–50, default 20) in
`documents/` plus `history-index.json`. The maximum accepted-document storage
is therefore 20 × 64 KiB by default, plus small index metadata.

History persistence is durable and crash-consistent: the BFF writes and
`force(true)`s a private temporary document, atomically renames it, and forces
its directory before doing the same for the replacement index. Only after both
durability barriers succeed can it publish the new runtime snapshot; it then
deletes unreferenced historical documents. A crash or failed write before the
index swap leaves the prior index and all of its referenced documents
recoverable. If a post-rename directory force fails, the live candidate is not
published; recovery uses whichever complete, integrity-checked index the
filesystem preserved. A post-swap cleanup failure leaves extra private files
safely in place and emits an auditable `history_cleanup_deferred` event; a
later accepted revision can collect them.

The BFF emits structured `capability_control` audit records for accepted,
restored, rollback, and rejected transitions. They contain only event type,
safe revision, generation/count, and fixed failure classification—never the
control path, document body, provider information, headers, or secrets.

Schema-interlock audit records use only the normalized city/capability and a
fixed durability classification. The interlock state is written in the same
private state directory with the same no-follow, ownership, POSIX mode, atomic
rename, file-force, and directory-force safeguards. If those safeguards cannot
be met while the interlock is configured, the BFF fails closed.

If a later file update is missing or malformed, the BFF keeps the accepted
in-memory snapshot and its persisted last-known-good document. An initial
production failure remains closed. To roll back, atomically restore the exact
prior `documents/<revision>.json` content over the control path. A historical
revision with its exact stored digest is accepted as an atomic `rollback`
transition. Reusing a revision with modified content is rejected; issue a new
revision for a new change. Do not edit history files by hand—restore a document
through the control path and let the BFF maintain the bounded index.

A startup `restored` snapshot and a historical `rollback` may restore ordinary
capability configuration, but they never clear a durable schema-interlock latch,
even if the older document contains an acknowledgement. Only a newly accepted
control revision after the latch, with its exact explicit acknowledgement, can
clear that latch. The BFF emits a fixed `schema_interlock_recovery_skipped`
audit event for restored/rollback transitions; it contains only event,
transition, and revision fields.

On `ApplicationStopped`, the watcher scope is cancelled; ordinary file reads
and writes have no long-lived handles. The read path uses no-follow access and
rechecks file identity/size around the bounded read; it never logs paths or
document contents. Provider calls retain their existing cancellation behavior.
