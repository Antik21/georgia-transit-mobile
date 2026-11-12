import type { getCopy } from "./copy";
import type { PlannerEndpoint, PlannerLeg, PlannerOption, PlannerState } from "./contracts";
import { applyRouteColors, button, element, heading, icon, status } from "./dom";

type Copy = ReturnType<typeof getCopy>;

export interface PlannerActions {
  closePlanner(): void;
  pickPointFor(side: string): void;
  useMyLocationFor(side: string): void;
  swapPlanner(): void;
  clearPlanner(): void;
  selectPlannerOption(id: string): void;
  retryPlanner(): void;
}

/** The planner replaces the stop list in the map panel while it is open. */
export function renderPlannerPanel(state: PlannerState, copy: Copy, actions: PlannerActions, language: string) {
  const panel = element("div", "panel-content planner-panel");
  panel.dataset.testid = "planner-panel";
  const close = button("back-button", "", actions.closePlanner, "planner-close");
  close.dataset.focusKey = "planner-close";
  close.append(icon("arrow-left"), element("span", "", copy.plannerClose));
  panel.append(close, heading(copy.plannerTitle), endpoints(state, copy, actions));

  const message = statusText(state, copy);
  if (message) {
    const note = status(message, state.status === "loading" || (state.status === "locationNeeded" && state.reason === "locating"));
    note.dataset.testid = "planner-status";
    panel.append(note);
  }
  if (state.status === "locationNeeded" && state.side) {
    const side = state.side;
    panel.append(button("secondary-button", copy.plannerPickOnMap, () => actions.pickPointFor(side), "planner-pick-instead"));
  }
  if (state.retryable || state.status === "empty" || state.stale) {
    panel.append(button("secondary-button", copy.retry, actions.retryPlanner, "planner-retry"));
  }
  if (state.options.length) {
    const list = element("ul", "planner-options");
    list.setAttribute("aria-label", copy.plannerTitle);
    for (const option of state.options) list.append(optionCard(option, copy, actions, language));
    panel.append(list);
  }
  const disclaimer = element("p", "planner-disclaimer", copy.plannerDisclaimer);
  disclaimer.dataset.testid = "planner-disclaimer";
  panel.append(disclaimer);
  return panel;
}

function endpoints(state: PlannerState, copy: Copy, actions: PlannerActions) {
  const section = element("div", "planner-endpoints");
  for (const [side, endpoint, letter, label] of [
    ["origin", state.origin, "A", copy.plannerOrigin],
    ["destination", state.destination, "B", copy.plannerDestination],
  ] as const) {
    const row = element("div", "planner-endpoint");
    row.dataset.side = side;
    const picking = state.status === "pickingPoint" && state.side === side;
    const badge = element("span", `planner-endpoint-badge planner-endpoint-${side}`, letter);
    badge.setAttribute("aria-hidden", "true");
    const text = element("span", "planner-endpoint-text");
    const value = element("span", "planner-endpoint-value", endpointLabel(endpoint, copy));
    value.dataset.testid = `planner-${side}-value`;
    text.append(element("small", "list-detail", label), value);
    if (picking) text.append(element("small", "planner-endpoint-hint", copy.plannerPickHint));
    const pick = button("planner-chip", copy.plannerPickOnMap, () => actions.pickPointFor(side), `planner-pick-${side}`);
    pick.dataset.focusKey = `planner-pick-${side}`;
    pick.setAttribute("aria-pressed", String(picking));
    const mine = button("planner-chip", copy.plannerUseMyLocation, () => actions.useMyLocationFor(side), `planner-my-location-${side}`);
    mine.dataset.focusKey = `planner-mine-${side}`;
    mine.setAttribute("aria-pressed", String(endpoint.kind === "myLocation"));
    const buttons = element("div", "planner-endpoint-actions");
    buttons.append(pick, mine);
    row.append(badge, text, buttons);
    section.append(row);
  }
  const tools = element("div", "planner-tools");
  const swap = button("planner-chip", "⇅", actions.swapPlanner, "planner-swap");
  swap.setAttribute("aria-label", copy.plannerSwap);
  swap.title = copy.plannerSwap;
  swap.dataset.focusKey = "planner-swap";
  const clear = button("planner-chip", copy.plannerClear, actions.clearPlanner, "planner-clear");
  clear.dataset.focusKey = "planner-clear";
  tools.append(swap, clear);
  section.append(tools);
  return section;
}

