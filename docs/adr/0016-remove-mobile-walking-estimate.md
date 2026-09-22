# ADR 0016: Remove the mobile walking-estimate experience

Status: Accepted (2026-09-22)

Supersedes: the mobile-client and stop-sheet portions of ADR 0008

## Context

ADR 0008 introduced a privacy-safe direct-walking estimate for the stop-arrivals sheet. The
mobile client requested it for a fresh location fix, displayed a local fallback when necessary,
and carried the related domain, presentation, localization, and smoke-test surface.

The stop-arrivals experience is being simplified to focus on arrivals and the routes that serve
the stop. It no longer needs the device location or a walking estimate. Keeping the unused mobile
path would retain unnecessary location-driven behavior and a larger UI/test surface.

The BFF's `POST /v1/cities/{cityId}/walking-estimate` operation remains an additive, privacy-safe
contract. It is not removed by this decision because it is a public BFF capability and may have
non-mobile consumers. Its no-store, coordinate-minimization, and provider-boundary guarantees in
ADR 0008 remain in effect.

## Decision

Remove walking-estimate requests, local fallback calculations, UI, localization, and mobile
smoke tests from the Android and iOS shared application. The stop-arrivals sheet shows arrivals
and serving routes only, and it no longer depends on a location permission or a fresh location
fix.

Retain the BFF walking-estimate endpoint and its existing privacy contract unchanged. Any future
mobile use must be introduced through a new ADR that defines the product purpose and privacy
boundary before reintroducing coordinate-bearing client behavior.

## Consequences

- Opening a stop remains useful without location access; no walking distance or duration is shown.
- Mobile code no longer sends device coordinates to the BFF for this screen and no longer keeps a
  local approximation of a walking journey.
- The BFF operation, its provider adapters, and its no-store safeguards remain compatible for
  existing or future non-mobile callers.
- ADR 0008 is superseded only where it required mobile walking-estimate behavior; its BFF privacy
  constraints remain the governing boundary for the retained operation.

## Alternatives

- **Keep the mobile UI but hide it when location is unavailable:** rejected; it retains the
  location-driven implementation and an inconsistent stop-sheet experience.
- **Delete the BFF endpoint too:** rejected; this PR removes only the mobile consumer and does not
  establish that other consumers may be broken.
- **Replace it with straight-line distance:** rejected; a straight-line value is not a walking
  estimate and would recreate the product ambiguity ADR 0008 was designed to avoid.

## Verification

```text
./gradlew :shared:testAndroidHostTest :androidApp:assembleSandbox
```

QA verifies that stop arrivals open and refresh without a location permission, and that no
walking estimate is rendered in the sheet.
