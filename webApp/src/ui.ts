import { getCopy, getLanguage } from "./copy";
import { applyRouteColors, button, element, heading, icon, replacePreservingFocus, status } from "./dom";
import type { ArrivalsState, Attribution, CityState, MapState, PlannerEndpoint, PlannerSide, PlannerState, RouteChoice, RoutesState } from "./contracts";
import { mapIcon } from "./map-icons";
import { createMapPointPicker } from "./planner-map-ui";
import { renderPlannerPanel, type PlannerActions } from "./planner-ui";
import appLogoUrl from "./assets/app-logo.webp";

export interface ViewActions extends PlannerActions {
  selectCity(id: string): void;
  confirmCity(): void;
  retryCities(): void;
  retryStartup(): void;
  changeCity(): void;
  retryNearby(): void;
  dismissArrivals(): void;
  retryArrivals(): void;
  openRoutes(): void;
  searchRoutes(query: string): void;
  toggleRoute(id: string): void;
  toggleRecentRoute(id: string): void;
  retryRoutes(): void;
  confirmRoutes(): void;
  cancelRoutes(): void;
  focusRoute(id: string): void;
  retryRoute(id: string): void;
  removeRoute(id: string): void;
  myLocation(): void;
  zoomIn(): void;
  zoomOut(): void;
  openPlanner(): void;
  cancelMapPick(): void;
  confirmMapPick(): void;
  dismissPlannerPin(): void;
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
  const brandLogo = element("img", "brand-logo");
  brandLogo.src = appLogoUrl;
  brandLogo.alt = "";
  brandLogo.dataset.testid = "brand-logo";
  brand.append(brandLogo, element("span", "brand-name", copy.appName));
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
  const confirmCity = button("primary-button", copy.openMap, actions.confirmCity, "confirm-city");
  cityFooter.append(confirmCity);
  cityCard.append(cityHeading, cityContent, cityFooter);
  city.append(cityCard);

  const map = element("main", "map-screen");
  map.dataset.testid = "map-screen";
  const mapColumn = element("div", "map-column");
  const mapRegion = element("section", "map-region");
  mapRegion.setAttribute("aria-label", copy.map);
  mapRegion.tabIndex = -1;
  const mapElement = element("div", "map-canvas");
  const mapOverlay = element("div", "map-overlay");
  const mapControls = element("div", "map-controls");
  const openRoutes = button("primary-button map-routes-button", copy.openRoutes, actions.openRoutes, "open-routes");
  const routeSelection = element("section", "map-route-selection");
  const routeHeading = element("h2", "map-route-heading", copy.selectedRoutes);
  routeHeading.id = "map-selected-routes";
  const routeLegends = element("ul", "map-route-legends");
  routeLegends.setAttribute("aria-labelledby", routeHeading.id);
  routeSelection.append(routeHeading, routeLegends);
  const vehicleStatus = element("p", "map-vehicle-status");
  vehicleStatus.dataset.testid = "vehicle-status";
  // Mirrors the mobile accessibility summary: announced politely, never drawn.
  const vehicleSummary = element("p", "visually-hidden");
  vehicleSummary.setAttribute("aria-live", "polite");
  vehicleSummary.dataset.testid = "vehicle-summary";
  // The footer grows upward. Placing status above the chips keeps their targets still as live data arrives.
  mapControls.append(vehicleStatus, routeSelection, openRoutes);
  const mapCredits = element("div", "map-credits");
  const mapLeftControls = element("div", "map-left-controls");
  const plannerControls = element("div", "map-planner-controls");
  const plannerNotice = status(copy.plannerUnavailable);
  plannerNotice.classList.add("map-planner-notice");
  plannerNotice.dataset.testid = "planner-unavailable-notice";
  plannerNotice.hidden = true;
  let plannerNoticeTimer: number | undefined;
  const openPlanner = button("map-planner-button", "", () => {
    if (mapState?.journeyPlanningAvailable) {
      actions.openPlanner();
    } else {
      plannerNotice.hidden = false;
      window.clearTimeout(plannerNoticeTimer);
      plannerNoticeTimer = window.setTimeout(hidePlannerNotice, 3_000);
    }
  }, "open-planner");
  openPlanner.append(mapIcon("journey"), element("span", "", copy.planTrip));
  plannerControls.append(plannerNotice, openPlanner);
  mapLeftControls.append(plannerControls, mapCredits);
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
  locationButton.append(mapIcon("location"));
  const zoomIn = button("map-zoom-button", "", actions.zoomIn, "zoom-in");
  const zoomOut = button("map-zoom-button", "", actions.zoomOut, "zoom-out");
  for (const [control, label, glyph] of [[zoomIn, copy.zoomIn, "zoomIn"], [zoomOut, copy.zoomOut, "zoomOut"]] as const) {
    control.setAttribute("aria-label", label);
    control.title = label;
    control.append(mapIcon(glyph));
  }
  locationControls.append(locationStatus, locationButton, zoomIn, zoomOut);
  // Mirrors the mobile map overlay: nearby-stop loading and errors show on the map, never as a list.
  const contentStatus = element("div", "map-content-status");
  contentStatus.dataset.testid = "map-content-status";
  const mapPointPicker = createMapPointPicker(copy, actions.cancelMapPick, actions.confirmMapPick);
  mapRegion.append(mapElement, mapOverlay, contentStatus, mapLeftControls, locationControls, mapPointPicker.header, mapPointPicker.target);
  const mapPanel = element("section", "map-panel");
  mapPanel.dataset.testid = "map-panel";
  mapPanel.setAttribute("aria-labelledby", "map-panel-heading");
  mapPanel.hidden = true;
  mapColumn.append(mapRegion, mapControls, mapPointPicker.footer, vehicleSummary);
  map.append(mapColumn, mapPanel);