function optionCard(option: PlannerOption, copy: Copy, actions: PlannerActions, language: string) {
  const row = element("li", "");
  const card = button("planner-option", "", () => actions.selectPlannerOption(option.id), "planner-option");
  card.dataset.optionId = option.id;
  card.dataset.focusKey = `planner-option-${option.id}`;
  card.setAttribute("aria-pressed", String(option.selected));
  // Labels are text inside the button, so they are part of its accessible name, never colour alone.
  if (option.labels.length) {
    const labels = element("span", "planner-labels");
    for (const label of option.labels) {
      const pill = element("span", "planner-label", labelText(copy, label.key));
      pill.dataset.testid = "planner-label";
      pill.dataset.label = label.key;
      applyRouteColors(pill, label.color, label.textColor);
      labels.append(pill, document.createTextNode(" "));
    }
    card.append(labels);
  }
  const summary = element("span", "planner-option-summary");
  summary.append(
    element("strong", "planner-option-total", format(copy.plannerTotal, option.totalMinutes)),
    element("span", "planner-option-arrival", copy.plannerArrival.replace("%1$s", clock(option.arrivalEpochMillis, language))),
  );
  const timing = { live: copy.plannerTimingLive, mixed: copy.plannerTimingMixed, estimated: copy.plannerTimingEstimated }[option.timing ?? ""];
  if (timing) summary.append(element("span", "planner-option-timing", timing));
  const legs = element("span", "planner-legs");
  for (const leg of option.legs) legs.append(legRow(leg, copy));
  card.append(summary, itinerary(option.legs, copy), legs);
  if (option.nearestEtas.length) {
    const nearest = element("span", "planner-nearest",
      copy.plannerNearest.replace("%1$s", option.nearestEtas.map((minutes) => format(copy.plannerMinutes, minutes)).join(", ")));
    nearest.dataset.testid = "planner-nearest";
    card.append(nearest);
  }
  row.append(card);
  return row;
}

/** One compact row of transport glyphs: walk → [bus 11] → [bus 7] → walk, with a spoken equivalent. */
function itinerary(legs: readonly PlannerLeg[], copy: Copy) {
  const row = element("span", "planner-itinerary");
  row.dataset.testid = "planner-itinerary";
  const visual = element("span", "planner-itinerary-icons");
  visual.setAttribute("aria-hidden", "true");
  const spoken: string[] = [];
  legs.forEach((leg, index) => {
    if (index) visual.append(element("span", "planner-itinerary-arrow", "→"));
    if (leg.kind === "ride") {
      const pill = element("span", "planner-route-pill");
      pill.dataset.testid = "planner-route-pill";
      applyRouteColors(pill, leg.routeColor ?? "", leg.routeTextColor ?? "");
      pill.append(glyph("bus"), document.createTextNode(leg.routeLabel ?? "?"));
      visual.append(pill);
      const template = { metro: copy.plannerSummaryMetro, tram: copy.plannerSummaryTram, ferry: copy.plannerSummaryFerry }[leg.routeMode ?? ""]
        ?? copy.plannerSummaryBus;
      spoken.push(template.replace("%1$s", leg.routeLabel ?? "?"));
    } else {
      visual.append(glyph("walk"));
      spoken.push(copy.plannerSummaryWalk);
    }
  });
  const text = spoken.join(copy.plannerThen);
  row.append(visual, element("span", "visually-hidden", text.charAt(0).toUpperCase() + text.slice(1) + "."));
  return row;
}

const glyphPaths = {
  bus: "M6 3h12a3 3 0 0 1 3 3v10a2 2 0 0 1-1 1.7V20a1 1 0 0 1-1 1h-1a1 1 0 0 1-1-1v-1H7v1a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-2.3A2 2 0 0 1 3 16V6a3 3 0 0 1 3-3zm0 3v5h12V6zm1 8.5a1.5 1.5 0 1 0 0 .01zm10 0a1.5 1.5 0 1 0 0 .01z",
  walk: "M13.5 5.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM9.8 8.9 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3A7.3 7.3 0 0 0 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6z",
};

