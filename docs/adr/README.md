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
