# Transit BFF observability and safety runbook

`/metrics` is an internal, deployment-private Prometheus scrape endpoint. It is
not part of the mobile API and must never be exposed through public ingress.
The repository supplies the BFF exporter and alert-rule asset, not a monitoring
backend, alert receiver, ingress policy, real probe target, hosted-use approval,
or multi-replica aggregate rate limit. Those are required deployment inputs and
remain unconfigured/fail-closed by repository defaults.

## Safe activation and isolation

`BFF_METRICS_ENABLED=true` enables `GET /metrics`; its only supported purpose is
an authenticated or network-isolated Prometheus scrape path. Restrict the route
with a private listener, service mesh/network policy, reverse-proxy ACL, or
equivalent deployment control. Do not route it through the public mobile
ingress, add browser access, scrape it from a public status page, or put an
operator reference/contact in its labels.

The text body is Prometheus 0.0.4: UTF-8, LF line endings, a final LF, one
`HELP`/`TYPE` pair before samples, cumulative histogram buckets with
`le="+Inf"` equal to `_count`, and no OpenMetrics `# EOF`. `/metrics` has no
HTTP-operation marker and never increments itself, queries a provider, reads a
cache, changes a circuit, or executes a probe. `/healthz` only reports BFF
readiness and likewise does not invoke a provider or alter a circuit.

Capability-enabled and schema-latch gauges are published from an immutable,
generation-ordered capability-control transition after its control locks are
released, including startup restore, accepted updates, and rollback. A scrape
or ordinary request does not refresh them. The bounded exporter writes `0` for
known configured city/provider/capability tuples absent from a closed snapshot,
so a closure cannot leave prior enabled gauge values behind.

The registry is process-local and bounded: its metric names, label names, and
label values come from Kotlin enums and registered adapter city/provider pairs.
It cannot accept caller strings. In particular, it never exports a raw URL or
path/query value, public route/stop/trip ID, coordinate, request ID, header/API
key, Transitous contact/approval reference, exception text, source metadata, or
payload. The stable label dimensions are `city`, `provider`, `capability`, and
`operation`; HTTP adds only `status_class` (`1xx`, `2xx`, `3xx`, `4xx`, `5xx`,
or the finite defensive value `other`).

## Metric glossary

| Metric family | Meaning |
| --- | --- |
| `bff_http_requests_total`, `bff_http_request_duration_seconds` | BFF request count and latency by enum operation/status class. |
| `bff_provider_requests_total`, `bff_provider_request_duration_seconds` | Provider outcome/latency, including unavailable/timeout/bad-gateway, JSON decode, normalized schema, fresh/stale, and empty/nonempty outcomes. Empty ratio is derived as `empty / (empty + nonempty)`. |
| `bff_provider_cache_lookups_total` | `hit`, `miss_owner`, or `coalesced` from the existing TTL cache/single-flight primitives. |
| `bff_provider_data_age_seconds` | Age from the adapter's original accepted `observedAt`, never a scrape or cache-served timestamp. |
| `bff_provider_events_total` | Bounded retry, rate-budget rejection, circuit, stale-response, and schema-latch events. |
| `bff_provider_circuit_open` | Current in-process circuit state per bounded city/provider/capability. |
| `bff_provider_capability_enabled`, `bff_provider_schema_interlock_latched` | Effective capability and durable schema-safety overlay state. |
| `bff_probe_*` | Internal synthetic probe enablement, last fresh success/failure, and last fresh realtime timestamp. |

`BFF_CIRCUIT_FAILURE_THRESHOLD`, `BFF_CIRCUIT_WINDOW_SECONDS`, and
`BFF_CIRCUIT_OPEN_SECONDS` bound a coroutine-safe closed/open/half-open circuit
per city/provider/capability. Eligible timeout, unavailable, and final eligible
5xx failures trip it. Only one half-open request is admitted after the open
duration; others receive the existing safe 503 with bounded `Retry-After`.
Cancellation propagates. Transitous applies this around the upstream attempt
path, so its existing exact-key last-known-good page can still be returned
`stale: true` while a circuit is open; malformed/schema responses never use LKG.
All other registered adapters are protected at the `TransitService` provider boundary.
Transitous declares its inner protection explicitly, preventing a duplicate outer
breaker while preserving its safe stale fallback.

Transitous' two concurrent and 24 starts/minute budgets, including at most two
retries, remain per-JVM upstream protection. They are not a fleet-wide limit:
operators must obtain hosted-use approval and set deployment-wide rate/replica
controls before adding replicas. A probe counts against the same budgets.

## Synthetic probes

