import type { PlannerSide } from "./contracts";
import type { getCopy } from "./copy";
import { button, element } from "./dom";
import { mapIcon } from "./map-icons";

type Copy = ReturnType<typeof getCopy>;
type ScreenPoint = Readonly<{ x: number; y: number }>;
const letters = { origin: "A", destination: "B" };
const svgNamespace = "http://www.w3.org/2000/svg";

function svgPart(name: string, attributes: Record<string, string>) {
  const part = document.createElementNS(svgNamespace, name);
  for (const [key, value] of Object.entries(attributes)) part.setAttribute(key, value);
  return part;
}

function journeyMarker(side?: PlannerSide) {
  // Same head, halo and tip geometry as JourneyMapPicker.kt.
  const marker = svgPart("svg", { viewBox: "0 0 48 66", class: "journey-marker", "aria-hidden": "true" });
  if (side) marker.classList.add("journey-marker-" + side);
  marker.append(
    svgPart("ellipse", { cx: "24", cy: "56", rx: "20", ry: "9", fill: "currentColor", opacity: "0.18" }),
    svgPart("circle", { cx: "24", cy: "56", r: "3", fill: "currentColor" }),
    svgPart("path", {
      d: "M12.43 33.79 A18 18 0 1 1 35.57 33.79 L24 " + (side ? "56" : "54") + " Z",
      fill: "currentColor", stroke: "#fff", "stroke-width": "3", "stroke-linejoin": side ? "round" : "miter",
    }),
  );
  if (side) {
    const label = svgPart("text", {
      x: "24", y: "20", fill: "#fff", "font-weight": "700", "font-size": "20",
      "text-anchor": "middle", "dominant-baseline": "central",
    });
    label.textContent = letters[side];
    marker.append(label);
  } else {
    marker.append(svgPart("circle", { cx: "24", cy: "20", r: "7", fill: "#fff" }));
  }
  return marker;
}

export function createMapPointPicker(copy: Copy, onBack: () => void, onConfirm: () => void) {
  const header = element("div", "map-point-header");
  const back = button("map-point-back", "", onBack, "map-point-back");
  back.setAttribute("aria-label", copy.plannerPickBack);
  back.append(mapIcon("back"));
  const title = element("h2", "map-point-title");
  header.append(back, title);

  const target = element("div", "map-point-target");
  target.dataset.testid = "map-point-target";
  const footer = element("section", "map-point-footer");
  footer.dataset.testid = "map-point-picker";
  const confirm = button("primary-button map-point-confirm", "", onConfirm, "map-point-confirm");
  const confirmText = element("span", "");
  confirm.append(mapIcon("check"), confirmText);
  footer.append(element("p", "", copy.plannerPickInstructions), confirm);

  const parts = [header, target, footer];
  for (const part of parts) part.hidden = true;
  confirm.disabled = true;
  let side: PlannerSide | undefined;

  return {
    header, target, footer,
    render(value: PlannerSide | undefined) {
      if (side === value) return;
      side = value;
      for (const part of parts) part.hidden = !side;
      if (!side) return;
      title.textContent = side === "origin" ? copy.plannerOrigin : copy.plannerDestination;
      confirmText.textContent = copy.plannerPickConfirm.replace("%1$s", letters[side]);
      target.replaceChildren(journeyMarker(side));
      back.focus({ preventScroll: true });
    },
    setReady(ready: boolean) { confirm.disabled = !ready; },
    point() {
      if (!side || confirm.disabled) return undefined;
      const { x, y } = target.getBoundingClientRect();
      return { x, y };
    },
  };
}

/** The neutral map pin and its action card share the same anchor, as on Android. */
export function renderPlannerPin(
  container: HTMLElement,
  anchor: ScreenPoint,
  onlySide: PlannerSide | undefined,
  copy: Copy,
  actions: { routeFromHere(): void; routeHere(): void },
) {
  const overlay = element("div", "planner-pin-overlay");
  const pin = element("div", "planner-dropped-pin");
  pin.dataset.testid = "planner-dropped-pin";
  pin.style.left = anchor.x + "px";
  pin.style.top = anchor.y + "px";
  pin.append(journeyMarker());
  const card = element("section", onlySide ? "planner-pin-card single" : "planner-pin-card");
  card.dataset.testid = "planner-pin-card";
  const title = onlySide ? (onlySide === "origin" ? copy.plannerOrigin : copy.plannerDestination) : copy.planTrip;
  card.append(element("h2", "", title));
  const choices = element("div", "planner-pin-actions");
  for (const side of onlySide ? [onlySide] : ["origin", "destination"] as const) {
    const from = side === "origin";
    const choice = button(onlySide ? "primary-button planner-pin-choice" : "planner-pin-choice", "",
      from ? actions.routeFromHere : actions.routeHere, from ? "planner-route-from-here" : "planner-route-here");
    if (!onlySide) {
      const badge = element("span", "planner-endpoint-badge planner-endpoint-" + side, letters[side]);
      badge.setAttribute("aria-hidden", "true");
      choice.append(badge);
    }
    choice.append(element("span", "", onlySide ? copy.plannerUsePoint : from ? copy.plannerRouteFromHere : copy.plannerRouteHere));
    choices.append(choice);
  }
  card.append(choices);
  overlay.append(pin, card);
  container.append(overlay);

  const edge = 16;
  const controlLane = 56;
  const markerAboveTip = 56;
  const tailHeight = 10;
  const { width, height } = card.getBoundingClientRect();
  const bottom = container.clientHeight - edge;
  const below = anchor.y + tailHeight - 1;
  const above = anchor.y - markerAboveTip - tailHeight + 1 - height;
  const preferBelow = below + height <= bottom || (above < edge && bottom - below >= anchor.y - edge);
  const idealY = preferBelow ? below : above;
  const y = Math.max(edge, Math.min(idealY, bottom - height));
  const x = Math.max(edge, Math.min(anchor.x - width / 2, container.clientWidth - edge - controlLane - width));
  card.style.left = x + "px";
  card.style.top = y + "px";
  // The tail meets the card only when it fits on the straight part of its edge.
  const tailInset = 27;
  if (idealY === y && anchor.x >= x + tailInset && anchor.x <= x + width - tailInset) {
    const tail = element("div", "planner-pin-tail");
    tail.dataset.pointsUp = String(preferBelow);
    tail.style.left = anchor.x + "px";
    tail.style.top = (preferBelow ? anchor.y : y + height - 1) + "px";
    overlay.append(tail);
  }
  return overlay;
}
