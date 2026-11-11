import { getCopy, getLanguage } from "./copy";
import type { ArrivalsState, Attribution, CityState, MapState, StopItem } from "./contracts";

export interface ViewActions {
  selectCity(id: string): void;
  confirmCity(): void;
  retryCities(): void;
  retryStartup(): void;
  changeCity(): void;
  selectStop(id: string, sourceRevision: string): void;
  retryNearby(): void;
  dismissArrivals(): void;
  retryArrivals(): void;
}

type Copy = ReturnType<typeof getCopy>;
type Screen = "startup" | "city" | "map";

export function createTransitView(root: HTMLElement, actions: ViewActions, languageTag = navigator.language) {
  const language = getLanguage(languageTag);
  const copy = getCopy(language);
  document.documentElement.lang = language;

  const app = element("div", "transit-app");
  const header = element("header", "transit-header");
  const brand = element("div", "transit-brand");
  const brandMark = element("span", "brand-mark");
  brandMark.setAttribute("aria-hidden", "true");
  brand.append(brandMark, element("span", "brand-name", copy.appName));
  const cityName = element("span", "header-city-name");
  const changeCity = button("city-switch", "", actions.changeCity, "change-city");
  const changeCityName = element("span", "city-switch-name");
  changeCity.append(changeCityName, icon("chevron-down"));
  header.append(brand, cityName, changeCity);

  const startup = element("main", "startup-screen");
  const city = element("main", "city-screen");
  city.dataset.testid = "city-screen";
  const cityCard = element("section", "city-card");
  const cityHeading = heading(copy.selectCity);
  cityHeading.id = "city-screen-heading";
  cityCard.setAttribute("aria-labelledby", cityHeading.id);
  const cityContent = element("div", "city-content");
  const cityFooter = element("div", "city-footer");
  const confirmCity = button("primary-button", copy.continue, actions.confirmCity, "confirm-city");
  cityFooter.append(confirmCity);
  cityCard.append(cityHeading, cityContent, cityFooter);
  city.append(cityCard);

  const map = element("main", "map-screen");
  map.dataset.testid = "map-screen";
  const mapRegion = element("section", "map-region");
  mapRegion.setAttribute("aria-label", copy.map);
  const mapElement = element("div", "map-canvas");
  const mapOverlay = element("div", "map-overlay");
  const mapCredits = element("div", "map-credits");
  mapRegion.append(mapElement, mapOverlay, mapCredits);
  const mapPanel = element("section", "map-panel");
  mapPanel.setAttribute("aria-labelledby", "map-panel-heading");
  map.append(mapRegion, mapPanel);

  app.append(header, startup, city, map);
  root.replaceChildren(app);

  let screen: Screen = "startup";
  let arrivalStop: string | null = null;

  app.addEventListener("keydown", (event) => {
    if (event.key !== "Escape" || event.defaultPrevented || screen !== "map" || !arrivalStop) return;
    event.preventDefault();
    actions.dismissArrivals();
  });

  function setScreen(next: Screen, focusTarget?: HTMLElement) {
    const changed = screen !== next;
    screen = next;
    startup.hidden = next !== "startup";
    city.hidden = next !== "city";
    map.hidden = next !== "map";
    if (next !== "map") {
      cityName.hidden = true;
      changeCity.hidden = true;
    }
    if (changed) focusTarget?.focus({ preventScroll: true });
  }

  function showLoading() {
    startup.replaceChildren(status(copy.startup, true));
    setScreen("startup");
  }

  function showStartupError() {
    const title = heading(copy.startupError);
    startup.replaceChildren(title, button("secondary-button", copy.retry, actions.retryStartup));
    setScreen("startup");
    title.focus({ preventScroll: true });
  }

  function showCitySelection(state?: CityState) {
    arrivalStop = null;
    confirmCity.disabled = !state?.canContinue;
    const rows = element("ul", "city-options");
    for (const item of state?.cities ?? []) {
      const row = element("li", "city-option-row");
      const select = button("list-button city-option", "", () => actions.selectCity(item.id), "city-option");
      select.dataset.cityId = item.id;
      select.dataset.focusKey = `city-${item.id}`;
      select.disabled = !item.enabled;
      select.setAttribute("aria-pressed", String(item.id === state?.selectedId));
      const name = element("span", "list-name", item.name);
      const detail = item.enabled ? (item.experimental ? copy.cityExperimental : "") : copy.cityUnavailable;
      if (detail) name.append(element("small", "list-detail", detail));
      select.append(icon("city-icon"), name, icon("selection-indicator"));
      row.append(select);
      const credits = creditLinks(item.attribution);
      if (credits.childElementCount) row.append(credits);
      rows.append(row);
    }

    const message = cityStatus(copy, state?.status);
    const contents: HTMLElement[] = [];
    if (message) contents.push(status(message, !state || state.status === "loading"));
    if (state?.status === "retryableError" || state?.status === "empty") {
      contents.push(button("secondary-button", copy.retry, actions.retryCities));
    }
    if (rows.childElementCount) contents.push(rows);
    replacePreservingFocus(cityContent, contents);
    setScreen("city", cityHeading);
  }

  function showMap(state?: MapState) {
    if (screen !== "map") setMapLoading();
    const name = state?.cityName ?? "";
    changeCity.hidden = !state?.canChangeCity;
    changeCityName.textContent = name || copy.changeCity;
    changeCity.setAttribute("aria-label", name ? `${copy.changeCity} (${name})` : copy.changeCity);
    cityName.hidden = !name || !changeCity.hidden;
    cityName.textContent = name;
    updateCredits(mapCredits, state?.attribution ?? []);

    const nextArrivalStop = state?.arrivals?.stopId ?? null;
    const previousArrivalStop = arrivalStop;
    const content = state?.arrivals ? renderArrivals(state.arrivals, copy, actions) : renderStops(state, copy, actions);
    const panelHeading = content.querySelector<HTMLElement>("h1");
    if (panelHeading) panelHeading.id = "map-panel-heading";
    if (nextArrivalStop !== previousArrivalStop) {
      mapPanel.replaceChildren(content);
      mapPanel.scrollTop = 0;
      const previousStop = Array.from(mapPanel.querySelectorAll<HTMLElement>("[data-stop-id]"))
        .find((stop) => stop.dataset.stopId === previousArrivalStop);
      (previousStop ?? panelHeading)?.focus({ preventScroll: true });
    } else {
      replacePreservingFocus(mapPanel, [content]);
    }
    arrivalStop = nextArrivalStop;
    setScreen("map", panelHeading ?? undefined);
  }

  function setMapLoading() {
    mapOverlay.replaceChildren(status(copy.mapLoading, true));
    mapOverlay.hidden = false;
  }

  function setMapUnavailable(unavailable = true) {
    mapOverlay.hidden = !unavailable;
    if (unavailable) mapOverlay.replaceChildren(status(copy.mapUnavailable));
  }

  showLoading();
  return { mapElement, showLoading, showStartupError, showCitySelection, showMap, setMapLoading, setMapUnavailable };
}

