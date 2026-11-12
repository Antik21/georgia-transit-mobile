import { getCopy, getLanguage } from "./copy";
import { applyRouteColors, button, element, heading, icon, replacePreservingFocus, status } from "./dom";
import type { ArrivalsState, Attribution, CityState, MapState, PlannerState, RouteChoice, RoutesState, StopItem } from "./contracts";
import { renderPlannerPanel, type PlannerActions } from "./planner-ui";

export interface ViewActions extends PlannerActions {
  selectCity(id: string): void;
  confirmCity(): void;
  retryCities(): void;
  retryStartup(): void;
  changeCity(): void;
  selectStop(id: string, sourceRevision: string): void;
  retryNearby(): void;
  dismissArrivals(): void;
  retryArrivals(): void;
  openRoutes(): void;
  searchRoutes(query: string): void;
  toggleRoute(id: string): void;
  retryRoutes(): void;
  confirmRoutes(): void;
  cancelRoutes(): void;
  focusRoute(id: string): void;
  retryRoute(id: string): void;
  removeRoute(id: string): void;
  myLocation(): void;
}

type Copy = ReturnType<typeof getCopy>;
type Screen = "startup" | "city" | "map" | "routes";

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
  const mapControls = element("div", "map-controls");
  const openRoutes = button("secondary-button map-routes-button", copy.openRoutes, actions.openRoutes, "open-routes");
  const routeLegends = element("ul", "map-route-legends");
  routeLegends.setAttribute("aria-label", copy.selectedRoutes);
  const vehicleStatus = element("p", "map-vehicle-status");
  vehicleStatus.dataset.testid = "vehicle-status";
  // Mirrors the mobile accessibility summary: announced politely, never drawn.
  const vehicleSummary = element("p", "visually-hidden");
  vehicleSummary.setAttribute("aria-live", "polite");
  vehicleSummary.dataset.testid = "vehicle-summary";
  mapControls.append(openRoutes, routeLegends, vehicleStatus, vehicleSummary);
  const mapCredits = element("div", "map-credits");
  const locationControls = element("div", "map-location");
  const locationStatus = element("p", "map-location-status");
  locationStatus.setAttribute("role", "status");
  locationStatus.dataset.testid = "location-status";
  // A permission state the user never asked about stays in the button label, as on mobile.
  let locationRequested = false;
  const locationButton = button("map-location-button", "", () => {
    locationRequested = true;
    actions.myLocation();
  }, "my-location");
  locationButton.append(locationIcon());
  locationControls.append(locationStatus, locationButton);
  mapRegion.append(mapElement, mapOverlay, mapControls, locationControls, mapCredits);
  const mapPanel = element("section", "map-panel");
  mapPanel.setAttribute("aria-labelledby", "map-panel-heading");
  map.append(mapRegion, mapPanel);

  const routePicker = createRoutePicker(copy, actions);
  app.append(header, startup, city, map, routePicker.element);
  root.replaceChildren(app);

  let screen: Screen = "startup";
  // The panel's current subject: a stop's arrivals, the planner, or null for nearby stops.
  let panelKey: string | null = null;
  let mapState: MapState | undefined;
  let planner: PlannerState | undefined;

  app.addEventListener("keydown", (event) => {
    if (event.key !== "Escape" || event.defaultPrevented || event.isComposing) return;
    if (screen === "routes") {
      event.preventDefault();
      actions.cancelRoutes();
    } else if (screen === "map" && planner?.open) {
      event.preventDefault();
      actions.closePlanner();
    } else if (screen === "map" && panelKey) {
      event.preventDefault();
      actions.dismissArrivals();
    }
  });

  function setScreen(next: Screen, focusTarget?: HTMLElement) {
    const changed = screen !== next;
    screen = next;
    startup.hidden = next !== "startup";
    city.hidden = next !== "city";
    map.hidden = next !== "map";
    routePicker.element.hidden = next !== "routes";
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
    panelKey = null;
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
    mapState = state;
    const returningFromRoutes = screen === "routes";
    if (screen !== "map" && (!returningFromRoutes || !state)) setMapLoading();
    const name = state?.cityName ?? "";
    changeCity.hidden = !state?.canChangeCity;
    changeCityName.textContent = name || copy.changeCity;
    changeCity.setAttribute("aria-label", name ? `${copy.changeCity} (${name})` : copy.changeCity);
    cityName.hidden = !name || !changeCity.hidden;
    cityName.textContent = name;
    updateCredits(mapCredits, state?.attribution ?? []);
    openRoutes.hidden = !state?.canOpenRoutes;
    const legends = state?.routeLegends ?? [];
    routeLegends.hidden = legends.length === 0;
    replacePreservingFocus(routeLegends, legends.map((route) => renderRouteLegend(route, copy, actions)));
    renderLocation(state?.location);
    const vehicleText = vehicleStatusText(copy, state?.vehicleStatus);
    vehicleStatus.hidden = !vehicleText;
    if (vehicleStatus.textContent !== (vehicleText ?? "")) vehicleStatus.textContent = vehicleText ?? "";
    const summaryText = vehicleSummaryText(copy, state?.vehicleStatus, state?.vehicleRoutes ?? []);
    if (vehicleSummary.textContent !== summaryText) vehicleSummary.textContent = summaryText;

    const nextPanelKey = planner?.open ? "planner" : state?.arrivals?.stopId ?? null;
    const previousArrivalStop = panelKey;
    const content = planner?.open ? renderPlannerPanel(planner, copy, actions, language)
      : state?.arrivals ? renderArrivals(state.arrivals, copy, actions) : renderStops(state, copy, actions);
    const panelHeading = content.querySelector<HTMLElement>("h1");
    if (panelHeading) panelHeading.id = "map-panel-heading";
    if (nextPanelKey !== previousArrivalStop) {
      mapPanel.replaceChildren(content);
      mapPanel.scrollTop = 0;
      const previousStop = Array.from(mapPanel.querySelectorAll<HTMLElement>("[data-stop-id]"))
        .find((stop) => stop.dataset.stopId === previousArrivalStop);
      (previousStop ?? panelHeading)?.focus({ preventScroll: true });
    } else {
      replacePreservingFocus(mapPanel, [content]);
    }
    panelKey = nextPanelKey;
    setScreen("map", returningFromRoutes && !openRoutes.hidden ? openRoutes : panelHeading ?? undefined);
  }

  function renderLocation(location: MapState["location"]) {
    locationControls.hidden = !location;
    if (!location) return;
    const label = locationActionLabel(copy, location);
    locationButton.setAttribute("aria-label", label);
    locationButton.title = label;
    locationButton.disabled = !location.actionable;
    locationButton.setAttribute("aria-busy", String(location.locating));
    locationButton.dataset.permission = location.permission;
    const message = location.locating ? copy.locationLocating
      : location.failure || locationRequested ? locationStatusText(copy, location) : null;
    locationStatus.hidden = !message;
    if (locationStatus.textContent !== (message ?? "")) locationStatus.textContent = message ?? "";
  }

  function showRoutes(state?: RoutesState) {
    routePicker.render(state, screen !== "routes");
    setScreen("routes", routePicker.title);
  }

  function setMapLoading() {
    mapOverlay.replaceChildren(status(copy.mapLoading, true));
    mapOverlay.hidden = false;
  }

  function setMapUnavailable(unavailable = true) {
    mapOverlay.hidden = !unavailable;
    if (unavailable) mapOverlay.replaceChildren(status(copy.mapUnavailable));
  }

  function showPlanner(state: PlannerState) {
    planner = state;
    if (screen === "map") showMap(mapState);
  }

  showLoading();
  return {
    mapElement, showLoading, showStartupError, showCitySelection, showMap, showRoutes, showPlanner, setMapLoading, setMapUnavailable,
  };
}

