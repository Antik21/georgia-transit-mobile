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
  setPlannerMode(mode: string): void;
  retryPlanner(): void;
}

const modes = ["fastest", "lessWalking", "fewerTransfers"] as const;

/** The planner replaces the stop list in the map panel while it is open. */
export function renderPlannerPanel(state: PlannerState, copy: Copy, actions: PlannerActions, language: string) {
  const panel = element("div", "panel-content planner-panel");
  panel.dataset.testid = "planner-panel";
  const close = button("back-button", "", actions.closePlanner, "planner-close");
  close.dataset.focusKey = "planner-close";
  close.append(icon("arrow-left"), element("span", "", copy.plannerClose));
  panel.append(close, heading(copy.plannerTitle), endpoints(state, copy, actions), modeChips(state, copy, actions));

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

function modeChips(state: PlannerState, copy: Copy, actions: PlannerActions) {
  const group = element("div", "planner-modes");
  group.setAttribute("role", "group");
  group.setAttribute("aria-label", copy.plannerModes);
  const labels = { fastest: copy.plannerModeFastest, lessWalking: copy.plannerModeLessWalking, fewerTransfers: copy.plannerModeFewerTransfers };
  for (const mode of modes) {
    const chip = button("planner-chip", labels[mode], () => actions.setPlannerMode(mode), "planner-mode");
    chip.dataset.mode = mode;
    chip.dataset.focusKey = `planner-mode-${mode}`;
    chip.setAttribute("aria-pressed", String(state.mode === mode));
    group.append(chip);
  }
  return group;
}

function optionCard(option: PlannerOption, copy: Copy, actions: PlannerActions, language: string) {
  const row = element("li", "");
  const card = button("planner-option", "", () => actions.selectPlannerOption(option.id), "planner-option");
  card.dataset.optionId = option.id;
  card.dataset.focusKey = `planner-option-${option.id}`;
  card.setAttribute("aria-pressed", String(option.selected));
  const summary = element("span", "planner-option-summary");
  summary.append(
    element("strong", "planner-option-total", format(copy.plannerTotal, option.totalMinutes)),
    element("span", "planner-option-arrival", copy.plannerArrival.replace("%1$s", clock(option.arrivalEpochMillis, language))),
  );
  const timing = { live: copy.plannerTimingLive, mixed: copy.plannerTimingMixed, estimated: copy.plannerTimingEstimated }[option.timing ?? ""];
  if (timing) summary.append(element("span", "planner-option-timing", timing));
  const legs = element("span", "planner-legs");
  for (const leg of option.legs) legs.append(legRow(leg, copy));
  card.append(summary, legs);
  row.append(card);
  return row;
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
    row.append(icon("planner-walk-icon"), element("span", "planner-leg-detail", `${role}: ${parts.filter(Boolean).join(" · ")}`));
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
