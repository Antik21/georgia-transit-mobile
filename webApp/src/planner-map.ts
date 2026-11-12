import {
  LngLatBounds, Popup, type AddLayerObject, type GeoJSONSource, type GeoJSONSourceSpecification, type Map as MapLibreMap,
} from "maplibre-gl";
import type { PlannerBus, PlannerRoute, PlannerState } from "./contracts";
import { drawVehicleBadge, systemTextScale, vehicleBadgeStyle } from "./vehicle-badge";

const lineSourceId = "transit-planner-lines";
const pointSourceId = "transit-planner-points";
const busSourceId = "transit-planner-buses";
export const plannerBusLayerIds = ["transit-planner-bus-halo", "transit-planner-buses"];
const busImagePrefix = "transit-planner-bus-";
// Same artwork rules as the vehicle layer in map.ts: badges point east and flip past vertical.
const eastwardArtworkBearing = 90;
const busHaloColor = "#ffc300";
export const plannerLineLayerIds = ["transit-planner-walk", "transit-planner-transit-casing", "transit-planner-transit"];
export const plannerPointLayerId = "transit-planner-points";
const longPressMillis = 500;
const longPressSlopPixels = 10;
// Android WebViews also fire contextmenu for the same hold; one gesture must not open two pins.
const duplicateGestureMillis = 400;
const walkColor = "#6b7280";
const lineLayout = { "line-cap": "round", "line-join": "round" } as const;
const lineLayers: AddLayerObject[] = [
  {
    id: plannerLineLayerIds[0], type: "line", source: lineSourceId, filter: ["==", ["get", "kind"], "walk"], layout: lineLayout,
    paint: { "line-color": walkColor, "line-width": 4, "line-dasharray": [0.6, 1.6] },
  },
  {
    id: plannerLineLayerIds[1], type: "line", source: lineSourceId, filter: ["==", ["get", "kind"], "transit"], layout: lineLayout,
    paint: { "line-color": "#ffffff", "line-width": 10 },
  },
  {
    id: plannerLineLayerIds[2], type: "line", source: lineSourceId, filter: ["==", ["get", "kind"], "transit"], layout: lineLayout,
    paint: { "line-color": ["to-color", ["get", "color"]], "line-width": 6 },
  },
];
const busLayers: AddLayerObject[] = [
  {
    id: plannerBusLayerIds[0], type: "circle", source: busSourceId,
    paint: { "circle-radius": 28, "circle-opacity": 0, "circle-stroke-color": busHaloColor, "circle-stroke-width": 3 },
  },
  {
    id: plannerBusLayerIds[1], type: "symbol", source: busSourceId,
    layout: {
      "icon-image": ["case", ["all", [">", ["get", "rotation"], 90], ["<", ["get", "rotation"], 270]],
        ["concat", ["get", "image"], "-flipped"], ["get", "image"]],
      "icon-rotate": ["get", "rotation"],
      "icon-rotation-alignment": "map",
      "icon-allow-overlap": true,
      "icon-ignore-placement": true,
    },
  },
];
const pointLayer: AddLayerObject = {
  id: plannerPointLayerId, type: "circle", source: pointSourceId,
  paint: {
    "circle-color": ["match", ["get", "kind"], "origin", "#2e7d32", "destination", "#8e1b2d", "#263238"],
    "circle-radius": ["match", ["get", "kind"], "pin", 6, 8],
    "circle-stroke-color": "#ffffff",
    "circle-stroke-width": 3,
  },
};

export interface PlannerMapActions {
  longPress(latitude: number, longitude: number): void;
  routeHere(): void;
  routeFromHere(): void;
  dismissPin(): void;
}

export interface PlannerMapLabels {
  routeHere: string;
  routeFromHere: string;
}

function lineData(route: PlannerRoute | undefined) {
  return {
    type: "FeatureCollection" as const,
    features: (route?.lines ?? []).filter((line) => line.points.length >= 2).map((line) => ({
      type: "Feature" as const,
      properties: { kind: line.kind, color: line.kind === "walk" ? walkColor : line.color },
      geometry: { type: "LineString" as const, coordinates: line.points.map(({ longitude, latitude }) => [longitude, latitude]) },
    })),
  };
}

