import {
  Map as MapLibreMap, setWorkerUrl, type AddLayerObject, type GeoJSONSource, type MapMouseEvent, type StyleSpecification,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import mapWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url&no-inline";
import type { MapState, MapStopLayer, MapStopPin, VehicleLayer } from "./contracts";
import { drawStopCluster, drawStopPin } from "./stop-pin";
import { drawVehicleBadge, systemTextScale, vehicleBadgeStyle, type VehicleBadgeStyle } from "./vehicle-badge";

// MapLibre's sibling-file lookup cannot discover an asset renamed by Vite.
setWorkerUrl(new URL(mapWorkerUrl, import.meta.url).href);

const routeSourceId = "transit-route-lines";
const userAccuracySourceId = "transit-user-accuracy";
const userLocationSourceId = "transit-user-location";
const vehicleSourceId = "transit-vehicles";
const vehicleLayerId = "transit-vehicles";
const attentionLayerId = "transit-vehicle-attention";
const flippedSuffix = "-flipped";
const overflowBadgeId = "transit-vehicle-badge-overflow";
const stopSourceId = "transit-stops";
const selectedStopSourceId = "transit-selected-stop";
const stopLayerId = "transit-stop-pins";
const clusterLayerId = "transit-stop-clusters";
const selectedStopLayerId = "transit-selected-stop-pin";
const clusterImagePrefix = "transit-stop-cluster-";
const maxStopMarkers = 1_000;
const maxVehicleMarkers = 2_000;
const maxBadgeImages = 256;
const staleVehicleOpacity = 0.62;
// Badge artwork points east; MapLibre's icon-rotate is clockwise from north.
const eastwardArtworkBearing = 90;
const minVehicleTarget = 48;
// Pulse geometry and timing copied from the native adapters (PlatformMap.android.kt, MapLibreMapViewBridge.swift).
const maxAttentionMarkers = 32;
const attentionTickMillis = Math.ceil(1_000 / 15);
const attentionCycleMillis = 1_600;
const attentionMinRadius = 80 / 2 * 1.15 / 1.5 * 0.85;
const attentionMaxRadius = 80 / 2 * 2 / 1.5 * 0.85;
const attentionMinSizeOpacity = 0.5;
const attentionMaxSizeOpacity = 0.1;

const localStyle: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "background", type: "background", paint: { "background-color": "#dce8e4" } }],
};

// Bottom to top, as in the native adapters: the selected stop and the user dot stay above vehicles.
const layerOrder = [
  "transit-route-lines-base", "transit-route-lines-emphasized", "transit-user-accuracy-fill", "transit-user-accuracy-line",
  stopLayerId, clusterLayerId, attentionLayerId, vehicleLayerId, selectedStopLayerId, "transit-user-location",
];

interface MapActions {
  tapStop(stopId: string, revision: string): void;
  updateViewport(latitude: number, longitude: number, radiusMeters: number, zoom: number): void;
  selectVehicle(vehicleId: string, revision: string): void;
  setUnavailable(unavailable: boolean): void;
}

export interface TransitMap {
  update(state: MapState): void;
  updateVehicles(layer: VehicleLayer): void;
  retry(): void;
  resize(): void;
  setVisible(visible: boolean): void;
  dispose(): void;
}

function mapAssetRequest(rawUrl: string) {
  const url = new URL(rawUrl, window.location.origin);
  if (url.pathname === "/v1/map/style.json" || /^\/v1\/map\/tiles\/\d+\/\d+\/\d+\.png$/.test(url.pathname)) {
    // The BFF style uses its public hostname. Keep style and tiles on the app's same-origin proxy.
    return { url: new URL(url.pathname, window.location.origin).href };
  }
  throw new Error("Unsupported map asset");
}

function normalizedBearing(degrees: number) {
  return ((degrees % 360) + 360) % 360;
}

