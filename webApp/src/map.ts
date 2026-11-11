import { Map as MapLibreMap, Marker, setWorkerUrl, type GeoJSONSource, type StyleSpecification } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import mapWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url&no-inline";
import type { MapState, StopItem } from "./contracts";

// MapLibre's sibling-file lookup cannot discover an asset renamed by Vite.
setWorkerUrl(new URL(mapWorkerUrl, import.meta.url).href);

const routeSourceId = "transit-route-lines";

const localStyle: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "background", type: "background", paint: { "background-color": "#dce8e4" } }],
};

interface MapActions {
  selectStop(stop: StopItem): void;
  updateViewport(latitude: number, longitude: number, radiusMeters: number, zoom: number): void;
  setUnavailable(unavailable: boolean): void;
}

export interface TransitMap {
  update(state: MapState): void;
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
  let cameraRevision = camera.revision;
  let remoteStyleLoading = Boolean(styleUrl);
  let usingFallbackStyle = false;
  let styleReady = false;
  let latestState = initial;
  let renderedRouteRevision: string | undefined;
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
        });
      }
    }
    renderedRouteRevision = latestState.routePolylineRevision;
  }

  map.on("style.load", () => {
    remoteStyleLoading = false;
    styleReady = true;
    renderedRouteRevision = undefined;
    renderRouteLines();
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
  return { update, retry, resize: () => map.resize(), dispose: () => map.remove() };
}