All defaults are safe: `BFF_PROBES_ENABLED=false`; CI and local fixture runs do
not contact a real provider. When explicitly enabled, the interval must be
60–300 seconds (`BFF_PROBE_INTERVAL_SECONDS`). `ProbeRunner` is lifecycle-bound
to the BFF and cleanly cancels on shutdown; it has no HTTP trigger.

A target must be declared by a provider adapter. For Transitous, the operator
sets `TRANSITOUS_PROBE_STOP_ID` to one of the server-only
`TRANSITOUS_TBILISI_STOP_IDS` and may set
`TRANSITOUS_PROBE_REALTIME_EXPECTED=true`. The raw target is never logged,
returned, or used as a metric label. The probe passes normal capability,
circuit, retry, and rate-budget controls but bypasses LKG when deciding fresh
upstream success. A stale page never refreshes probe success/realtime gauges.

Schedule-only Transitous arrivals (`officialArrivals=false`) should normally use
`TRANSITOUS_PROBE_REALTIME_EXPECTED=false`; otherwise the explicit realtime
freshness alert intentionally fires. A target disabled by a capability control
or schema latch emits `bff_probe_target_enabled=0` and must not page.

## Schema-drift interlock and recovery

Production Transitous activation requires
`BFF_SCHEMA_INTERLOCK_ENABLED=true`, the normal private capability-control
document/state pair, and bounded `BFF_SCHEMA_DRIFT_THRESHOLD` (2–10) plus
`BFF_SCHEMA_DRIFT_WINDOW_SECONDS` (60–3600). Only repeated JSON decode or
normalized-schema failures observed by synthetic probes count. The latch is an
atomic capability-control overlay for that city/capability; it blocks provider
calls, persists under the same POSIX ownership/no-symlink/private-mode/atomic
rename/file-and-directory-fsync safeguards as capability history, and survives
restart. If its durable state cannot be read/written, the BFF fails closed
rather than continuing to call a provider.

Recovery is deliberately manual:

1. Capture the bounded metric/audit evidence and provider-safe reproduction.
   Never attach raw response bodies, URLs, target IDs, keys, or user locations.
2. Fix or approve the provider contract change and verify the target through an
   operator-approved exercise.
3. Write a **newly accepted** capability-control revision with the ordinary
   desired capability plus an explicit acknowledgement. A startup restore or
   historical rollback document cannot clear a latch, even if it contains one.
   For example:

   ```json
   "schemaInterlockAcknowledgements": [
     {"cityId": "tbilisi", "capability": "arrivals"}
   ]
   ```

4. Atomically publish that control document using the procedure in
   [bff-capability-control.md](bff-capability-control.md). The BFF durably
   clears only the acknowledged latch before publishing the revision.
5. Confirm the capability/latched gauges and a fresh probe success. If unsafe,
   roll back by disabling the capability in another new revision; never edit the
   interlock state file or expect a restart to clear it.

## Alerts, fallback, rollback, and ownership

[`deploy/ops/transit-bff-alerts.yaml`](../../deploy/ops/transit-bff-alerts.yaml)
contains deployment-agnostic rule definitions for scrape availability, no fresh
probe success, realtime freshness, schema latch, circuit open, rate budget,
elevated provider failures/schema parse, and stale/data age. The
`TransitBffScrapeDown` rule expects the deployment's private scrape target to
use the canonical Prometheus `job="transit-bff"` label and pages after
`up{job="transit-bff"} == 0` for five minutes; deployment configuration must
preserve that label or consciously replace the rule selector. The two five-minute
freshness rules use `time() - timestamp > 300` and a target-enabled-since signal;
they do not also use `for: 5m`, which would delay notification to roughly ten
minutes.
They explicitly join `bff_probe_target_enabled==1`, preventing a deliberately
disabled capability from paging. The receiver, severity routing, runbook URL,
and ownership/on-call rota are external deployment inputs.

Validate rules with the Prometheus version selected by deployment, for example:

```text
promtool check rules deploy/ops/transit-bff-alerts.yaml
curl --fail --silent http://private-bff/metrics > /tmp/transit-bff.metrics
promtool check metrics /tmp/transit-bff.metrics
```

No Prometheus binary is committed here, so CI performs Kotlin/format checks;
the operator must run the two `promtool` checks against the selected backend.
Rollback/kill switch is a new capability-control revision disabling the city or
affected feature. For Transitous-specific policy, LKG limits, activation, and
fallback behavior, use [transitous-fallback.md](transitous-fallback.md). For
capability-file permissions, persistence, and exact rollback mechanics, use
[bff-capability-control.md](bff-capability-control.md).