function vehicleData(layer: VehicleLayer, imageIds: Map<string, string>) {
  const vehicles = layer.vehicles
    .filter((vehicle) => Number.isFinite(vehicle.latitude) && Number.isFinite(vehicle.longitude))
    .slice(0, maxVehicleMarkers);
  // A deterministic bounded subset, as on mobile, keeps the pulse cost independent of fleet size.
  const attention = new Set(vehicles.filter((vehicle) => vehicle.id && vehicle.routeId).map((vehicle) => vehicle.id)
    .sort().slice(0, maxAttentionMarkers));
  return {
    type: "FeatureCollection" as const,
    attentionCount: attention.size,
    features: vehicles.map((vehicle) => {
      const style = vehicleBadgeStyle(vehicle);
      return {
        type: "Feature" as const,
        properties: {
          id: vehicle.id,
          revision: layer.revision,
          image: imageIds.get(style.key) ?? overflowBadgeId,
          color: style.color,
          attention: attention.has(vehicle.id),
          rotation: vehicle.bearing == null ? 0 : normalizedBearing(vehicle.bearing - eastwardArtworkBearing),
          opacity: vehicle.stale ? staleVehicleOpacity : 1,
        },
        geometry: { type: "Point" as const, coordinates: [vehicle.longitude, vehicle.latitude] },
      };
    }),
  };
}

function userAccuracyData(location: MapState["userLocation"]) {
  const ring = location?.accuracyRing ?? [];
  return {
    type: "FeatureCollection" as const,
    features: ring.length < 4 ? [] : [{
      type: "Feature" as const,
      properties: {},
      geometry: { type: "Polygon" as const, coordinates: [ring.map(({ longitude, latitude }) => [longitude, latitude])] },
    }],
  };
}

function userLocationData(location: MapState["userLocation"]) {
  return {
    type: "FeatureCollection" as const,
    features: !location?.showDot ? [] : [{
      type: "Feature" as const,
      properties: {},
      geometry: { type: "Point" as const, coordinates: [location.longitude, location.latitude] },
    }],
  };
}

function userLocationKey(location: MapState["userLocation"]) {
  return location ? `${location.latitude},${location.longitude},${location.accuracyMeters},${location.showDot}` : "none";
}

function stopFeature(id: string, revision: string, longitude: number, latitude: number, properties: object) {
  return {
    type: "Feature" as const,
    properties: { id, revision, ...properties },
    geometry: { type: "Point" as const, coordinates: [longitude, latitude] },
  };
}

function isMapCoordinate(latitude: number, longitude: number) {
  return Number.isFinite(latitude) && Number.isFinite(longitude) && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
}

const stopOrder = new WeakMap<MapStopLayer, { ordinary: MapStopPin[]; selected: MapStopPin[] }>();

// Draw order, bottom to top. Route-highlighted stops survive the renderer cap first, exactly like the native adapters.
function stopDrawOrder(layer: MapStopLayer) {
  let order = stopOrder.get(layer);
  if (!order) {
    const valid = layer.stops.filter((stop) => stop.id && isMapCoordinate(stop.latitude, stop.longitude));
    const byId = (a: MapStopPin, b: MapStopPin) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
    order = {
      ordinary: valid.filter((stop) => !stop.selected)
        .sort((a, b) => Number(b.highlighted) - Number(a.highlighted) || byId(a, b)).slice(0, maxStopMarkers),
      selected: valid.filter((stop) => stop.selected).sort(byId).slice(0, 1),
    };
    stopOrder.set(layer, order);
  }
  return order;
}

function stopHitOrder(layer: MapStopLayer) {
  const { ordinary, selected } = stopDrawOrder(layer);
  return { ordinary: [...ordinary].reverse(), selected };
}

