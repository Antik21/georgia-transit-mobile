# ADR 0023: Journey endpoints and the planning coordinate boundary

Status: Accepted (2026-11-12)

Supersedes in part: [ADR 0016](0016-remove-mobile-walking-estimate.md), only where it forbids the
mobile client from sending coordinates to the BFF. The stop-arrivals sheet stays location-free.

## Context

Journey planning ([ADR 0022](0022-batumi-in-house-journey-planner.md)) needs an origin A and a
destination B. Users must be able to plan in two ways:

- from **their current location** to a point;
- between **two arbitrary points** on the map. This covers planning a trip in advance, planning for
  someone else, and planning without granting location access.

Swapping A and B must work. A swapped plan routes from a chosen point to the user's current
location.

Coordinates are sensitive. A current-location fix reveals where the user is. A point pair reveals
intent, for example home to work. ADR 0008 established a privacy boundary for coordinate-bearing BFF
requests. ADR 0016 removed the only mobile consumer and required a new ADR before any client
behaviour that sends coordinates returns.

The app already has a foreground location flow (`LocationSession`, `UserLocationFix`). It enforces a
two-minute maximum fix age, a 250 m precise-accuracy bound, and a 5 km approximate bound. Android
declares coarse and fine location. The iOS usage text currently says only that location centres the
map and finds nearby stops.

## Decision

### Endpoint model

Shared domain owns:

```text
sealed interface JourneyEndpoint {
    data object CurrentLocation : JourneyEndpoint
    data class MapPoint(val point: GeoPoint) : JourneyEndpoint
}
JourneyRequest(origin: JourneyEndpoint, destination: JourneyEndpoint, transferBuffer)
```

The model has these rules:

- At most one side may be `CurrentLocation`. Swapping exchanges both sides unchanged.
- Each `MapPoint` must lie inside the selected city's service bounds. Otherwise the client shows an
  out-of-area message and sends nothing.
- The planner is not called when A and B are within 150 m of each other. The UI shows "you are
  already there" and offers a walk-only estimate.
- Stop and address endpoints are out of scope. Stop endpoints can be added later without a new
  privacy decision, because they resolve to public stop coordinates. Geocoded addresses need a
  separate geocoding-provider ADR.

### Choosing points

- A long press on the map drops a pin with two actions, "Route here" and "Route from here".
- A planner panel shows the A and B fields, each either "My location" or "Point on map", plus swap
  and clear.
- A pin can be re-picked by another long press while its field is active.
- "Route here" defaults the origin to `CurrentLocation` only when permission is already granted.
  Otherwise the origin stays empty and the panel asks the user to choose A. The app never shows the
  system permission prompt just because the user long-pressed.

### Location use

The permission is requested only after an explicit "My location" choice, with the existing
foreground (while-in-use) flow. Background location is never requested.

A fix resolves `CurrentLocation` only when it meets both conditions:

- it is at most 120 s old when the request is sent;
- its accuracy is at most 250 m.

An approximate-only grant (Android coarse, iOS reduced accuracy) that misses this bound does not
block planning. The panel explains the problem and offers a map point. On iOS it may also offer a
one-time temporary full-accuracy request with a purpose string.

Denial or any unavailable state leaves arbitrary-point planning fully usable.

While results are visible, a periodic re-plan uses the newest gated fix. A re-plan happens only
after the user has moved more than 50 m, or when the ETA refresh interval of ADR 0022 elapses.

### Transmission

Coordinates leave the device in exactly one operation:

```text
POST /v1/cities/{cityId}/journeys/plan
{ from:{lat,lon}, to:{lat,lon}, departureAt?, maxTransfers, transferBufferSeconds, locale }
```

- The client rounds both points to 4 decimal places (about 11 m) before sending.
- The body never says whether a side was the device location. The BFF cannot tell a fix from a map
  point.
- No other request, header, query string, route argument, or telemetry event carries either
  point.

### Client retention

Endpoints, fixes, and plan responses live only in Map presentation memory. They are never placed in:

- persisted settings, the offline catalog cache (ADR 0005), or saved instance state;
- navigation routes, logs, analytics, or crash breadcrumbs.

Process death or leaving the city clears the planner. Recents, favourites, or "home/work" need a
new ADR.

Android and iOS Sandbox network inspectors redact these fields:

- `from` and `to` in the request;
- `fromPosition`, `toPosition`, and `geometry` in the response.

### BFF handling

The BFF inherits the ADR 0008 boundary unchanged:

- `Cache-Control: no-store, no-cache, max-age=0` and `Pragma: no-cache`;
- call logging keeps only method, path, status, and request ID;
- no body logging, cache, SingleFlight, LKG, or coordinate-derived key;
- the static metric operation `journey_plan`.

Out-of-area or invalid coordinates return the existing `400` envelope without echoing values. The
planner discards coordinates when the request completes.

### Disclosure

Update these disclosures before release:

- the iOS `NSLocationWhenInUseUsageDescription` text in every localization, stating that route
  planning sends an approximate location to the Geo Transit server;
- the App Store privacy label: location collected for app functionality, not linked to identity,
  not used for tracking;
- the Google Play Data safety form;
- the in-app privacy text.

The browser target (ADR 0019) and the Telegram Mini App client (ADR 0020) use the same domain model
and the same rules. Their geolocation adapter and map long-press bridge are separate work.

## Consequences

- Planning works with no location permission at all. Current location is a convenience, not a
  requirement.
- The BFF receives planning coordinates. Exposure is bounded by rounding, one no-store operation,
  no origin-kind signal, and no server or client retention.
- Store privacy disclosures change and must be reviewed with the release.
- Map gains a map long-press platform event and an endpoint-pin layer on Android and iOS. The
  stop-arrivals sheet remains location-free, as ADR 0016 decided.
- Saved places and address search remain explicitly out of scope.

## Alternatives

- **Current location as the only origin.** Rejected: it blocks advance and third-party planning,
  and it forces a permission prompt.
- **Send the origin kind (device or map) to the BFF.** Rejected: the planner does not need it, and
  it would mark which coordinate is the user's position.
- **Coordinates in a GET query string.** Rejected: proxies and edge infrastructure may retain URLs.
- **Plan on the device so coordinates never leave it.** Rejected in ADR 0022: it would move the
  provider-derived graph and live ETA model into the app.
- **Coarser rounding (3 decimals, about 110 m).** Rejected: it materially distorts the first and
  last walks that the feature must report.

## Verification

Shared tests cover:

- endpoint validation: one current location at most, the out-of-area rule, and the A≈B threshold;
- swap;
- the fix gate (age, accuracy, approximate-only) re-checked immediately before each request;
- rounding;
- latest-wins cancellation;
- the moved-50 m re-plan rule;
- the absence of endpoints in persisted state and navigation.

BFF tests cover the no-store headers, absent coordinates in logs and metrics, out-of-area `400`
without echo, and inspector redaction.

QA verifies on Android and iOS:

- planning A→B with location denied;
- "My location" to a point, and the swapped direction;
- an approximate-only grant;
- that the stop-arrivals sheet still opens without location.

```text
./gradlew spotlessCheck :transitBff:check :shared:testAndroidHostTest :androidApp:assembleSandbox
```
