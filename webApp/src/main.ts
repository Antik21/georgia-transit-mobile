import type { MapEntry, MapState, RoutesEntry, TransitRuntime } from "./contracts";
import type { TransitMap } from "./map";
import { connectTelegram } from "./telegram";
import { createTransitView } from "./ui";
import "./styles.css";

interface ActiveMap {
  bridge: MapEntry;
  state?: MapState;
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
  map?.bridge.dispose();
  telegram.setBackVisible(false);
}

function updateVisibility() {
  activeMap?.bridge.setVisible(!activeRoutes && document.visibilityState === "visible");
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
      setUnavailable,
    });
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

function showMap(session: TransitRuntime) {
  // The runtime pops the route draft and retains the map entry when returning from Routes.
  activeRoutes = undefined;
  if (activeMap) {
    renderMap(activeMap);
    activeMap.renderer?.resize();
  } else {
    const entry: ActiveMap = { bridge: session.openMap() };
    activeMap = entry;
    renderMap(entry);
    entry.bridge.subscribe((state) => {
      if (activeMap !== entry) return;
      entry.state = state;
      if (!activeRoutes) renderMap(entry);
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

document.addEventListener("visibilitychange", updateVisibility);
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
