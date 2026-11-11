import type { MapEntry, MapState, RoutesEntry, TransitRuntime, VehicleLayer } from "./contracts";
import type { TransitMap } from "./map";
import { createLocationAdapter, type LocationAdapter } from "./location";
import { connectTelegram } from "./telegram";
import { createTransitView } from "./ui";
import "./styles.css";

interface ActiveMap {
  bridge: MapEntry;
  location: LocationAdapter;
  state?: MapState;
  vehicles?: VehicleLayer;
  renderer?: TransitMap;
  rendererRequested?: boolean;
  rendererUnavailable?: boolean;
}

const root = document.querySelector<HTMLElement>("#app");
if (!root) throw new Error("App container is missing");

let runtime: TransitRuntime | undefined;
let activeMap: ActiveMap | undefined;
let activeRoutes: RoutesEntry | undefined;
let startupRevision = 0;
const language = navigator.language;

const view = createTransitView(root, {
  selectCity: (id) => runtime?.selectCity(id),
  confirmCity: () => runtime?.confirmCity(),
  retryCities: () => runtime?.retryCities(),
  retryStartup: () => { void start(); },
  changeCity: () => runtime?.openCitySelection(),
  selectStop: (id, revision) => activeMap?.bridge.selectStop(id, revision),
  retryNearby: () => activeMap?.bridge.retryNearby(),
  dismissArrivals: () => activeMap?.bridge.dismissArrivals(),
  retryArrivals: () => activeMap?.bridge.retryArrivals(),
  openRoutes: () => runtime?.openRouteSelection(),
  searchRoutes: (query) => activeRoutes?.setSearch(query),
  toggleRoute: (id) => activeRoutes?.toggleRoute(id),
  confirmRoutes: () => activeRoutes?.confirm(),
  cancelRoutes: () => activeRoutes?.cancel(),
  retryRoutes: () => activeRoutes?.retry(),
  focusRoute: (id) => activeMap?.bridge.focusRoute(id),
  retryRoute: (id) => activeMap?.bridge.retryRoute(id),
  removeRoute: (id) => activeMap?.bridge.removeRoute(id),
  myLocation: () => activeMap?.bridge.myLocation(),
}, language);

const telegram = connectTelegram(() => {
  if (activeRoutes) activeRoutes.cancel();
  else activeMap?.bridge.dismissArrivals();
});

function closeEntries() {
  const routes = activeRoutes;
  const map = activeMap;
  activeRoutes = undefined;
  activeMap = undefined;
  routes?.dispose();
  map?.renderer?.dispose();
  map?.location.dispose();
  map?.bridge.dispose();
  telegram.setBackVisible(false);
}

function updateVisibility() {
  const visible = !activeRoutes && document.visibilityState === "visible";
  activeMap?.bridge.setVisible(visible);
  if (!activeMap) return;
  if (!visible) activeMap.location.pause();
  activeMap.location.setRefreshing(visible && activeMap.state?.location?.permission === "granted");
}

async function loadMapRenderer(entry: ActiveMap) {
  entry.rendererRequested = true;
  const setUnavailable = (unavailable: boolean) => {
    entry.rendererUnavailable = unavailable;
    if (activeMap === entry && !activeRoutes) view.setMapUnavailable(unavailable);
  };
  try {
    const { createTransitMap } = await import("./map");
    if (activeMap !== entry) return;
    if (activeRoutes || !entry.state?.camera) {
      entry.rendererRequested = false;
      return;
    }
    entry.renderer = createTransitMap(view.mapElement, entry.state, {
      selectStop: (stop) => entry.bridge.selectStop(stop.id, stop.sourceRevision),
      updateViewport: (lat, lng, radius, zoom) => entry.bridge.updateViewport(lat, lng, radius, zoom),
      selectVehicle: (id, revision) => entry.bridge.selectVehicle(id, revision),
      setUnavailable,
    });
    if (entry.vehicles) entry.renderer.updateVehicles(entry.vehicles);
    setUnavailable(false);
  } catch {
    setUnavailable(true);
  }
}

function renderMap(entry: ActiveMap) {
  view.showMap(entry.state);
  telegram.setBackVisible(Boolean(entry.state?.arrivals));
  if (entry.renderer || entry.rendererUnavailable) view.setMapUnavailable(Boolean(entry.rendererUnavailable));
  if (!entry.state?.camera) return;
  if (entry.renderer) entry.renderer.update(entry.state);
  else if (!entry.rendererRequested) void loadMapRenderer(entry);
}

function retryMap() {
  const entry = activeMap;
  if (!entry || activeRoutes || document.visibilityState !== "visible") return;
  if (entry.renderer) {
    entry.renderer.retry();
  } else if (entry.rendererUnavailable) {
    // A failed chunk import is not retried per state emission; only a network or visibility return retries it.
    entry.rendererRequested = false;
    renderMap(entry);
  }
}

function showMap(session: TransitRuntime) {
  // The runtime pops the route draft and retains the map entry when returning from Routes.
  activeRoutes = undefined;
  if (activeMap) {
    renderMap(activeMap);
    activeMap.renderer?.resize();
  } else {
    const bridge = session.openMap();
    const entry: ActiveMap = { bridge, location: createLocationAdapter(bridge, telegram.locationManager) };
    activeMap = entry;
    renderMap(entry);
    entry.bridge.subscribe((state) => {
      if (activeMap !== entry) return;
      const permissionChanged = state.location?.permission !== entry.state?.location?.permission;
      entry.state = state;
      if (permissionChanged) updateVisibility();
      if (!activeRoutes) renderMap(entry);
    });
    entry.bridge.subscribeLocationCommands((command) => {
      if (activeMap === entry) entry.location.handle(command);
    });
    entry.location.resume();
    entry.bridge.subscribeVehicles((layer) => {
      if (activeMap !== entry) return;
      entry.vehicles = layer;
      entry.renderer?.updateVehicles(layer);
    });
  }
  updateVisibility();
}

function showRoutes(session: TransitRuntime) {
  if (activeRoutes) return;
  const entry = session.openRoutes();
  activeRoutes = entry;
  updateVisibility();
  view.showRoutes();
  telegram.setBackVisible(true);
  entry.subscribe((state) => {
    if (activeRoutes === entry) view.showRoutes(state);
  });
}

async function start() {
  const revision = ++startupRevision;
  closeEntries();
  runtime?.dispose();
  runtime = undefined;
  view.showLoading();
  try {
    const { createTransitRuntime } = await import("./runtime");
    if (revision !== startupRevision) return;
    const session = createTransitRuntime(window.location.origin, language);
    runtime = session;
    session.start((destination) => {
      if (runtime !== session) return;
      if (destination === "map") {
        showMap(session);
      } else if (destination === "routes") {
        showRoutes(session);
      } else {
        closeEntries();
        if (destination === "citySelection") view.showCitySelection();
        else view.showStartupError();
      }
    }, (state) => {
      if (runtime === session) view.showCitySelection(state);
    });
  } catch {
    if (revision === startupRevision) view.showStartupError();
  }
}

document.addEventListener("visibilitychange", () => {
  updateVisibility();
  retryMap();
  // Native adapters re-read the permission on resume; a user may have changed it in settings.
  if (document.visibilityState === "visible") activeMap?.location.resume();
});
window.addEventListener("online", retryMap);
window.addEventListener("pageshow", updateVisibility);
window.addEventListener("pagehide", (event) => {
  if (event.persisted) {
    activeMap?.bridge.setVisible(false);
    return;
  }
  ++startupRevision;
  closeEntries();
  runtime?.dispose();
  telegram.dispose();
});

void start();
