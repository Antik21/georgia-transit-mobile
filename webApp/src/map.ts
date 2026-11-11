import { Map as MapLibreMap, Marker, setWorkerUrl, type GeoJSONSource, type StyleSpecification } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import mapWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url&no-inline";
import type { MapState, StopItem, VehicleLayer } from "./contracts";
import { drawVehicleBadge, vehicleBadgeStyle, type VehicleBadgeStyle } from "./vehicle-badge";

// MapLibre's sibling-file lookup cannot discover an asset renamed by Vite.
setWorkerUrl(new URL(mapWorkerUrl, import.meta.url).href);

const routeSourceId = "transit-route-lines";
const userAccuracySourceId = "transit-user-accuracy";
const userLocationSourceId = "transit-user-location";
const vehicleSourceId = "transit-vehicles";
const vehicleLayerId = "transit-vehicles";
const flippedSuffix = "-flipped";
const overflowBadgeId = "transit-vehicle-badge-overflow";
const maxVehicleMarkers = 2_000;
const maxBadgeImages = 256;
const staleVehicleOpacity = 0.62;
// Badge artwork points east; MapLibre's icon-rotate is clockwise from north.
const eastwardArtworkBearing = 90;

const localStyle: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "background", type: "background", paint: { "background-color": "#dce8e4" } }],
};

interface MapActions {
  selectStop(stop: StopItem): void;
  updateViewport(latitude: number, longitude: number, radiusMeters: number, zoom: number): void;
  selectVehicle(vehicleId: string, revision: string): void;
  setUnavailable(unavailable: boolean): void;
}

export interface TransitMap {
  update(state: MapState): void;
  updateVehicles(layer: VehicleLayer): void;
  retry(): void;
  resize(): void;
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
  return {
    type: "FeatureCollection" as const,
    features: layer.vehicles
      .filter((vehicle) => Number.isFinite(vehicle.latitude) && Number.isFinite(vehicle.longitude))
      .slice(0, maxVehicleMarkers)
      .map((vehicle) => ({
        type: "Feature" as const,
        properties: {
          id: vehicle.id,
          revision: layer.revision,
          image: imageIds.get(vehicleBadgeStyle(vehicle).key) ?? overflowBadgeId,
          rotation: vehicle.bearing == null ? 0 : normalizedBearing(vehicle.bearing - eastwardArtworkBearing),
          opacity: vehicle.stale ? staleVehicleOpacity : 1,
        },
        geometry: { type: "Point" as const, coordinates: [vehicle.longitude, vehicle.latitude] },
      })),
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
  const markers = new Map<string, { marker: Marker; stop: StopItem }>();

  function renderRouteLines() {
    if (!styleReady || renderedRouteRevision === latestState.routePolylineRevision) return;
    const data = routeLineData(latestState.routePolylines);
    const source = map.getSource<GeoJSONSource>(routeSourceId);
    if (source) {
      source.setData(data);
    } else {
      map.addSource(routeSourceId, { type: "geojson", data });
      for (const emphasized of [false, true]) {
        map.addLayer({
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
        }, map.getLayer(vehicleLayerId) ? vehicleLayerId : undefined);
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
      map.addLayer({ id: "transit-user-accuracy-fill", type: "fill", source: userAccuracySourceId,
        paint: { "fill-color": "#1976d2", "fill-opacity": 0.18 } });
      map.addLayer({ id: "transit-user-accuracy-line", type: "line", source: userAccuracySourceId,
        paint: { "line-color": "#0d47a1", "line-opacity": 0.75, "line-width": 2 } });
      map.addLayer({ id: "transit-user-location", type: "circle", source: userLocationSourceId,
        paint: { "circle-color": "#1565c0", "circle-radius": 7, "circle-stroke-color": "#ffffff", "circle-stroke-width": 3 } });
    }
    renderedUserLocation = key;
  }

  function addBadgeImages(id: string, style: VehicleBadgeStyle) {
    const pixelRatio = window.devicePixelRatio || 1;
    for (const flipped of [false, true]) {
      const image = drawVehicleBadge(style, pixelRatio, flipped);
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
    const data = vehicleData(vehicles, badgeImageIds);
    const source = map.getSource<GeoJSONSource>(vehicleSourceId);
    if (source) {
      source.setData(data);
    } else {
      map.addSource(vehicleSourceId, { type: "geojson", data });
      map.addLayer({
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
  }

  map.on("style.load", () => {
    remoteStyleLoading = false;
    styleReady = true;
    renderedRouteRevision = undefined;
    renderedUserLocation = undefined;
    renderedVehicleRevision = undefined;
    renderedBadgeRevision = undefined;
    // A replaced style drops every image registered on the previous one.
    badgeImageIds.clear();
    renderRouteLines();
    renderUserLocation();
    renderVehicles();
  });
  map.on("click", vehicleLayerId, (event) => {
    const properties = event.features?.[0]?.properties;
    if (typeof properties?.id === "string" && properties.revision != null) {
      actions.selectVehicle(properties.id, String(properties.revision));
    }
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
  map.on("moveend", (event) => {
    // The ViewModel already owns the viewport for its programmatic camera commands.
    if (!event.originalEvent) return;
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
      map.jumpTo({ center: [state.camera.longitude, state.camera.latitude], zoom: state.camera.zoom });
    }

    const currentIds = new Set<string>();
    for (const stop of state.stops) {
      if (stop.latitude == null || stop.longitude == null) continue;
      currentIds.add(stop.id);
      let item = markers.get(stop.id);
      if (!item) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "transit-map-marker";
        button.dataset.testid = "map-stop-marker";
        button.dataset.stopId = stop.id;
        const marker = new Marker({ element: button }).setLngLat([stop.longitude, stop.latitude]).addTo(map);
        item = { marker, stop };
        const selectedItem = item;
        button.addEventListener("click", () => actions.selectStop(selectedItem.stop));
        markers.set(stop.id, item);
      }
      item.stop = stop;
      item.marker.setLngLat([stop.longitude, stop.latitude]);
      const button = item.marker.getElement();
      button.setAttribute("aria-label", stop.name);
      button.setAttribute("aria-pressed", String(state.arrivals?.stopId === stop.id));
    }
    for (const [id, item] of markers) {
      if (currentIds.has(id)) continue;
      item.marker.remove();
      markers.delete(id);
    }
  }

  update(initial);
  function updateVehicles(layer: VehicleLayer) {
    vehicles = layer;
    renderVehicles();
  }

  return { update, updateVehicles, retry, resize: () => map.resize(), dispose: () => map.remove() };
}