function createRoutePicker(copy: Copy, actions: ViewActions) {
  const screen = element("main", "routes-screen");
  screen.dataset.testid = "routes-screen";
  const card = element("section", "routes-card");
  const title = heading(copy.routes);
  title.id = "routes-screen-heading";
  card.setAttribute("aria-labelledby", title.id);

  const meta = element("div", "routes-meta");
  const city = element("span", "routes-city");
  const count = element("span", "routes-count");
  count.dataset.testid = "route-selection-count";
  count.setAttribute("role", "status");
  meta.append(city, count);
  const header = element("div", "routes-header");
  header.append(title, meta);

  const search = element("div", "routes-search");
  const field = element("input", "routes-search-field");
  field.type = "search";
  field.enterKeyHint = "search";
  field.placeholder = copy.routesSearchHint;
  field.setAttribute("aria-label", copy.routesSearch);
  field.dataset.testid = "route-search";
  field.addEventListener("input", (event) => {
    clear.hidden = !field.value;
    if (!(event instanceof InputEvent) || !event.isComposing) actions.searchRoutes(field.value);
  });
  field.addEventListener("compositionend", () => actions.searchRoutes(field.value));
  field.addEventListener("keydown", (event) => {
    if (event.isComposing) return;
    if (event.key === "Escape" && field.value) {
      // Escape clears the query first; the app-level handler would discard the whole draft.
      event.preventDefault();
      event.stopPropagation();
      clear.click();
    } else if (event.key === "Enter") {
      event.preventDefault();
      field.blur();
    }
  });
  const clear = button("route-icon-button", "×", () => {
    field.value = "";
    clear.hidden = true;
    field.focus();
    actions.searchRoutes("");
  }, "clear-route-search");
  clear.setAttribute("aria-label", copy.routesClearSearch);
  search.append(field, clear);

  const content = element("div", "routes-content");
  const footer = element("div", "routes-footer");
  const cancel = button("secondary-button", copy.routesClose, actions.cancelRoutes, "cancel-routes");
  const confirm = button("primary-button", "", actions.confirmRoutes, "confirm-routes");
  footer.append(cancel, confirm);
  card.append(header, search, content, footer);
  screen.append(card);

  let lastQuery = "";
  return {
    element: screen,
    title,
    render(state: RoutesState | undefined, entering: boolean) {
      const selected = state?.selectedCount ?? 0;
      const selectionLimit = state?.selectionLimit ?? 0;
      const limitReached = selectionLimit > 0 && selected >= selectionLimit;
      const available = state?.status === "available";
      const isConfirming = state?.isConfirming === true;
      const query = state?.searchQuery ?? "";
      city.textContent = state?.cityName ?? "";
      const selectionLabel = formatNumber(copy.routesSelected, selected);
      if (count.textContent !== selectionLabel) count.textContent = selectionLabel;
      cancel.disabled = isConfirming;
      confirm.disabled = !state?.canConfirm;
      confirm.textContent = formatNumber(copy.routesConfirm, selected);
      search.hidden = !available;
      card.classList.toggle("has-route-search", available);
      field.disabled = isConfirming;
      if (entering || document.activeElement !== field) field.value = query;
      clear.hidden = !field.value;
      clear.disabled = isConfirming;

      const children: HTMLElement[] = [];
      if (available && limitReached) {
        const warning = status(formatNumber(copy.routesLimit, selectionLimit));
        warning.dataset.testid = "route-selection-limit";
        children.push(warning);
      }
      if (state?.freshness === "staleOffline") children.push(status(copy.routesStale));
      const message = routesStatus(copy, state?.status);
      if (message) children.push(status(message, !state || state.status === "loading"));
      if (state?.canRetry) children.push(button("secondary-button", copy.retry, actions.retryRoutes, "retry-routes"));
      if (available) {
        if (!query.trim() && state.recentRoutes.length) {
          const recent = element("section", "routes-recent");
          recent.append(element("h2", "routes-subheading", copy.routesRecent), routeOptions(copy, state.recentRoutes, limitReached, isConfirming, actions, true));
          children.push(recent);
        }
        if (state.routes.length) children.push(routeOptions(copy, state.routes, limitReached, isConfirming, actions));
        else children.push(status(query.trim() ? copy.routesNoMatches : copy.routesEmpty));
      }
      replacePreservingFocus(content, children);
      if (entering || query !== lastQuery) content.scrollTop = 0;
      lastQuery = query;
    },
  };
}