function renderStops(state: MapState | undefined, copy: Copy, actions: ViewActions) {
  const panel = element("div", "panel-content");
  panel.append(heading(copy.nearby));
  const message = stopStatus(copy, state?.content);
  if (message) panel.append(status(message, !state || state.content === "loading"));
  if (state?.content === "retryableError" || state?.content === "offline" || state?.content === "offlineStale") {
    panel.append(button("secondary-button", copy.retry, actions.retryNearby));
  }

  if (state?.stops.length) {
    const stops = element("ul", "stop-list");
    for (const stop of state.stops) stops.append(stopRow(stop, actions));
    panel.append(stops);
  }
  return panel;
}

function stopRow(stop: StopItem, actions: ViewActions) {
  const row = element("li", "stop-row");
  const open = button("list-button stop-option", "", () => actions.selectStop(stop.id, stop.sourceRevision), "stop-option");
  open.dataset.stopId = stop.id;
  open.dataset.focusKey = `stop-${stop.id}`;
  open.append(icon("stop-icon"), element("span", "list-name", stop.name), icon("chevron-right"));
  row.append(open);
  return row;
}

function renderArrivals(arrivals: ArrivalsState, copy: Copy, actions: ViewActions) {
  const panel = element("div", "panel-content");
  panel.dataset.testid = "arrivals-panel";
  const dismiss = button("back-button", "", actions.dismissArrivals, "dismiss-arrivals");
  dismiss.dataset.focusKey = "dismiss";
  dismiss.append(icon("arrow-left"), element("span", "", copy.backToStops));
  panel.append(dismiss, heading(arrivals.stopName));

  const message = arrivalStatus(copy, arrivals.status);
  if (message) panel.append(status(message, arrivals.status === "loading"));
  if (arrivals.stale) panel.append(status(copy.arrivalsStale));
  if (arrivals.refreshing && arrivals.status !== "loading") panel.append(status(copy.arrivalsRefreshing, true));
  if (arrivals.status === "upstreamError" || arrivals.status === "offline") {
    panel.append(button("secondary-button", copy.retry, actions.retryArrivals));
  }
  if (arrivals.rows.length) {
    const rows = element("ul", "arrivals-list");
    rows.setAttribute("aria-label", copy.arrivals);
    for (const arrival of arrivals.rows) {
      const row = element("li", "arrival-row");
      row.dataset.testid = "arrival-row";
      const detail = element("div", "arrival-details");
      detail.append(element("span", "arrival-destination", arrival.headsign || copy.destinationUnavailable));
      const source = arrivalSource(copy, arrival.source);
      if (source) detail.append(element("span", "arrival-source", source));
      const eta = arrival.timeKind === "arriving" ? copy.arriving
        : arrival.timeKind === "minutes" && arrival.minutes != null ? `${arrival.minutes} ${copy.minutes}`
          : copy.timeUnavailable;
      const time = element("span", "arrival-time", eta);
      time.dataset.kind = arrival.timeKind;
      if (arrival.route) row.append(element("span", "route-number", arrival.route));
      row.append(detail, time);
      rows.append(row);
    }
    panel.append(rows);
  }
  return panel;
}