function pointData(state: PlannerState | undefined) {
  const points = [
    ["origin", state?.route.origin],
    ["destination", state?.route.destination],
    ["pin", state?.pin],
  ] as const;
  return {
    type: "FeatureCollection" as const,
    features: points.filter(([, point]) => point).map(([kind, point]) => ({
      type: "Feature" as const,
      properties: { kind },
      geometry: { type: "Point" as const, coordinates: [point!.longitude, point!.latitude] },
    })),
  };
}

/** Planned lines, A/B markers, the long-press pin with its two actions, and the long-press gesture itself. */
export function createPlannerMap(
  map: MapLibreMap,
  addLayerInOrder: (layer: AddLayerObject) => void,
  actions: PlannerMapActions,
  labels: PlannerMapLabels,
  reducedMotion: MediaQueryList,
) {
  let state: PlannerState | undefined;
  let styleReady = false;
  let renderedRoute: PlannerRoute | undefined;
  let renderedPoints: string | undefined;
  let fittedRevision: string | undefined;
  let renderedBuses: string | undefined;
  const busImages = new Map<string, string>();
  let retryPending = false;
  let popup: Popup | undefined;
  let popupKey: string | undefined;
  let lastLongPress = -Infinity;
  let swallowClick = false;
  let pressTimer: ReturnType<typeof setTimeout> | undefined;
  let pressStart: { x: number; y: number } | undefined;

  function fire(latitude: number, longitude: number) {
    const now = performance.now();
    if (now - lastLongPress < duplicateGestureMillis) return;
    lastLongPress = now;
    swallowClick = true;
    actions.longPress(latitude, longitude);
  }

  function cancelPress() {
    clearTimeout(pressTimer);
    pressTimer = undefined;
    pressStart = undefined;
  }

  const canvas = map.getCanvasContainer();
  const onPointerDown = () => { swallowClick = false; };
  canvas.addEventListener("pointerdown", onPointerDown);
  const onTouchStart = (event: TouchEvent) => {
    cancelPress();
    if (event.touches.length !== 1) return;
    const touch = event.touches[0];
    pressStart = { x: touch.clientX, y: touch.clientY };
    pressTimer = setTimeout(() => {
      const start = pressStart;
      cancelPress();
      if (!start) return;
      const bounds = map.getCanvas().getBoundingClientRect();
      const point = map.unproject([start.x - bounds.left, start.y - bounds.top]);
      fire(point.lat, point.wrap().lng);
    }, longPressMillis);
  };
  const onTouchMove = (event: TouchEvent) => {
    const touch = event.touches[0];
    if (!pressStart || !touch || Math.hypot(touch.clientX - pressStart.x, touch.clientY - pressStart.y) > longPressSlopPixels) {
      cancelPress();
    }
  };
  canvas.addEventListener("touchstart", onTouchStart, { passive: true });
  canvas.addEventListener("touchmove", onTouchMove, { passive: true });
  canvas.addEventListener("touchend", cancelPress);
  canvas.addEventListener("touchcancel", cancelPress);
  map.on("contextmenu", (event) => {
    event.preventDefault();
    fire(event.lngLat.lat, event.lngLat.wrap().lng);
  });

  function ensureLayers(layers: AddLayerObject[]) {
    for (const layer of layers) if (!map.getLayer(layer.id)) addLayerInOrder(layer);
  }

  function syncSource(id: string, data: GeoJSONSourceSpecification["data"], layers: AddLayerObject[]) {
    const source = map.getSource<GeoJSONSource>(id);
    if (source) source.setData(data);
    else map.addSource(id, { type: "geojson", data });
    ensureLayers(layers);
  }

  // Nothing counts as rendered until MapLibre accepted it, so a failed attempt is never mistaken for a drawn route.
  function renderLayers() {
    if (!styleReady) return;
    try {
      const route = state?.route;
      if (renderedRoute !== route) {
        syncSource(lineSourceId, lineData(route), lineLayers);
        renderedRoute = route;
      }
      const points = pointData(state);
      const pointsKey = JSON.stringify(points.features);
      if (renderedPoints !== pointsKey) {
        syncSource(pointSourceId, points, [pointLayer]);
        renderedPoints = pointsKey;
      }
      const buses = busData(state?.buses ?? []);
      const busesKey = JSON.stringify(buses.features);
      if (renderedBuses !== busesKey) {
        syncSource(busSourceId, buses, busLayers);
        renderedBuses = busesKey;
      }
      // Only a new plan or another chosen option re-frames; refreshes leave the user's camera alone.
      if (route?.lines.length && state && fittedRevision !== state.cameraFitRevision) {
        fittedRevision = state.cameraFitRevision;
        fitRoute(route);
      }
    } catch {
      // MapLibre throws while a style it is (re)loading is not done; draw again once the style settles.
      retryWhenStyleSettles();
    }
  }

  function busData(buses: readonly PlannerBus[]) {
    return {
      type: "FeatureCollection" as const,
      features: buses.filter((bus) => Number.isFinite(bus.latitude) && Number.isFinite(bus.longitude)).map((bus) => ({
        type: "Feature" as const,
        properties: {
          id: bus.id,
          image: busImage(bus),
          rotation: bus.bearing == null ? 0 : (((bus.bearing - eastwardArtworkBearing) % 360) + 360) % 360,
        },
        geometry: { type: "Point" as const, coordinates: [bus.longitude, bus.latitude] },
      })),
    };
  }

  function busImage(bus: PlannerBus) {
    const style = vehicleBadgeStyle({ label: bus.label, color: bus.color, textColor: bus.textColor, stale: false });
    let id = busImages.get(style.key);
    if (!id) {
      id = `${busImagePrefix}${busImages.size}`;
      const pixelRatio = window.devicePixelRatio || 1;
      for (const flipped of [false, true]) {
        map.addImage(flipped ? `${id}-flipped` : id, drawVehicleBadge(style, pixelRatio, systemTextScale(), flipped),
          { pixelRatio: pixelRatio * 1.5 });
      }
      busImages.set(style.key, id);
    }
    return id;
  }

  function retryWhenStyleSettles() {
    if (retryPending) return;
    retryPending = true;
    const retry = () => {
      map.off("styledata", retry);
      map.off("idle", retry);
      retryPending = false;
      renderLayers();
    };
    map.on("styledata", retry);
    map.on("idle", retry);
  }

  function fitRoute(route: PlannerRoute) {
    const bounds = new LngLatBounds();
    for (const line of route.lines) for (const point of line.points) bounds.extend([point.longitude, point.latitude]);
    map.fitBounds(bounds, { padding: 48, maxZoom: 17, duration: reducedMotion.matches ? 0 : 400 });
  }

  function renderPopup() {
    const pin = state?.pin;
    const key = pin ? `${pin.latitude},${pin.longitude}` : undefined;
    if (key === popupKey) return;
    popupKey = key;
    const previous = popup;
    popup = undefined;
    previous?.remove();
    if (!pin) return;
    const content = document.createElement("div");
    content.className = "planner-pin-actions";
    for (const [label, action, testId] of [
      [labels.routeHere, actions.routeHere, "planner-route-here"],
      [labels.routeFromHere, actions.routeFromHere, "planner-route-from-here"],
    ] as const) {
      const choice = document.createElement("button");
      choice.type = "button";
      choice.className = "planner-pin-button";
      choice.textContent = label;
      choice.dataset.testid = testId;
      choice.addEventListener("click", action);
      content.append(choice);
    }
    const next = new Popup({ closeButton: true, closeOnClick: false, anchor: "bottom", offset: 12, maxWidth: "260px" })
      .setLngLat([pin.longitude, pin.latitude])
      .setDOMContent(content);
    next.on("close", () => {
      if (popup === next) actions.dismissPin();
    });
    popup = next;
    next.addTo(map);
  }

  return {
    update(next: PlannerState) {
      state = next;
      renderLayers();
      renderPopup();
    },
    styleLoaded() {
      styleReady = true;
      renderedRoute = undefined;
      renderedPoints = undefined;
      renderedBuses = undefined;
      // A replaced style drops every image registered on the previous one.
      busImages.clear();
      renderLayers();
    },
    styleLost() {
      styleReady = false;
    },
    /** True when the click ends a long press; any other map click dismisses an open pin. */
    consumeClick() {
      if (swallowClick) {
        swallowClick = false;
        return true;
      }
      if (state?.pin) actions.dismissPin();
      return false;
    },
    dispose() {
      cancelPress();
      canvas.removeEventListener("pointerdown", onPointerDown);
      canvas.removeEventListener("touchstart", onTouchStart);
      canvas.removeEventListener("touchmove", onTouchMove);
      canvas.removeEventListener("touchend", cancelPress);
      canvas.removeEventListener("touchcancel", cancelPress);
      popup?.remove();
      popup = undefined;
    },
  };
}
