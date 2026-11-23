import {
  LngLatBounds, type AddLayerObject, type GeoJSONSource, type GeoJSONSourceSpecification, type Map as MapLibreMap,
} from "maplibre-gl";
import type { PlannerRoute, PlannerState } from "./contracts";
import type { getCopy } from "./copy";
import { renderPlannerPin } from "./planner-map-ui";

const lineSourceId = "transit-planner-lines";
const pointSourceId = "transit-planner-points";
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
const pointLayer: AddLayerObject = {
  id: plannerPointLayerId, type: "symbol", source: pointSourceId,
  layout: { "icon-image": ["get", "image"], "icon-allow-overlap": true, "icon-ignore-placement": true },
};

const endpoints = {
  origin: { image: "transit-planner-origin", label: "A", color: "#2e7d32" },
  destination: { image: "transit-planner-destination", label: "B", color: "#8e1b2d" },
};
const pointImagePixelRatio = 2;

function endpointImage(label: string, color: string) {
  // Match the native 28 dp marker and 3 dp white outline.
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 28 * pointImagePixelRatio;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D is unavailable");
  context.scale(pointImagePixelRatio, pointImagePixelRatio);
  for (const [radius, fill] of [[14, "#ffffff"], [11, color]] as const) {
    context.beginPath();
    context.arc(14, 14, radius, 0, Math.PI * 2);
    context.fillStyle = fill;
    context.fill();
  }
  context.font = "bold 14px system-ui, sans-serif";
  context.textAlign = "center";
  context.textBaseline = "middle";
  context.fillStyle = "#ffffff";
  context.fillText(label, 14, 14);
  return context.getImageData(0, 0, canvas.width, canvas.height);
}

export interface PlannerMapActions {
  longPress(latitude: number, longitude: number): void;
  routeHere(): void;
  routeFromHere(): void;
  dismissPin(): void;
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
  ] as const;
  return {
    type: "FeatureCollection" as const,
    features: points.filter(([, point]) => point).map(([kind, point]) => ({
      type: "Feature" as const,
      properties: { kind, image: endpoints[kind].image },
      geometry: { type: "Point" as const, coordinates: [point!.longitude, point!.latitude] },
    })),
  };
}

/** Planned lines, A/B markers, the long-press pin with its two actions, and the long-press gesture itself. */
export function createPlannerMap(
  map: MapLibreMap,
  addLayerInOrder: (layer: AddLayerObject) => void,
  actions: PlannerMapActions,
  copy: ReturnType<typeof getCopy>,
  reducedMotion: MediaQueryList,
) {
  let state: PlannerState | undefined;
  let styleReady = false;
  let renderedRoute: PlannerRoute | undefined;
  let renderedPoints: string | undefined;
  let fittedRevision: string | undefined;
  let retryPending = false;
  let popup: HTMLElement | undefined;
  let popupKey: string | undefined;
  let lastLongPress = -Infinity;
  let swallowClick = false;
  let pressTimer: ReturnType<typeof setTimeout> | undefined;
  let pressStart: { x: number; y: number } | undefined;

  function fire(latitude: number, longitude: number) {
    if (state?.mapPick || map.isMoving()) return;
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

  function onMoveStart() {
    if (!popup) return;
    popup.remove();
    popup = undefined;
    actions.dismissPin();
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
  map.on("movestart", onMoveStart);
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
        for (const { image, label, color } of Object.values(endpoints)) {
          if (!map.hasImage(image)) map.addImage(image, endpointImage(label, color), { pixelRatio: pointImagePixelRatio });
        }
        syncSource(pointSourceId, points, [pointLayer]);
        renderedPoints = pointsKey;
      }
      // Only a new plan or another chosen option re-frames; refreshes leave the user's camera alone.
      if (route?.lines.length && state && !state.mapPick && fittedRevision !== state.cameraFitRevision) {
        fittedRevision = state.cameraFitRevision;
        fitRoute(route);
      }
    } catch {
      // MapLibre throws while a style it is (re)loading is not done; draw again once the style settles.
      retryWhenStyleSettles();
    }
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
    const onlySide = state?.open && state.origin.kind !== state.destination.kind
      ? state.origin.kind === "none" ? "origin" : state.destination.kind === "none" ? "destination" : undefined
      : undefined;
    const key = pin ? `${pin.latitude},${pin.longitude},${onlySide}` : undefined;
    if (key === popupKey) return;
    popupKey = key;
    popup?.remove();
    popup = undefined;
    if (!pin) return;
    popup = renderPlannerPin(map.getContainer(), map.project([pin.longitude, pin.latitude]), onlySide, copy, actions);
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
      if (state?.pin) {
        actions.dismissPin();
        return true;
      }
      return Boolean(state?.mapPick);
    },
    dispose() {
      cancelPress();
      canvas.removeEventListener("pointerdown", onPointerDown);
      canvas.removeEventListener("touchstart", onTouchStart);
      canvas.removeEventListener("touchmove", onTouchMove);
      canvas.removeEventListener("touchend", cancelPress);
      canvas.removeEventListener("touchcancel", cancelPress);
      map.off("movestart", onMoveStart);
      popup?.remove();
      popup = undefined;
    },
  };
}
