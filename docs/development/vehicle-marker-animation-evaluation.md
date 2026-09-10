# Vehicle marker visual decision (DEN-65)

## Final treatment

The comparison experiment is complete and the product now has one vehicle treatment on Android
and iOS:

- a fixed 40dp/pt circular badge filled with the route color;
- a bold white route number, scaled down only when required to stay inside the circle;
- a fixed 1dp/pt white outline at the badge edge;
- a separate fully filled route-colored circle behind the badge;
- the circle grows smoothly from `1.15x` to `2x` the badge diameter and back while its opacity moves
  inversely from `50%` to `10%` and back.

The pulse uses a cosine curve, so both turning points are continuous. The badge, text, vehicle
position, hit target, and selected state never scale or rotate. System Reduce Motion stops the
ticker and leaves the colored circle visible at its minimum `1.15x` size and `50%` opacity.

## Performance guardrails

The platform adapters animate a deterministic maximum of 32 vehicle decorations with one native
ticker capped at 15 fps. Animation frames change only circle-layer paint properties; they do not
replace vehicle or decoration GeoJSON, rebuild badge images, restart polling, or touch camera
state. Badge images remain cached by route label, route color, and freshness style.

No new dependency or paid SDK is introduced. Physical-device FPS/CPU/battery measurements remain
part of release profiling rather than a claim made by this implementation.