function routeOptions(copy: Copy, routes: readonly RouteChoice[], limitReached: boolean, confirming: boolean, actions: ViewActions, recent = false) {
  const list = element("ul", "route-options");
  for (const route of routes) {
    const row = element("li", "");
    const option = button("list-button route-option", "", () => actions.toggleRoute(route.id), recent ? "recent-route-option" : "route-option");
    option.dataset.routeId = route.id;
    option.dataset.focusKey = `${recent ? "recent" : "route"}-${route.id}`;
    option.disabled = confirming || (limitReached && !route.selected);
    option.setAttribute("aria-pressed", String(route.selected));
    const badge = element("span", "route-number route-option-number", route.shortName);
    applyRouteColors(badge, route.color, route.textColor);
    const name = route.name === route.shortName ? "" : route.name;
    const direction = route.direction === route.shortName || route.direction === name ? "" : route.direction;
    // Same row as the mobile RouteRow: the mode title is always shown, so a catalogue without names is never blank.
    const detail = element("span", "list-name", routeTitle(copy, route.mode, route.shortName));
    const subtitle = direction || name;
    if (subtitle) detail.append(element("small", "list-detail", subtitle));
    option.append(badge, detail, icon("selection-indicator"));
    row.append(option);
    list.append(row);
  }
  return list;
}

