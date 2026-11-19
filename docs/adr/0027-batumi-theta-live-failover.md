# ADR 0027 — Fallback for Batumi live positions

Status: Accepted (2026-11-19)

[ADR 0013](0013-batumi-theta-production-approval.md) approved ThetaMaps for the
catalog and the BatBus bulk request for live positions. The city now needs to
keep publishing positions and arrival estimates through a sustained BatBus
outage.

## Decision

The existing city worker tries BatBus first on each cycle. A successful,
validated response clears the outage timer. After three minutes of consecutive
failures, the worker can fetch live positions from ThetaMaps for every route in
the current catalog. It switches back on the next valid BatBus response.

Each ThetaMaps sweep uses a ten-second time budget and at most four concurrent
requests. It accepts empty route arrays and publishes only a complete, valid
generation. Sweeps are scheduled at least ten seconds apart and honor the
provider's retry window. Capability control applies to both sources.

Both sources share the same vehicle identities, stop-chain ETA and 15-second
fresh and 60-second expiration limits. Vehicles and arrivals are published
together. Source changes clear the arrival continuity buffer. A malformed
ThetaMaps generation follows the existing schema interlock. A malformed BatBus
generation starts the outage timer so the independent source can take over.

## Verification

Run `./gradlew :transitBff:check`. Tests cover the outage boundary, valid and
invalid sweeps, recovery, and the return of a fresh city snapshot after an expiry.
