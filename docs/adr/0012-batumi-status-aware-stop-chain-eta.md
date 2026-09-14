# ADR 0012: Status-aware Batumi stop-chain ETA

Status: Accepted (2026-09-14)

## Context

The first Batumi ETA implementation inferred direction and speed from three GPS observations. Live
responses arrive at fractional five-second intervals, but whole-second duration arithmetic and hard
movement thresholds could reject every nearby bus. A stopped bus disappeared from predictions, and
a bus within 15 metres of a stop on a loop was treated as one complete circuit away. The provider's
`Status` direction/route-part field was validated but discarded.

The product also requires explicit passenger dwell: every intermediate stop contributes one minute.
An effective speed that already includes dwell cannot be combined with that rule without counting
stops twice.

## Decision

Build each Batumi route as one cyclic stop chain sorted by numeric `Status`, then `Order`. Preserve
the live bus `Status` inside the provider boundary and project its GPS point only onto stop-chain
segments whose endpoints have that status. The published route shape remains an independent
150-metre GPS corridor check.

GPS determines position only. ETA uses the route's stable moving-speed profile, with a 25.1 km/h
fallback for unknown routes, plus 60 seconds for every intermediate stop. The target stop is not a
dwell stop. A bus up to 150 metres beyond the target is treated as arriving now; only a target more
than 150 metres behind wraps to the next circuit. Passenger ETA is rounded to a whole minute with a
minimum of one minute.

The optional normalized `vehicleId` is included in an arrival so a client can associate a prediction
with a map marker without exposing the raw upstream vehicle name. The city-wide feed applies its
one-generation candidate continuity rule before atomically publishing the route boards.

## Consequences

- ETA is available from the first valid city snapshot; there is no movement-sample warm-up.
- A bus dwelling at a stop remains a valid arrival candidate.
- Parallel or crossing route branches no longer override the bus's upstream route part.
- Dwell cost is explicit and testable rather than hidden in a short GPS-derived speed window.
- The embedded moving-speed table must be reviewed when BatBus changes its route set or calibration.
- This remains an unreviewed development integration subject to the upstream approval described in
  ADR 0011.

## Alternatives

- **Keep instantaneous GPS speed:** rejected because dwell, polling jitter, and GPS noise remove the
  nearest bus from the board.
- **Use effective speed plus one minute per stop:** rejected because the effective profile already
  includes stop delays and would double-count dwell.
- **Ignore `Status` and infer direction geometrically:** rejected because crossing and parallel route
  branches are ambiguous.

## Verification

Regression tests cover status ordering and preservation, a route-10-like 350-metre next stop,
one-minute intermediate dwell, a stopped bus, the 150-metre passed-stop tolerance, and global-feed
candidate continuity. Run:

```text
./gradlew spotlessCheck :transitBff:check :shared:testAndroidHostTest :androidApp:assembleDebug
```