function renderRouteLegend(route: MapState["routeLegends"][number], copy: Copy, actions: ViewActions) {
  const row = element("li", "map-route-legend");
  row.dataset.testid = "route-legend";
  row.dataset.routeId = route.routeId;
  const focus = button("map-route-focus", "", () => actions.focusRoute(route.routeId), "focus-route");
  focus.dataset.focusKey = `focus-${route.routeId}`;
  focus.setAttribute("aria-pressed", String(route.focused));
  const badge = element("span", "route-number", route.label);
  applyRouteColors(badge, route.color, route.textColor);
  focus.append(badge);
  const state = routeLineStatus(copy, route.status);
  if (state) focus.append(element("span", "map-route-status", state));
  row.append(focus);
  if (route.canRetry) {
    const retry = button("route-icon-button", "↻", () => actions.retryRoute(route.routeId), "retry-route");
    retry.dataset.focusKey = `retry-${route.routeId}`;
    retry.setAttribute("aria-label", copy.retryRoute.replace("%1$s", route.label));
    row.append(retry);
  }
  const remove = button("route-icon-button", "×", () => actions.removeRoute(route.routeId), "remove-route");
  remove.dataset.focusKey = `remove-${route.routeId}`;
  remove.setAttribute("aria-label", copy.removeRoute.replace("%1$s", route.label));
  row.append(remove);
  return row;
}

function locationActionLabel(copy: Copy, location: NonNullable<MapState["location"]>) {
  switch (location.permission) {
    case "notDetermined": return copy.locationEnable;
    case "denied":
    case "unavailable":
    case "error": return copy.locationRetry;
    case "settingsRequired": return copy.locationSettings;
    case "servicesDisabled": return copy.locationServicesSettings;
    default: return copy.myLocation;
  }
}

