# ADR 0013: Batumi Theta catalog and BatBus live-feed production approval

Status: Accepted (2026-09-15)

## Context

The configured Batumi adapter uses two fixed HTTPS origins: Theta supplies the route, stop, and
geometry catalog from `https://thetamaps.site:54321/api`, while the continuous city-wide vehicle
feed comes from the hard-coded `https://batbus.app/api/getAllBuses` endpoint. The 2026-09-10
evaluation classified the Theta origin as development-only because production authorization had not
yet been established; the BatBus bulk feed was added and measured separately in ADR 0011. Since
then, the composed adapter has gained strict schema validation, bounded catalog and live-response
parsing, short stale-data limits, normalized opaque identifiers, capability kill switches, a
durable schema interlock, and regression coverage for routes, stops, geometry, vehicles, and
approximate ETA.

On 2026-09-15 the product operator explicitly approved using both Batumi sources in production
and selected Batumi as the only city required for the initial release. This decision is the project
record that supersedes the former authorization blocker. It does not make either source official or
expand the data contract beyond the evidence already validated by the adapter.

The operator accepts both origins under their currently observed anonymous-access conditions. No
credential, separate provider attribution requirement, or numeric quota was supplied for either
origin. The project therefore records no provider attribution link, retains the measured bounded
cadence below, and treats any later access, terms, attribution, credential, or quota change at
either origin as a reason to disable the adapter pending review.

## Decision

Classify the composed Batumi adapter as `PRODUCTION_READY` with source `REVIEWED_ADAPTER`. Keep it
disabled by default and require all of the following before construction in any deployed runtime:

1. `BATUMI_THETA_ENABLED=true`;
2. the exact fixed Theta catalog origin `https://thetamaps.site:54321/api`;
3. the hard-coded BatBus live origin `https://batbus.app/api/getAllBuses`;
4. `BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT=I_APPROVE_THETA_PRODUCTION_USE`; and
5. for production, the durable schema interlock plus the paired operator-owned capability-control
   document and state directory.

The acknowledgement variable and token retain their historical `THETA` name for configuration
compatibility, but the acknowledgement applies to the complete composed Batumi adapter and
explicitly approves calls to both fixed origins. It must not be interpreted as approval for an
operator-supplied BatBus URL or any third origin.

Production exposes routes, stops, route geometry, vehicle positions, and BFF-derived approximate
arrivals. `officialArrivals` and `tripPlanning` remain false. Arrival responses remain
`CLIENT_ESTIMATE`; the UI must continue to describe them as approximate. Raw upstream identifiers,
vehicle names, URLs, payloads, and implementation details remain inside the BFF.

Use one production instance until a higher aggregate request budget or a single-feed-owner
distribution design is approved. While either `vehiclePositions` or `arrivals` is enabled, that
instance makes one sequential BatBus `getAllBuses` request every five seconds. The Theta catalog is
refreshed at most once every ten minutes while healthy; a failed refresh retains a validated
in-memory catalog for up to seven days and uses a twenty-second retry cooldown. The capability
document remains the immediate egress kill switch. Disable affected capabilities, or disable the
city to stop all Batumi upstream egress, if either upstream contract, access conditions, or observed
data quality changes.

Neither fixed origin currently requires a provider credential. If either introduces authentication,
its credential must be supplied only through the Render secret boundary and must never enter mobile
code, repository files, capability documents, logs, or GitHub build output. GitHub Secrets may hold
only narrowly scoped deployment credentials such as a Render deploy-hook URL; runtime provider
configuration belongs in Render.

## Consequences

- A Batumi-only production BFF may start and report ready when its activation and capability-control
  requirements are satisfied.
- Mobile clients see only normalized production readiness and never become coupled to Theta or
  BatBus.
- The prior evaluation remains historical evidence, but its NO-GO classification is superseded.
- Horizontal scaling remains disabled until upstream request budgeting or feed ownership is reviewed.
- Production rollout still requires health monitoring and an operational response that can disable
  Batumi capabilities immediately.

## Alternatives

- **Keep Batumi development-only:** rejected by the operator's explicit production approval and
  initial Batumi-only release scope.
- **Remove explicit activation gates:** rejected because accidental upstream traffic and an
  unbounded rollout must remain impossible.
- **Present ETA as official:** rejected because Theta provides no official arrival timestamp or ETA.
- **Call Theta directly from mobile:** rejected because it would violate the BFF provider boundary.

## Verification

```text
./gradlew spotlessCheck :transitBff:check :transitBff:installDist
```

For deployment verification, start one production instance with the approved capability document,
confirm `/healthz` is ready, verify Batumi is `PRODUCTION_READY`/`REVIEWED_ADAPTER`, and confirm that
routes, stops, geometry, vehicles, and approximate arrivals remain bounded by their capability
switches and stale-data limits.