function glyph(kind: keyof typeof glyphPaths) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("class", `planner-glyph planner-glyph-${kind}`);
  svg.dataset.testid = `planner-glyph-${kind}`;
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  path.setAttribute("d", glyphPaths[kind]);
  path.setAttribute("fill", "currentColor");
  svg.append(path);
  return svg;
}

function labelText(copy: Copy, key: string) {
  return { optimal: copy.plannerLabelOptimal, fastest: copy.plannerLabelFastest, leastWalking: copy.plannerLabelLeastWalking,
    noTransfers: copy.plannerLabelNoTransfers }[key] ?? key;
}

function legRow(leg: PlannerLeg, copy: Copy) {
  const row = element("span", `planner-leg planner-leg-${leg.kind}`);
  row.dataset.testid = "planner-leg";
  if (leg.kind === "ride") {
    const badge = element("span", "route-number", leg.routeLabel ?? "?");
    applyRouteColors(badge, leg.routeColor ?? "", leg.routeTextColor ?? "");
    const parts = [leg.stopCount != null ? format(copy.plannerStops, leg.stopCount) : null, format(copy.plannerMinutes, leg.minutes)];
    const detail = element("span", "planner-leg-detail", parts.filter(Boolean).join(" · "));
    if (leg.waitMinutes != null) {
      detail.append(element("small", "planner-leg-wait",
        format(leg.waitLive ? copy.plannerWaitLive : copy.plannerWaitEstimate, leg.waitMinutes)));
    }
    row.append(badge, detail);
  } else {
    const role = { access: copy.plannerWalkAccess, transfer: copy.plannerWalkTransfer, egress: copy.plannerWalkEgress }[leg.role ?? ""]
      ?? copy.plannerWalkDirect;
    const parts = [leg.meters != null ? format(copy.plannerMeters, leg.meters) : null, format(copy.plannerMinutes, leg.minutes)];
    const walkIcon = icon("planner-walk-icon");
    walkIcon.dataset.testid = "planner-leg-walk-icon";
    walkIcon.append(glyph("walk"));
    row.append(walkIcon, element("span", "planner-leg-detail", `${role}: ${parts.filter(Boolean).join(" · ")}`));
  }
  return row;
}

function statusText(state: PlannerState, copy: Copy) {
  if (state.refreshing) return copy.plannerRefreshing;
  if (state.stale) return copy.plannerStale;
  switch (state.status) {
    case "pickingPoint": return null;
    case "endpointsIncomplete": return copy.plannerIncomplete;
    case "loading": return copy.plannerLoading;
    case "empty": return copy.plannerEmpty;
    case "unavailable": return copy.plannerUnavailable;
    case "alreadyThere":
      return copy.plannerAlreadyThere.replace("%1$d", String(state.walkMeters ?? 0)).replace("%2$d", String(state.walkMinutes ?? 1));
    case "locationNeeded":
      return state.reason === "approximate" ? copy.plannerLocationApproximate
        : state.reason === "locating" ? copy.plannerLocationLocating : copy.plannerLocationPermission;
    case "error":
      return { network: copy.plannerErrorNetwork, busy: copy.plannerErrorBusy, rejected: copy.plannerErrorRejected }[state.reason ?? ""]
        ?? copy.plannerErrorService;
    default: return null;
  }
}

function endpointLabel(endpoint: PlannerEndpoint, copy: Copy) {
  return endpoint.kind === "myLocation" ? copy.plannerMyLocation : endpoint.kind === "point" ? copy.plannerMapPoint : copy.plannerNotSet;
}

function format(template: string, value: number) {
  return template.replace("%1$d", String(value));
}

function clock(epochMillis: number, language: string) {
  return new Intl.DateTimeFormat(language, { hour: "2-digit", minute: "2-digit" }).format(new Date(epochMillis));
}