/** Visible counterpart of the mobile content description, shown only when something needs attention. */
function locationStatusText(copy: Copy, location: NonNullable<MapState["location"]>) {
  switch (location.failure) {
    case "timedOut": return copy.locationTimedOut;
    case "invalidFix": return copy.locationInvalid;
    case "unavailable": return copy.locationFailed;
  }
  switch (location.permission) {
    case "denied": return copy.locationDenied;
    case "settingsRequired": return copy.locationSettingsRequired;
    case "restricted": return copy.locationRestricted;
    case "servicesDisabled": return copy.locationServicesDisabled;
    case "unavailable": return copy.locationUnavailable;
    case "error": return copy.locationError;
    default: return null;
  }
}

const svgNamespace = "http://www.w3.org/2000/svg";

function locationIcon() {
  const svg = document.createElementNS(svgNamespace, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  svg.classList.add("map-location-icon");
  for (const [tag, attributes] of [
    ["circle", { cx: "12", cy: "12", r: "7" }],
    ["circle", { cx: "12", cy: "12", r: "2.5", fill: "currentColor" }],
    ["path", { d: "M12 1v4M12 19v4M1 12h4M19 12h4" }],
  ] as const) {
    const shape = document.createElementNS(svgNamespace, tag);
    for (const [name, value] of Object.entries(attributes)) shape.setAttribute(name, value);
    svg.append(shape);
  }
  return svg;
}

function vehicleStateLabel(copy: Copy, status?: string) {
  return {
    loading: copy.vehiclesLoading,
    live: copy.vehiclesLive,
    stale: copy.vehiclesStale,
    retryable: copy.vehiclesRetryable,
    unavailable: copy.vehiclesUnavailable,
    mixed: copy.vehiclesMixed,
  }[status ?? ""];
}

function vehicleSummaryText(copy: Copy, status: string | undefined, routes: MapState["vehicleRoutes"]) {
  const label = vehicleStateLabel(copy, status);
  if (!label || routes.length === 0) return "";
  const counts = routes
    .map((route) => `${route.label}: ${route.vehicleCount}, ${vehicleStateLabel(copy, route.status) ?? ""}`)
    .join(" · ");
  return copy.vehiclesSummary.replace("%1$s", label).replace("%2$s", counts);
}

function vehicleStatusText(copy: Copy, status?: string) {
  const label = {
    loading: copy.vehiclesLoading,
    stale: copy.vehiclesStale,
    retryable: copy.vehiclesRetryable,
    unavailable: copy.vehiclesUnavailable,
    mixed: copy.vehiclesMixed,
  }[status ?? ""];
  return label ? copy.vehiclesStatus.replace("%1$s", label) : null;
}

function formatNumber(template: string, count: number) {
  return template.replace("%1$d", String(count));
}

function routesStatus(copy: Copy, state?: string) {
  switch (state) {
    case "available": return null;
    case "empty": return copy.routesEmpty;
    case "offlineNoCache": return copy.routesOffline;
    case "unavailable": return copy.routesUnavailable;
    case "error": return copy.routesError;
    default: return copy.routesLoading;
  }
}

function routeLineStatus(copy: Copy, state: string) {
  switch (state) {
    case "loading": return copy.routeLineLoading;
    case "partial": return copy.routeLinePartial;
    case "retryable": return copy.routeLineError;
    case "unavailable": return copy.routeLineUnavailable;
    case "selectionOverflow": return copy.routeLineOverflow;
    default: return null;
  }
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

function routeTitle(copy: Copy, mode: string, number: string) {
  const template = mode === "metro" ? copy.routeTitleMetro : mode === "tram" ? copy.routeTitleTram
    : mode === "ferry" ? copy.routeTitleFerry : copy.routeTitleBus;
  return template.replace("%1$s", number);
}

function arrivalSource(copy: Copy, source: string) {
  switch (source) {
    case "OfficialRealtime": return copy.officialRealtime;
    case "AggregatorRealtime": return copy.aggregatorRealtime;
    case "Schedule": return copy.scheduled;
    case "Approximate": return copy.estimated;
    default: return null;
  }
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