  const routePicker = createRoutePicker(copy, actions);
  app.append(header, startup, city, map, routePicker.element);
  root.replaceChildren(app);

  let screen: Screen = "startup";
  // The panel's current subject: a stop's arrivals, the planner, or null when the panel is collapsed.
  let panelKey: string | null = null;
  let mapState: MapState | undefined;
  let planner: PlannerState | undefined;
  let expandedPlannerSide: PlannerSide | undefined;
  const plannerActions: PlannerActions = {
    ...actions,
    editPlannerPoints() {
      if (planner?.status === "empty") expandedPlannerSide = "origin";
      actions.editPlannerPoints();
    },
    useMyLocationFor(side) {
      if (planner?.[side].kind === "myLocation") {
        expandedPlannerSide = undefined;
        showMap(mapState);
      }
      actions.useMyLocationFor(side);
    },
  };

  function togglePlannerSide(side: PlannerSide) {
    expandedPlannerSide = expandedPlannerSide === side ? undefined : side;
    showMap(mapState);
  }

  app.addEventListener("keydown", (event) => {
    if (event.key !== "Escape" || event.defaultPrevented || event.isComposing) return;
    if (screen === "routes") {
      event.preventDefault();
      actions.cancelRoutes();
    } else if (screen === "map" && planner?.mapPick) {
      event.preventDefault();
      actions.cancelMapPick();
    } else if (screen === "map" && planner?.pin) {
      event.preventDefault();
      actions.dismissPlannerPin();
    } else if (screen === "map" && planner?.open) {
      event.preventDefault();
      if (planner.showingResults) actions.editPlannerPoints();
      else actions.closePlanner();
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
      hidePlannerNotice();
    }
    if (changed) focusTarget?.focus({ preventScroll: true });
  }

  function showLoading() {
    const loading = element("div", "startup-loading");
    loading.dataset.testid = "startup-loading";
    const logo = element("img", "startup-logo");
    logo.src = appLogoUrl;
    logo.alt = "";
    const progress = element("span", "startup-progress");
    progress.setAttribute("aria-hidden", "true");
    loading.append(logo, status(copy.startup), progress);
    startup.replaceChildren(loading);
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
      const credits = cityCreditLinks(item.id, item.attribution);
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
    const mapPick = planner?.mapPick;
    const pickSide = mapPick === "origin" || mapPick === "destination" ? mapPick : undefined;
    const returningFromRoutes = screen === "routes";
    if (screen !== "map" && (!returningFromRoutes || !state)) setMapLoading();
    const name = state?.cityName ?? "";
    changeCity.hidden = !state?.canChangeCity;
    changeCityName.textContent = name || copy.changeCity;
    changeCity.setAttribute("aria-label", name ? `${copy.changeCity} (${name})` : copy.changeCity);
    cityName.hidden = !name || !changeCity.hidden;
    cityName.textContent = name;
    updateCredits(mapCredits, cityCreditLinks(state?.cityId, state?.attribution ?? []));
    plannerControls.hidden = !state?.camera || planner?.open === true;
    if (plannerControls.hidden || state?.journeyPlanningAvailable) hidePlannerNotice();
    openPlanner.dataset.available = String(state?.journeyPlanningAvailable === true);
    openPlanner.title = state?.journeyPlanningAvailable ? "" : copy.plannerUnavailable;
    openRoutes.hidden = !state?.canOpenRoutes;
    const legends = state?.routeLegends ?? [];
    routeSelection.hidden = legends.length === 0;
    const legendRows = legends.map((route) => renderRouteLegend(route, copy, actions));
    // Map updates can arrive between pointer down and up. Keep unchanged buttons mounted.
    if (routeLegends.childElementCount !== legendRows.length
      || legendRows.some((row, index) => !row.isEqualNode(routeLegends.children[index]))) {
      replacePreservingFocus(routeLegends, legendRows);
    }
    renderLocation(state?.location);
    const vehicleText = vehicleStatusText(copy, state?.vehicleStatus);
    vehicleStatus.hidden = !vehicleText;
    if (vehicleStatus.textContent !== (vehicleText ?? "")) vehicleStatus.textContent = vehicleText ?? "";
    mapControls.hidden = planner?.open === true || (openRoutes.hidden && routeSelection.hidden && vehicleStatus.hidden);
    const summaryText = vehicleSummaryText(copy, state?.vehicleStatus, state?.vehicleRoutes ?? []);
    if (vehicleSummary.textContent !== summaryText) vehicleSummary.textContent = summaryText;

    if (pickSide) contentStatus.hidden = true;
    else renderContentStatus(state);

    const nextPanelKey = pickSide ? null : planner?.open ? "planner" : state?.arrivals?.stopId ?? null;
    const content = pickSide ? null : planner?.open
      ? renderPlannerPanel(planner, copy, plannerActions, language, expandedPlannerSide, togglePlannerSide)
      : state?.arrivals ? renderArrivals(state.arrivals, copy, actions) : null;
    const panelHeading = content?.querySelector<HTMLElement>("h1") ?? null;
    if (panelHeading) panelHeading.id = "map-panel-heading";
    const plannerForm = !pickSide && planner?.open === true && !planner.showingResults;
    const panelChanged = nextPanelKey !== panelKey || (plannerForm && !map.classList.contains("has-planner-form"));
    const panelHadFocus = mapPanel.contains(document.activeElement);
    mapPanel.hidden = !content;
    map.classList.toggle("has-panel", Boolean(content));
    map.classList.toggle("has-planner-form", plannerForm);
    if (!content) {
      mapPanel.replaceChildren();
      if (panelHadFocus && !pickSide) {
        const previousControl = panelKey === "planner" && !plannerControls.hidden ? openPlanner : mapRegion;
        previousControl.focus({ preventScroll: true });
      }
    } else if (panelChanged) {
      mapPanel.replaceChildren(content);
      mapPanel.scrollTop = 0;
      panelHeading?.focus({ preventScroll: true });
    } else if (!planner?.open || !content.isEqualNode(mapPanel.firstChild)) {
      const focusedSide = mapPanel.querySelector(":focus")?.closest<HTMLElement>("[data-side]")?.dataset.side;
      replacePreservingFocus(mapPanel, [content]);
      if (plannerForm && panelHadFocus && !mapPanel.contains(document.activeElement)) {
        const side = expandedPlannerSide ?? focusedSide;
        const summary = side ? mapPanel.querySelector<HTMLElement>(`[data-testid="planner-${side}-toggle"]`) : panelHeading;
        summary?.focus({ preventScroll: true });
      }
    }
    panelKey = nextPanelKey;
    setScreen("map", returningFromRoutes && !openRoutes.hidden ? openRoutes : panelHeading ?? mapRegion);
    mapPointPicker.render(pickSide);
  }

  function hidePlannerNotice() {
    window.clearTimeout(plannerNoticeTimer);
    plannerNotice.hidden = true;
  }

  function renderContentStatus(state: MapState | undefined) {
    const message = stopStatus(copy, state?.content);
    contentStatus.hidden = !message;
    contentStatus.dataset.content = state?.content ?? "loading";
    if (!message) {
      contentStatus.replaceChildren();
      return;
    }
    const children: HTMLElement[] = [status(message, !state || state.content === "loading")];
    if (state?.content === "retryableError" || state?.content === "offline" || state?.content === "offlineStale") {
      const retry = button("secondary-button", copy.retry, actions.retryNearby, "retry-nearby");
      retry.dataset.focusKey = "retry-nearby";
      children.push(retry);
    }
    replacePreservingFocus(contentStatus, children);
  }

  function renderLocation(location: MapState["location"]) {
    locationButton.hidden = !location;
    if (!location) {
      locationStatus.hidden = true;
      return;
    }
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

  function showPlanner(state: PlannerState | undefined) {
    // Reset manual expansion only when the form opens or a saved endpoint changes, as on Android.
    if (state?.open !== planner?.open || (state && planner && (
      !sameEndpoint(state.origin, planner.origin) || !sameEndpoint(state.destination, planner.destination)
    ))) {
      const side = state?.defaultExpandedSide;
      expandedPlannerSide = side === "origin" || side === "destination" ? side : undefined;
    }
    planner = state;
    if (screen === "map") showMap(mapState);
  }

  showLoading();
  return {
    mapElement, showLoading, showStartupError, showCitySelection, showMap, showRoutes, showPlanner, setMapLoading, setMapUnavailable,
    setMapPointReady: mapPointPicker.setReady, mapPickPoint: mapPointPicker.point,
  };
}

function sameEndpoint(next: PlannerEndpoint, previous: PlannerEndpoint) {
  return next.kind === previous.kind && next.point?.latitude === previous.point?.latitude
    && next.point?.longitude === previous.point?.longitude;
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
          recent.append(element("h2", "routes-subheading", copy.routesRecent), recentRouteChips(state.recentRoutes, limitReached, isConfirming, actions));
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

// Same chip as the mobile RecentRoutesSection: the route colour fills it only while the route is selected.
function recentRouteChips(routes: readonly RouteChoice[], limitReached: boolean, confirming: boolean, actions: ViewActions) {
  const list = element("ul", "recent-route-chips");
  for (const route of routes) {
    const row = element("li", "");
    const chip = button("recent-route-chip", "", () => actions.toggleRecentRoute(route.id), "recent-route-option");
    chip.dataset.routeId = route.id;
    chip.dataset.focusKey = `recent-${route.id}`;
    chip.disabled = confirming || (limitReached && !route.selected);
    chip.setAttribute("aria-pressed", String(route.selected));
    const label = element("span", "recent-route-chip-label", route.shortName);
    if (route.selected) applyRouteColors(label, route.color, route.textColor);
    chip.append(label);
    row.append(chip);
    list.append(row);
  }
  return list;
}

function routeOptions(copy: Copy, routes: readonly RouteChoice[], limitReached: boolean, confirming: boolean, actions: ViewActions) {
  const list = element("ul", "route-options");
  for (const route of routes) {
    const row = element("li", "");
    const option = button("list-button route-option", "", () => actions.toggleRoute(route.id), "route-option");
    option.dataset.routeId = route.id;
    option.dataset.focusKey = `route-${route.id}`;
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
  const chip = element("div", "map-route-chip");
  applyRouteColors(chip, route.color, route.textColor);
  const focus = button("map-route-focus", "", () => actions.focusRoute(route.routeId), "focus-route");
  focus.dataset.focusKey = `focus-${route.routeId}`;
  focus.setAttribute("aria-pressed", String(route.focused));
  focus.disabled = !route.canFocus;
  const state = routeLineStatus(copy, route.status);
  focus.setAttribute("aria-label", [copy.focusRoute.replace("%1$s", route.label), state].filter(Boolean).join(". "));
  const label = element("span", "map-route-label");
  label.append(element("span", "", route.label));
  if (state) label.append(element("small", "map-route-status", state));
  focus.append(route.status === "loading" ? icon("spinner") : mapIcon("bus"), label);
  const remove = button("map-route-remove", "", () => actions.removeRoute(route.routeId), "remove-route");
  remove.dataset.focusKey = `remove-${route.routeId}`;
  remove.setAttribute("aria-label", copy.removeRoute.replace("%1$s", route.label));
  remove.append(mapIcon("close"));
  chip.append(focus, icon("map-route-divider"), remove);
  row.append(chip);
  if (route.canRetry) {
    const retry = button("map-route-retry", "", () => actions.retryRoute(route.routeId), "retry-route");
    retry.dataset.focusKey = `retry-${route.routeId}`;
    retry.setAttribute("aria-label", copy.retryRoute.replace("%1$s", route.label));
    retry.append(mapIcon("retry"));
    row.append(retry);
  }
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

function renderArrivals(arrivals: ArrivalsState, copy: Copy, actions: ViewActions) {
  const panel = element("div", "panel-content");
  panel.dataset.testid = "arrivals-panel";
  const dismiss = button("back-button", "", actions.dismissArrivals, "dismiss-arrivals");
  dismiss.dataset.focusKey = "dismiss";
  dismiss.append(icon("arrow-left"), element("span", "", copy.closeArrivals));
  panel.append(dismiss, heading(arrivals.stopName));
  if (arrivals.routes.length) {
    const routes = element("section", "stop-routes");
    routes.setAttribute("aria-label", copy.stopRoutes);
    routes.append(element("h2", "panel-subheading", copy.stopRoutes));
    const chips = element("ul", "stop-route-chips");
    for (const route of arrivals.routes) {
      const chip = element("li", "stop-route-chip", route.label);
      chip.dataset.testid = "stop-route-chip";
      chip.dataset.routeId = route.routeId;
      applyRouteColors(chip, route.color, route.textColor);
      if (route.selected) {
        chip.dataset.selected = "true";
        chip.append(element("span", "visually-hidden", `. ${copy.stopRouteSelected}`));
      }
      chips.append(chip);
    }
    routes.append(chips);
    panel.append(routes);
  }

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
      if (arrival.route) {
        const badge = element("span", "route-number", arrival.route);
        badge.dataset.testid = "arrival-route";
        if (arrival.routeColor && arrival.routeTextColor) applyRouteColors(badge, arrival.routeColor, arrival.routeTextColor);
        row.append(badge);
      }
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

function cityCreditLinks(cityId: string | undefined, attribution: readonly Attribution[]) {
  const credits = element("div", "attribution-links");
  if (cityId === "kutaisi" || cityId === "zugdidi") return credits;
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

function updateCredits(container: HTMLElement, credits: HTMLElement) {
  container.hidden = !credits.childElementCount;
  if (container.firstElementChild?.isEqualNode(credits)) return;
  container.replaceChildren(credits);
}
