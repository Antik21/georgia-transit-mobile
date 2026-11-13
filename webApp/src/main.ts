import type { MapEntry, MapState, PlannerState, RoutesEntry, TransitRuntime, VehicleLayer } from "./contracts";
import { getCopy } from "./copy";
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
  planner?: PlannerState;
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
  zoomIn: () => activeMap?.bridge.zoomIn(),
  zoomOut: () => activeMap?.bridge.zoomOut(),
  closePlanner: () => activeMap?.bridge.planner.close(),
  pickPointFor: (side) => activeMap?.bridge.planner.pickPointFor(side),
  useMyLocationFor: (side) => activeMap?.bridge.planner.useMyLocationFor(side),
  swapPlanner: () => activeMap?.bridge.planner.swap(),
  clearPlanner: () => activeMap?.bridge.planner.clear(),
  selectPlannerOption: (id) => activeMap?.bridge.planner.selectOption(id),
  retryPlanner: () => activeMap?.bridge.planner.retry(),
}, language);
const copy = getCopy(language);

const telegram = connectTelegram(() => {
  if (activeRoutes) activeRoutes.cancel();
  else if (activeMap?.planner?.open) activeMap.bridge.planner.close();
  else activeMap?.bridge.dismissArrivals();
});

function updateBackButton(entry: ActiveMap) {
  telegram.setBackVisible(Boolean(entry.state?.arrivals) || Boolean(entry.planner?.open));
}

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

function mapVisible() {
  return !activeRoutes && document.visibilityState === "visible";
}

function updateVisibility() {
  const visible = mapVisible();
  activeMap?.bridge.setVisible(visible);
  if (!activeMap) return;
  activeMap.renderer?.setVisible(visible);
  if (visible) activeMap.location.resume();
  else activeMap.location.pause();
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
      tapStop: (id, revision) => entry.bridge.tapStop(id, revision),
      updateViewport: (lat, lng, radius, zoom) => entry.bridge.updateViewport(lat, lng, radius, zoom),
      selectVehicle: (id, revision) => entry.bridge.selectVehicle(id, revision),
      setUnavailable,
      longPress: (lat, lng) => entry.bridge.planner.longPressMap(lat, lng),
      routeHere: () => entry.bridge.planner.routeHere(),
      routeFromHere: () => entry.bridge.planner.routeFromHere(),
      dismissPin: () => entry.bridge.planner.dismissPin(),
    }, { routeHere: copy.plannerRouteHere, routeFromHere: copy.plannerRouteFromHere });
    entry.renderer.setVisible(mapVisible());
    if (entry.vehicles) entry.renderer.updateVehicles(entry.vehicles);
    if (entry.planner) entry.renderer.updatePlanner(entry.planner);
    setUnavailable(false);
  } catch {
    setUnavailable(true);
  }
}

function renderMap(entry: ActiveMap) {
  view.showMap(entry.state);
  updateBackButton(entry);
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
    entry.location.resume(true);
    entry.bridge.subscribeVehicles((layer) => {
      if (activeMap !== entry) return;
      entry.vehicles = layer;
      entry.renderer?.updateVehicles(layer);
    });
    entry.bridge.planner.subscribe((state) => {
      if (activeMap !== entry) return;
      entry.planner = state;
      // The panel first: a renderer failure must not leave the cards out of step with the map.
      view.showPlanner(state);
      if (!activeRoutes) updateBackButton(entry);
      entry.renderer?.updatePlanner(state);
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
  if (document.visibilityState === "visible" && !activeRoutes) activeMap?.location.resume(true);
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
