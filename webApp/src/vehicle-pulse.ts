import type { CircleLayerSpecification } from "maplibre-gl";

// Pulse geometry and timing copied from the native adapters (PlatformMap.android.kt, MapLibreMapViewBridge.swift).
export const maxPulseMarkers = 32;
export const pulseTickMillis = Math.ceil(1_000 / 15);
const pulseCycleMillis = 1_600;
const pulseMinRadius = 80 / 2 * 1.15 / 1.5 * 0.85;
const pulseMaxRadius = 80 / 2 * 2 / 1.5 * 0.85;
const pulseMinSizeOpacity = 0.5;
const pulseMaxSizeOpacity = 0.1;

/** Route-coloured halo paint for a circle layer whose features carry a `color` property, at rest. */
export function pulsePaint(): CircleLayerSpecification["paint"] {
  return {
    "circle-color": ["to-color", ["get", "color"]],
    "circle-radius": pulseMinRadius,
    "circle-opacity": pulseMinSizeOpacity,
  };
}

/** Without a time the halo rests at its smallest, most opaque size (Reduce Motion, paused map). */
export function pulseFrame(now?: number) {
  const phase = now == null ? 0 : (now % pulseCycleMillis) / pulseCycleMillis;
  const progress = (1 - Math.cos(phase * Math.PI * 2)) / 2;
  return {
    radius: pulseMinRadius + (pulseMaxRadius - pulseMinRadius) * progress,
    opacity: pulseMinSizeOpacity - (pulseMinSizeOpacity - pulseMaxSizeOpacity) * progress,
  };
}
