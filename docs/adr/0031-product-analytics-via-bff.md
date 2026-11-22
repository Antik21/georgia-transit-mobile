# ADR 0031 · Product analytics through the Transit BFF

## Context

Geo Transit needs Amplitude segmentation by launch client, unique web app visitors by entry source,
usage of map tools, and website interactions.
Clients already share a Transit BFF and may only send requests to it. Journey coordinates and Telegram
identity data stay outside the analytics contract.

## Decision

- Use the app-scoped common Kotlin session across map screens and navigation. The first foreground
  opens a session. A cold app launch or page reload creates a new session. A return after at least
  30 minutes in the background also starts a new session.
- Use a random installation ID backed by preferences or same-origin browser storage. It survives
  restarts when storage is available. In the absence of logins, profiles are per installation or
  browser store. Telegram launch context is determined by the WebApp platform; only the classification
  enters analytics.
- Set the Amplitude user property `client` at native session start via `$identify`.
  Map a browser or Telegram Mini App session start to `web_app_opened`. Its event property
  `entry_source` is `browser` or `telegram`. Both web clients already send the session-start request.
  Reusing their stored device ID allows Amplitude to count unique visitors across sessions
  in the same browser storage context.
- Emit `zoom_started` at most once per session for each `type`. The values are
  `finger_double_tap`, `finger_pinch`, and `button` (both plus and minus). Programmatic camera
  changes, mouse wheel, keyboard zoom and two-finger tap stay outside this event.
- Emit `routes_selected` after the route picker applies a confirmation from **Show on map**.
  The `city` property is the catalog's stable city ID. The integer `route_count` is the
  size of the committed selection, including zero when the user confirms clearing it. A new
  confirmation is its own event and uses a unique ID for delivery deduplication.
- Emit `recent_routes_used` at most once per session after a tap on a Recent route chip changes the
  route draft. The event has no event properties.
- Emit `route_search_used` at most once per session when Routes receives input containing at least
  one non-whitespace character. The event records usage only, with no query or event properties.
- Emit `journey_planning_started` every time the planner opens. The `source` is `map_button` for
  the launcher or `long_press` when the user chooses an action on a map pin. A pin alone,
  editing endpoints, and executing the route request are separate from an opening. Each
  opening has its own event ID.
- Emit `stop_opened` when a valid stop tap opens its details or switches the sheet to another
  stop. The `city` property is the stable ID of the stop catalog's city. Each opening
  has its own event ID.
- Emit `vehicle_tapped` for each tap on a vehicle marker from the current map frame. Each tap
  has its own event ID. The event has no properties.
- The landing site uses a separate `landing` client value and sends page views, language
  changes and clicks on app links after the visitor opts in. All three events include the page
  language. A language change also includes the previous language. A click also includes the
  destination platform. The site uses a random tab session ID and forwards requests to the same
  BFF route through a same-origin rewrite.
- Use the existing client HTTP stack to send a bounded, typed request to `POST /v1/analytics`.
  The BFF maps the request to Amplitude HTTP V2 and uses a deterministic `insert_id` per
  installation, session, client and type. It holds the project ingestion key and selects the
  configured US or EU data region. The route accepts only the documented event shapes and fields.
  User IP, location, URLs, Telegram payloads, and advertising identifiers stay out of the event.
- Delivery is best effort. The client keeps a bounded queue for the active process; a network
  failure does not affect the app or reset the session's deduplication. An unset
  `BFF_AMPLITUDE_API_KEY` disables upstream delivery.

## Alternatives

Client SDK uploads would require a new client telemetry transport or an exception to the BFF boundary.
Sending each zoom invocation would count repeated input from the same method multiple times in a session.

## Verification

Shared tests cover per-method deduplication and session rollover. BFF tests cover validation, the four
app client values, landing payloads and stable deduplication keys. Browser fixtures exercise the transport
without submitting test activity to the Amplitude project.