function stopData(layer: MapStopLayer | null | undefined) {
  const order = layer ? stopDrawOrder(layer) : { ordinary: [], selected: [] };
  const pin = (stop: MapStopPin) => stopFeature(stop.id, layer!.revision, stop.longitude, stop.latitude,
    { kind: "stop", image: stop.pin.imageId, scale: stop.scale, label: stop.label });
  const ordinary = order.ordinary.map(pin);
  const clusters = (layer?.clusters ?? [])
    .filter((cluster) => cluster.id && cluster.count > 1 && isMapCoordinate(cluster.latitude, cluster.longitude))
    .sort((a, b) => (a.id < b.id ? -1 : 1)).slice(0, maxStopMarkers)
    .map((cluster) => stopFeature(cluster.id, layer!.revision, cluster.longitude, cluster.latitude,
      { kind: "cluster", image: clusterImagePrefix + cluster.count, label: cluster.label }));
  const selected = order.selected.map(pin);
  return {
    ordinary: { type: "FeatureCollection" as const, features: [...ordinary, ...clusters] },
    selected: { type: "FeatureCollection" as const, features: selected },
  };
}

function routeLineData(lines: MapState["routePolylines"]) {
  return {
    type: "FeatureCollection" as const,
    features: lines.map(({ points, color, strokeWidth, opacity, isEmphasized }) => ({
      type: "Feature" as const,
      properties: { color, strokeWidth, opacity, isEmphasized },
      geometry: {
        type: "LineString" as const,
        coordinates: points.map(({ longitude, latitude }) => [longitude, latitude]),
      },
    })),
  };
}