function cityStatus(copy: Copy, state?: string) {
  switch (state) {
    case "populated": return null;
    case "empty": return copy.cityEmpty;
    case "retryableError": return copy.cityError;
    default: return copy.cityLoading;
  }
}

function stopStatus(copy: Copy, state?: string) {
  switch (state) {
    case "ready": return null;
    case "empty": return copy.stopsEmpty;
    case "retryableError": return copy.stopsError;
    case "offline": return copy.stopsOffline;
    case "offlineStale": return copy.stopsStale;
    case "unavailable": return copy.stopsUnavailable;
    default: return copy.stopsLoading;
  }
}

function arrivalStatus(copy: Copy, state: string) {
  switch (state) {
    case "ready": return null;
    case "noArrivals": return copy.arrivalsEmpty;
    case "partialData": return copy.arrivalsPartial;
    case "offline": return copy.arrivalsOffline;
    case "upstreamError": return copy.arrivalsError;
    case "unavailable": return copy.arrivalsUnavailable;
    default: return copy.arrivalsLoading;
  }
}

function arrivalSource(copy: Copy, source: string) {
  switch (source) {
    case "OfficialRealtime": return copy.officialRealtime;
    case "AggregatorRealtime": return copy.aggregatorRealtime;
    case "Schedule": return copy.scheduled;
    case "ClientEstimate": return copy.estimated;
    default: return null;
  }
}

function status(message: string, loading = false) {
  const result = element("p", "status-message");
  result.setAttribute("role", "status");
  if (loading) result.append(icon("spinner"));
  result.append(document.createTextNode(message));
  return result;
}

function creditLinks(attribution: readonly Attribution[]) {
  const credits = element("div", "attribution-links");
  for (const item of attribution) {
    const url = safeCreditUrl(item.url);
    if (!url) continue;
    const link = element("a", "", item.label);
    link.href = url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    credits.append(link);
  }
  return credits;
}

function safeCreditUrl(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password ? url.href : null;
  } catch {
    return null;
  }
}

function updateCredits(container: HTMLElement, attribution: readonly Attribution[]) {
  const credits = creditLinks(attribution);
  container.hidden = !credits.childElementCount;
  if (container.firstElementChild?.isEqualNode(credits)) return;
  container.replaceChildren(credits);
}

function replacePreservingFocus(container: HTMLElement, children: HTMLElement[]) {
  const active = document.activeElement;
  const focusKey = active instanceof HTMLElement && container.contains(active) ? active.dataset.focusKey : undefined;
  container.replaceChildren(...children);
  if (!focusKey) return;
  const focused = Array.from(container.querySelectorAll<HTMLElement>("[data-focus-key]"))
    .find((item) => item.dataset.focusKey === focusKey);
  focused?.focus({ preventScroll: true });
}

function heading(text: string) {
  const result = element("h1", "screen-heading", text);
  result.tabIndex = -1;
  result.dataset.focusKey = "heading";
  return result;
}

function icon(className: string) {
  const result = element("span", `icon ${className}`);
  result.setAttribute("aria-hidden", "true");
  return result;
}

function button(className: string, text: string, onClick: () => void, testId?: string) {
  const result = element("button", className, text);
  result.type = "button";
  if (testId) result.dataset.testid = testId;
  result.addEventListener("click", onClick);
  return result;
}

function element<K extends keyof HTMLElementTagNameMap>(tag: K, className: string, text?: string): HTMLElementTagNameMap[K] {
  const result = document.createElement(tag);
  if (className) result.className = className;
  if (text) result.textContent = text;
  return result;
}
