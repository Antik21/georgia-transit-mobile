import type { MapEntry, MapState, TransitRuntime } from "./contracts";
import type { TransitMap } from "./map";
import { connectTelegram } from "./telegram";
import { createTransitView } from "./ui";
import "./styles.css";

interface ActiveMap {
  bridge: MapEntry;
  state?: MapState;
  renderer?: TransitMap;
  rendererRequested?: boolean;
}

const root = document.querySelector<HTMLElement>("#app");
if (!root) throw new Error("App container is missing");

let runtime: TransitRuntime | undefined;
let activeMap: ActiveMap | undefined;
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
}, language);

const telegram = connectTelegram(() => activeMap?.bridge.dismissArrivals());

function closeMap() {
  const previous = activeMap;
  activeMap = undefined;
  previous?.renderer?.dispose();
  previous?.bridge.dispose();
  telegram.setBackVisible(false);
}

function updateVisibility() {
  activeMap?.bridge.setVisible(document.visibilityState === "visible");
}

async function loadMapRenderer(entry: ActiveMap) {
  entry.rendererRequested = true;
  try {
    const { createTransitMap } = await import("./map");
    if (activeMap !== entry || !entry.state?.camera) return;
    entry.renderer = createTransitMap(view.mapElement, entry.state, {
      selectStop: (stop) => entry.bridge.selectStop(stop.id, stop.sourceRevision),
      updateViewport: (lat, lng, radius, zoom) => entry.bridge.updateViewport(lat, lng, radius, zoom),
      setUnavailable: view.setMapUnavailable,
    });
    view.setMapUnavailable(false);
  } catch {
    if (activeMap === entry) view.setMapUnavailable();
  }
}

function openMap(session: TransitRuntime) {
  closeMap();
  const entry: ActiveMap = { bridge: session.openMap() };
  activeMap = entry;
  view.showMap();
  entry.bridge.subscribe((state) => {
    if (activeMap !== entry) return;
    entry.state = state;
    view.showMap(state);
    telegram.setBackVisible(Boolean(state.arrivals));
    if (state.camera) {
      if (entry.renderer) entry.renderer.update(state);
      else if (!entry.rendererRequested) void loadMapRenderer(entry);
    }
  });
  updateVisibility();
}

async function start() {
  const revision = ++startupRevision;
  closeMap();
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
        openMap(session);
      } else {
        closeMap();
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
  closeMap();
  runtime?.dispose();
  telegram.dispose();
});

void start();