export function createTransitMap(container: HTMLElement, initial: MapState, actions: MapActions): TransitMap {
  const { camera, styleUrl } = initial;
  if (!camera) throw new Error("Map camera is missing");
  const map = new MapLibreMap({
    container,
    style: styleUrl ? "/v1/map/style.json" : localStyle,
    center: [camera.longitude, camera.latitude],
    zoom: camera.zoom,
    maxZoom: 20,
    attributionControl: { compact: false },
    dragRotate: false,
    pitchWithRotate: false,
    touchPitch: false,
    renderWorldCopies: false,
    transformRequest: mapAssetRequest,
  });
  // Rotation would also need the camera bearing in the flipped-label rule and a compass to reset it.
  map.touchZoomRotate.disableRotation();
  map.keyboard.disableRotation();
  let cameraRevision = camera.revision;
  let remoteStyleLoading = Boolean(styleUrl);
  let usingFallbackStyle = false;
  let styleReady = false;
  let latestState = initial;
  let renderedRouteRevision: string | undefined;
  let renderedUserLocation: string | undefined;
  let vehicles: VehicleLayer | undefined;
  let renderedVehicleRevision: string | undefined;
  let renderedBadgeRevision: string | undefined;
  const badgeImageIds = new Map<string, string>();
  let nextBadgeImage = 0;
  let textScale = systemTextScale();
  let mapVisible = true;
  let attentionCount = 0;
  let attentionTimer: ReturnType<typeof setTimeout> | undefined;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let renderedStops: MapStopLayer | null | undefined;
  const stopImageIds = new Set<string>();

  function addLayerInOrder(layer: AddLayerObject) {
    const index = layerOrder.indexOf(layer.id);
    map.addLayer(layer, layerOrder.slice(index + 1).find((id) => map.getLayer(id)));
  }

  function renderRouteLines() {
    if (!styleReady || renderedRouteRevision === latestState.routePolylineRevision) return;
    const data = routeLineData(latestState.routePolylines);
    const source = map.getSource<GeoJSONSource>(routeSourceId);
    if (source) {
      source.setData(data);
    } else {
      map.addSource(routeSourceId, { type: "geojson", data });
      for (const emphasized of [false, true]) {
        addLayerInOrder({
          id: emphasized ? "transit-route-lines-emphasized" : "transit-route-lines-base",
          type: "line",
          source: routeSourceId,
          filter: ["==", ["get", "isEmphasized"], emphasized],
          layout: { "line-cap": "round", "line-join": "round" },
          paint: {
            "line-color": ["to-color", ["get", "color"]],
            "line-width": ["number", ["get", "strokeWidth"]],
            "line-opacity": ["number", ["get", "opacity"]],
          },
        });
      }
    }
    renderedRouteRevision = latestState.routePolylineRevision;
  }

  function renderUserLocation() {
    const key = userLocationKey(latestState.userLocation);
    if (!styleReady || renderedUserLocation === key) return;
    const accuracy = userAccuracyData(latestState.userLocation);
    const dot = userLocationData(latestState.userLocation);
    const accuracySource = map.getSource<GeoJSONSource>(userAccuracySourceId);
    if (accuracySource) {
      accuracySource.setData(accuracy);
      map.getSource<GeoJSONSource>(userLocationSourceId)?.setData(dot);
    } else {
      // Same palette as the native adapters' accuracy area and location dot.
      map.addSource(userAccuracySourceId, { type: "geojson", data: accuracy });
      map.addSource(userLocationSourceId, { type: "geojson", data: dot });
      addLayerInOrder({ id: "transit-user-accuracy-fill", type: "fill", source: userAccuracySourceId,
        paint: { "fill-color": "#1976d2", "fill-opacity": 0.18 } });
      addLayerInOrder({ id: "transit-user-accuracy-line", type: "line", source: userAccuracySourceId,
        paint: { "line-color": "#0d47a1", "line-opacity": 0.75, "line-width": 2 } });
      addLayerInOrder({ id: "transit-user-location", type: "circle", source: userLocationSourceId,
        paint: { "circle-color": "#1565c0", "circle-radius": 7, "circle-stroke-color": "#ffffff", "circle-stroke-width": 3 } });
    }
    renderedUserLocation = key;
  }

  function syncStopImages(layer: MapStopLayer | null | undefined) {
    const pixelRatio = window.devicePixelRatio || 1;
    const wanted = new Map<string, () => ImageData>();
    for (const stop of layer?.stops ?? []) {
      if (!wanted.has(stop.pin.imageId)) wanted.set(stop.pin.imageId, () => drawStopPin(stop.pin, pixelRatio));
    }
    for (const cluster of layer?.clusters ?? []) {
      const id = clusterImagePrefix + cluster.count;
      if (cluster.count > 1 && !wanted.has(id)) wanted.set(id, () => drawStopCluster(cluster.count, pixelRatio));
    }
    for (const id of stopImageIds) {
      if (wanted.has(id)) continue;
      if (map.hasImage(id)) map.removeImage(id);
      stopImageIds.delete(id);
    }
    for (const [id, draw] of wanted) {
      if (stopImageIds.has(id)) continue;
      map.addImage(id, draw(), { pixelRatio });
      stopImageIds.add(id);
    }
  }

  function pinLayer(id: string, source: string, kind: string): AddLayerObject {
    return {
      id,
      type: "symbol",
      source,
      filter: ["==", ["get", "kind"], kind],
      layout: {
        "icon-image": ["get", "image"],
        "icon-size": kind === "stop" ? ["number", ["get", "scale"]] : 1,
        "icon-anchor": kind === "stop" ? "bottom" : "center",
        "icon-padding": 0,
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
        // stopAt() walks the source order; the default viewport-Y order would put a different pin on top.
        "symbol-z-order": "source",
      },
    };
  }

  // The layer object only changes identity when the bridge re-projects stop content.
  function renderStops() {
    const layer = latestState.stopLayer;
    if (!styleReady || renderedStops === layer) return;
    syncStopImages(layer);
    const { ordinary, selected } = stopData(layer);
    const source = map.getSource<GeoJSONSource>(stopSourceId);
    if (source) {
      source.setData(ordinary);
      map.getSource<GeoJSONSource>(selectedStopSourceId)?.setData(selected);
    } else {
      map.addSource(stopSourceId, { type: "geojson", data: ordinary });
      map.addSource(selectedStopSourceId, { type: "geojson", data: selected });
      addLayerInOrder(pinLayer(stopLayerId, stopSourceId, "stop"));
      addLayerInOrder(pinLayer(clusterLayerId, stopSourceId, "cluster"));
      addLayerInOrder(pinLayer(selectedStopLayerId, selectedStopSourceId, "stop"));
    }
    renderedStops = layer;
  }

  function addBadgeImages(id: string, style: VehicleBadgeStyle) {
    const pixelRatio = window.devicePixelRatio || 1;
    for (const flipped of [false, true]) {
      const image = drawVehicleBadge(style, pixelRatio, textScale, flipped);
      // Badges are drawn at the native adapters' size and shown at the same 1/1.5 scale.
      map.addImage(flipped ? id + flippedSuffix : id, image, { pixelRatio: pixelRatio * 1.5 });
    }
  }

  function syncBadgeImages(layer: VehicleLayer) {
    const styles = new Map<string, VehicleBadgeStyle>();
    for (const vehicle of layer.vehicles) {
      const style = vehicleBadgeStyle(vehicle);
      if (!styles.has(style.key) && styles.size < maxBadgeImages) styles.set(style.key, style);
    }
    for (const [key, id] of badgeImageIds) {
      if (styles.has(key)) continue;
      map.removeImage(id);
      map.removeImage(id + flippedSuffix);
      badgeImageIds.delete(key);
    }
    for (const [key, style] of styles) {
      if (badgeImageIds.has(key)) continue;
      const id = `transit-vehicle-badge-${++nextBadgeImage}`;
      addBadgeImages(id, style);
      badgeImageIds.set(key, id);
    }
    if (!map.hasImage(overflowBadgeId)) {
      addBadgeImages(overflowBadgeId, vehicleBadgeStyle({ label: "?", color: "#455a64", textColor: "#ffffff", stale: false }));
    }
  }

  function renderVehicles() {
    if (!styleReady || !vehicles || renderedVehicleRevision === vehicles.revision) return;
    if (renderedBadgeRevision !== vehicles.badgeRevision) {
      syncBadgeImages(vehicles);
      renderedBadgeRevision = vehicles.badgeRevision;
    }
    const { attentionCount: nextAttentionCount, ...data } = vehicleData(vehicles, badgeImageIds);
    const source = map.getSource<GeoJSONSource>(vehicleSourceId);
    if (source) {
      source.setData(data);
    } else {
      map.addSource(vehicleSourceId, { type: "geojson", data });
      // The route-coloured pulse stays below the badge and is never hit-tested.
      addLayerInOrder({
        id: attentionLayerId,
        type: "circle",
        source: vehicleSourceId,
        filter: ["==", ["get", "attention"], true],
        paint: {
          "circle-color": ["to-color", ["get", "color"]],
          "circle-radius": attentionMinRadius,
          "circle-opacity": attentionMinSizeOpacity,
        },
      });
      addLayerInOrder({
        id: vehicleLayerId,
        type: "symbol",
        source: vehicleSourceId,
        layout: {
          // Rotation is disabled, so a badge rotated past vertical would otherwise show its label upside down.
          "icon-image": ["case",
            ["all", [">", ["get", "rotation"], 90], ["<", ["get", "rotation"], 270]],
            ["concat", ["get", "image"], flippedSuffix],
            ["get", "image"]],
          "icon-rotate": ["get", "rotation"],
          "icon-rotation-alignment": "map",
          "icon-allow-overlap": true,
          "icon-ignore-placement": true,
        },
        paint: { "icon-opacity": ["get", "opacity"] },
      });
    }
    renderedVehicleRevision = vehicles.revision;
    if (attentionCount !== nextAttentionCount) {
      attentionCount = nextAttentionCount;
      refreshAttention();
    }
  }

  function shouldAnimateAttention() {
    return styleReady && mapVisible && attentionCount > 0 && !reducedMotion.matches;
  }

  // Without a progress value the ring stays static at its smallest, most opaque size (Reduce Motion, paused map).
  function renderAttentionFrame(now?: number) {
    if (!styleReady || !map.getLayer(attentionLayerId)) return;
    const phase = now == null ? 0 : (now % attentionCycleMillis) / attentionCycleMillis;
    const progress = (1 - Math.cos(phase * Math.PI * 2)) / 2;
    map.setPaintProperty(attentionLayerId, "circle-radius",
      attentionMinRadius + (attentionMaxRadius - attentionMinRadius) * progress);
    map.setPaintProperty(attentionLayerId, "circle-opacity",
      attentionMinSizeOpacity - (attentionMinSizeOpacity - attentionMaxSizeOpacity) * progress);
  }

  function attentionTick() {
    attentionTimer = undefined;
    refreshAttention();
  }

  function refreshAttention() {
    if (!shouldAnimateAttention()) {
      clearTimeout(attentionTimer);
      attentionTimer = undefined;
      renderAttentionFrame();
      return;
    }
    renderAttentionFrame(performance.now());
    attentionTimer ??= setTimeout(attentionTick, attentionTickMillis);
  }
  reducedMotion.addEventListener("change", refreshAttention);

  map.on("style.load", () => {
    remoteStyleLoading = false;
    styleReady = true;
    renderedRouteRevision = undefined;
    renderedUserLocation = undefined;
    renderedVehicleRevision = undefined;
    renderedBadgeRevision = undefined;
    renderedStops = undefined;
    // A replaced style drops every image registered on the previous one.
    badgeImageIds.clear();
    stopImageIds.clear();
    renderRouteLines();
    renderUserLocation();
    renderStops();
    attentionCount = 0;
    renderVehicles();
  });

  // Like the native adapters only the drawn pin is a target and the selected one wins; clusters are not tappable.
  // Hit-testing the current layer avoids MapLibre's collision index, which lags source updates by a fade.
  function stopAt(event: MapMouseEvent) {
    const layer = renderedStops;
    if (!layer) return undefined;
    const { x, y } = event.point;
    const { ordinary, selected } = stopHitOrder(layer);
    for (const stop of [...selected, ...ordinary]) {
      const anchor = map.project([stop.longitude, stop.latitude]);
      const halfWidth = stop.pin.width * stop.scale / 2;
      if (Math.abs(x - anchor.x) <= halfWidth && y <= anchor.y && y >= anchor.y - stop.pin.height * stop.scale) {
        return { id: stop.id, revision: layer.revision };
      }
    }
    return undefined;
  }

  // Badges are about 39×24 px, so taps are matched within a 48×48 target like the Android adapter.
  function vehicleAt(event: MapMouseEvent) {
    if (!map.getLayer(vehicleLayerId)) return undefined;
    const { x, y } = event.point;
    const half = minVehicleTarget / 2;
    let nearest: { id: string; revision: string; distance: number } | undefined;
    for (const feature of map.queryRenderedFeatures([[x - half, y - half], [x + half, y + half]], { layers: [vehicleLayerId] })) {
      const { id, revision } = feature.properties;
      if (typeof id !== "string" || revision == null || feature.geometry.type !== "Point") continue;
      const [longitude, latitude] = feature.geometry.coordinates;
      const point = map.project([longitude, latitude]);
      const distance = Math.hypot(point.x - x, point.y - y);
      if (!nearest || distance < nearest.distance) nearest = { id, revision: String(revision), distance };
    }
    return nearest;
  }
  map.on("click", (event) => {
    const stop = stopAt(event);
    if (stop) {
      actions.tapStop(stop.id, stop.revision);
      return;
    }
    const vehicle = vehicleAt(event);
    if (vehicle) actions.selectVehicle(vehicle.id, vehicle.revision);
  });
  map.on("mousemove", (event) => {
    map.getCanvas().style.cursor = stopAt(event) || vehicleAt(event) ? "pointer" : "";
  });
  map.on("error", (event) => {
    // Any error listener replaces MapLibre's default logging, so tile failures must be logged here.
    if (!remoteStyleLoading) {
      console.error(event.error);
      return;
    }
    remoteStyleLoading = false;
    usingFallbackStyle = true;
    styleReady = false;
    map.setStyle(localStyle, { diff: false });
  });

  function retry() {
    if (!usingFallbackStyle || !styleUrl) return;
    usingFallbackStyle = false;
    remoteStyleLoading = true;
    styleReady = false;
    map.setStyle("/v1/map/style.json", { diff: false });
  }
  map.on("webglcontextlost", () => {
    styleReady = false;
    actions.setUnavailable(true);
  });
  map.on("webglcontextrestored", () => actions.setUnavailable(false));
  // Programmatic moves are reported too, as on mobile: a group tap zooms in and the ViewModel must see the new zoom.
  map.on("moveend", () => {
    const center = map.getCenter();
    const bounds = map.getBounds();
    const radius = Math.ceil(Math.max(
      center.distanceTo(bounds.getNorthEast()),
      center.distanceTo(bounds.getSouthWest()),
    ));
    actions.updateViewport(center.lat, center.wrap().lng, radius, map.getZoom());
  });

  function update(state: MapState) {
    latestState = state;
    renderRouteLines();
    renderUserLocation();
    if (state.camera && state.camera.revision !== cameraRevision) {
      cameraRevision = state.camera.revision;
      const target = { center: [state.camera.longitude, state.camera.latitude] as [number, number], zoom: state.camera.zoom };
      if (state.camera.animated && !reducedMotion.matches) map.easeTo(target);
      else map.jumpTo(target);
    }
    renderStops();
  }

  update(initial);
  // Badges are rasterised for one devicePixelRatio; zooming or moving to another screen blurs them.
  let pixelRatioQuery: MediaQueryList | undefined;
  function watchPixelRatio() {
    pixelRatioQuery?.removeEventListener("change", onPixelRatioChange);
    pixelRatioQuery = window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`);
    pixelRatioQuery.addEventListener("change", onPixelRatioChange);
  }
  function onPixelRatioChange() {
    watchPixelRatio();
    rebuildBadges();
    if (!styleReady) return;
    for (const id of stopImageIds) if (map.hasImage(id)) map.removeImage(id);
    stopImageIds.clear();
    renderedStops = undefined;
    renderStops();
  }
  // There is no event for a changed font size setting, so it is re-read whenever the user may be back from it.
  function onTextScaleCheck() {
    const scale = systemTextScale();
    if (scale === textScale) return;
    textScale = scale;
    rebuildBadges();
  }
  function rebuildBadges() {
    if (!styleReady) return;
    for (const id of [...badgeImageIds.values(), overflowBadgeId]) {
      for (const imageId of [id, id + flippedSuffix]) if (map.hasImage(imageId)) map.removeImage(imageId);
    }
    badgeImageIds.clear();
    renderedBadgeRevision = undefined;
    renderedVehicleRevision = undefined;
    renderVehicles();
  }
  watchPixelRatio();

  function updateVehicles(layer: VehicleLayer) {
    vehicles = layer;
    renderVehicles();
  }

  function setVisible(visible: boolean) {
    if (visible === mapVisible) return;
    mapVisible = visible;
    if (visible) onTextScaleCheck();
    refreshAttention();
  }

  function resize() {
    map.resize();
    onTextScaleCheck();
  }

  function dispose() {
    pixelRatioQuery?.removeEventListener("change", onPixelRatioChange);
    reducedMotion.removeEventListener("change", refreshAttention);
    clearTimeout(attentionTimer);
    attentionTimer = undefined;
    map.remove();
  }

  return { update, updateVehicles, retry, resize, setVisible, dispose };
}
