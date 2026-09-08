# ADR 0007: Privacy-safe BFF observability and schema safety

Status: Accepted (2026-09-08)

## Context

The BFF needs operational visibility for normalized provider availability,
cache/coalescing, freshness, rate protection, and schema drift. Conventional
request telemetry can accidentally expose user locations, public IDs, URLs,
headers, provider contacts, or error/payload content; it can also create
unbounded time series. Transitous is explicitly opt-in and rate bounded, and
its existing last-known-good (LKG) response must retain its `stale` meaning
during an upstream outage. Repeated provider schema drift needs an automatic,
durable safety response that uses the BFF's existing capability authority.

## Decision

Keep observability inside `:transitBff`, without Prometheus, Resilience4j, or a
second telemetry/network/cache/DI stack. A synchronized, bounded in-process
registry exports Prometheus 0.0.4 text at internal `GET /metrics`. Its metric
and label names are static; label values are finite enums plus the configured
adapter city/provider pairs. HTTP uses stable operation/route-template enums
and finite `1xx`/`2xx`/`3xx`/`4xx`/`5xx`/`other` status classes (`304` is
`3xx`). It never accepts a request string as a
label or exports paths, query values, IDs, coordinates, request IDs, headers,
keys, contacts, references, exception text, URLs, or payloads. `/metrics` and
`/healthz` do not call providers or mutate circuits.

Capability-control transitions emit an immutable, generation-ordered bounded
capability/latch state to the registry only after control locks are released.
This includes startup restore, accepted updates, and rollback; scrapes and
ordinary provider requests never refresh these gauges.

Instrument existing `BoundedKeyedTtlCache` and `SingleFlight` observer seams:
each reports only `hit`, `miss_owner`, or `coalesced`. `TransitService` carries
a static provider operation/capability to adapter calls and records normalized
outcomes, empty/nonempty counts, data age from original `observedAt`, and stale
results. Transitous reports retries and its existing local two-concurrent,
24-starts/minute attempt budget. These remain per-JVM protection, not a
fleet-wide quota claim.

Use a coroutine-safe in-process circuit breaker per bounded
`{city, provider, capability}`. It opens after configured eligible timeout,
unavailable, or final 5xx observations in a configured window, fails fast with
a safe bounded 503 `Retry-After`, and permits exactly one half-open attempt.
Cancellation propagates. The Transitous circuit composes around its actual
upstream path so an eligible exact-key LKG can still be emitted as `stale:true`
while open; malformed/schema responses never become LKG.

Add a lifecycle-bound internal `ProbeRunner`. It is disabled by default, has a
strict 60–300 second interval, runs only adapter-declared server-only targets,
uses normal capability/circuit/retry/budget controls, and bypasses LKG when
deciding fresh success. It never exposes a public probe endpoint or target
identifier. A target explicitly says whether realtime is expected, avoiding a
false perpetual alert for schedule-only Transitous arrivals.

Classify malformed upstream JSON separately from normalized schema validation,
while preserving the existing safe public 502. Only repeated synthetic-probe
schema classifications can trigger the durable schema-drift interlock. The
interlock is an overlay owned by `RuntimeCapabilityControl`; it atomically
disables only the affected normalized capability and blocks new provider calls.
Its state uses ADR 0004's private POSIX/no-follow/owner/mode/atomic
rename/file-and-directory-fsync safeguards. A configured durable-state failure
fails closed. A latch survives restart and clears only on a newly accepted
capability control revision with an explicit normalized acknowledgement for that
exact city/capability; startup restore and historical rollback preserve latches
even when an old document carries an acknowledgement.

Repository-owned alert rules combine `up{job="transit-bff"} == 0` with
`absent(up{job="transit-bff"})` for failed discovered scrapes and completely
missing target series under the canonical Prometheus `job="transit-bff"`
selector, then document no-success/realtime freshness, schema latch, circuit,
rate budget, parsing/5xx, and stale/data-age conditions. Private ingress,
Prometheus backend/receiver, operator-owned real probe target,
Transitous hosted-use approval, and aggregate multi-replica limits remain
deployment inputs; defaults make no real upstream request.

## Consequences

- Operators get privacy-safe bounded signals without a new production SDK or
  cost/licence obligation; deployment still owns scrape isolation and alerting.
- Production Transitous activation requires the schema interlock and existing
  private capability state directory. Development fixtures remain deterministic
  because probes are disabled unless explicitly configured.
- Recovery requires evidence and a new acknowledged control revision, not a
  restart or direct state-file edit. The detailed procedure is in
  [`bff-observability.md`](../development/bff-observability.md).
- Monitoring reflects per-process circuits and budgets. Replica/fleet limits
  require separate operator enforcement and upstream approval.

## Alternatives

- **Prometheus client SDK or hosted APM:** rejected: a new telemetry stack adds
  dependency, cardinality, configuration, cost, and privacy surface when the
  BFF needs a small bounded text exporter.
- **Resilience4j/external breaker:** rejected: an in-process coroutine-safe
  breaker preserves the existing Ktor/coroutines stack and LKG composition.
- **Admin endpoint to clear schema latches:** rejected: it expands the public
  security surface and duplicates ADR 0004's control authority.
- **Clear latches on restart or after time:** rejected: that silently restores
  an unsafe capability without operator acknowledgement.
- **Probe public IDs/coordinates directly:** rejected: it risks provider abuse
  and telemetry/privacy leakage; adapters own approved targets instead.

## Verification

```text
./gradlew --no-daemon --no-build-cache spotlessCheck
./gradlew --no-daemon --no-build-cache :transitBff:compileKotlin :transitBff:check

# operator deployment check; Prometheus is not bundled by this repository
promtool check rules deploy/ops/transit-bff-alerts.yaml
curl --fail --silent http://private-bff/metrics > /tmp/transit-bff.metrics
promtool check metrics /tmp/transit-bff.metrics
```

Before production activation, verify ingress isolation, zero default upstream
traffic, finite labels/redaction, cache ownership/coalescing, breaker/LKG,
retry/budget accounting, probe cancellation, no-success/realtime alerts,
schema latch/restart/revision acknowledgement, and control-file durability.
