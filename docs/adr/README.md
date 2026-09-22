# Architecture decision records

Create an ADR for changes to layer direction, core frameworks, persisted formats, provider/privacy boundaries, map SDK/tile infrastructure, or a platform workaround that changes shared behavior. Copy `template.md`, use the next number, and record context, decision, consequences, alternatives, and verification.

The server/provider boundary is recorded in [ADR 0003](0003-transit-bff-runtime-and-provider-boundary.md).
The runtime capability control-plane is recorded in
[ADR 0004](0004-runtime-capability-control-plane.md).
The mobile BFF client and bounded offline catalog cache are recorded in
[ADR 0005](0005-mobile-transit-bff-client-and-offline-catalog-cache.md).
The controlled Transitous fallback, attribution, and capability contract are
recorded in [ADR 0006](0006-transitous-best-effort-fallback.md).
Privacy-safe BFF observability, circuit protection, synthetic probes, and the
durable schema-drift capability interlock are recorded in
[ADR 0007](0007-bff-observability-and-schema-safety.md).
The additive direct-walking API, local fallback, and coordinate privacy
boundary are recorded in [ADR 0008](0008-direct-walking-estimate-privacy-boundary.md).
The route-selection draft, bounded city-scoped snapshot migration, and cancel contract are
recorded in [ADR 0009](0009-route-selection-draft-and-city-scoped-persistence.md).
The on-demand route arrival board and its idle worker lifecycle are recorded in
[ADR 0010](0010-demand-driven-route-arrival-workers.md).
The continuous bulk Batumi feed that supersedes route polling in the configured runtime is recorded
in [ADR 0011](0011-batumi-continuous-city-feed.md).
The status-aware stop-chain projection, stable moving-speed ETA, and explicit dwell policy are
recorded in [ADR 0012](0012-batumi-status-aware-stop-chain-eta.md).
The explicit operator approval, production classification, activation gates, and operational
bounds for the Batumi Theta catalog and BatBus live feed are recorded in
[ADR 0013](0013-batumi-theta-production-approval.md).
The CI-gated Render Docker deployment and persistent capability-control storage are recorded in
[ADR 0014](0014-render-production-deployment.md).
The removal of the mobile walking-estimate experience while preserving the BFF's privacy-safe
operation is recorded in [ADR 0016](0016-remove-mobile-walking-estimate.md).
