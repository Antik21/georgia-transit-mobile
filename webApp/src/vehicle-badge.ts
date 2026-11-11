import type { VehicleItem } from "./contracts";

// Mirrors the native VehicleBadgeArtwork geometry so the bus looks the same on every client.
const bodyHeight = 36;
const minBodyWidth = 58;
const bodyRadius = 11;
const outlineWidth = 2.5;
const textPadding = 44;
const font = "bold 17px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
const windowRadius = 4;
const headlightRadius = 2.5;
const headlightColor = "#ffdc7a";
const glassBrightness = 0.45;
const maxLabelLength = 8;
const cssColor = /^#[0-9a-f]{6}$/i;
const styleCache = new Map<string, VehicleBadgeStyle>();

export interface VehicleBadgeStyle {
  readonly key: string;
  readonly label: string;
  readonly color: string;
  readonly textColor: string;
  readonly stale: boolean;
}

type BadgeInput = Pick<VehicleItem, "label" | "color" | "textColor" | "stale">;

// Called for every vehicle on every animation frame, so the sanitised style is memoised by raw input.
export function vehicleBadgeStyle(input: BadgeInput): VehicleBadgeStyle {
  const raw = `${input.label}\u0000${input.color}\u0000${input.textColor}\u0000${input.stale}`;
  let style = styleCache.get(raw);
  if (!style) {
    if (styleCache.size > 1_024) styleCache.clear();
    style = sanitizedStyle(input);
    styleCache.set(raw, style);
  }
  return style;
}

function sanitizedStyle({ label, color, textColor, stale }: BadgeInput): VehicleBadgeStyle {
  const safeLabel = Array.from(label).filter((char) => /[\p{L}\p{N} -]/u.test(char)).join("").trim()
    .slice(0, maxLabelLength) || "?";
  const safeColor = cssColor.test(color) ? color.toLowerCase() : "#455a64";
  const safeText = cssColor.test(textColor) ? textColor.toLowerCase() : "#ffffff";
  return { key: `${safeLabel}|${safeColor}|${safeText}|${stale}`, label: safeLabel, color: safeColor, textColor: safeText, stale };
}

function dimmed(color: string) {
  const value = Number.parseInt(color.slice(1), 16);
  const channel = (shift: number) => Math.floor(((value >> shift) & 0xff) * glassBrightness);
  return `rgb(${channel(16)}, ${channel(8)}, ${channel(0)})`;
}

function roundRect(context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number) {
  context.beginPath();
  // CanvasRenderingContext2D.roundRect is missing before Safari 16, which older Telegram iOS WebViews use.
  context.moveTo(x + radius, y);
  context.arcTo(x + width, y, x + width, y + height, radius);
  context.arcTo(x + width, y + height, x, y + height, radius);
  context.arcTo(x, y + height, x, y, radius);
  context.arcTo(x, y, x + width, y, radius);
  context.closePath();
}

export function drawVehicleBadge(style: VehicleBadgeStyle, pixelRatio: number, flipLabel: boolean): ImageData {
  const measure = document.createElement("canvas").getContext("2d");
  if (!measure) throw new Error("Canvas 2D is unavailable");
  measure.font = font;
  const textWidth = Math.max(measure.measureText(style.label).width, measure.measureText("00").width);
  const width = Math.max(minBodyWidth, textWidth + textPadding);

  const canvas = document.createElement("canvas");
  canvas.width = Math.ceil((width + outlineWidth) * pixelRatio);
  canvas.height = Math.ceil((bodyHeight + outlineWidth) * pixelRatio);
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D is unavailable");
  context.translate(canvas.width / 2, canvas.height / 2);
  context.scale(pixelRatio, pixelRatio);

  const left = -width / 2;
  const top = -bodyHeight / 2;
  const right = width / 2;
  const bottom = bodyHeight / 2;
  roundRect(context, left, top, width, bodyHeight, bodyRadius);
  context.fillStyle = style.color;
  context.fill();
  context.lineWidth = outlineWidth;
  context.strokeStyle = "#ffffff";
  context.stroke();

  context.fillStyle = dimmed(style.color);
  const rearLeft = left + 9;
  const frontRight = right - 5;
  roundRect(context, rearLeft, -11, 8, 22, windowRadius);
  context.fill();
  roundRect(context, frontRight - 11, -13, 11, 26, windowRadius);
  context.fill();
  context.fillStyle = headlightColor;
  for (const y of [top + 7, bottom - 7]) {
    context.beginPath();
    context.arc(right - 2, y, headlightRadius, 0, Math.PI * 2);
    context.fill();
  }

  context.save();
  if (flipLabel) context.rotate(Math.PI);
  context.font = font;
  context.fillStyle = "#ffffff";
  context.textAlign = "center";
  context.textBaseline = "middle";
  context.fillText(style.label, 0, 0);
  context.restore();

  if (style.stale) {
    // A shape cue in addition to the layer opacity, so staleness never relies on colour alone.
    context.save();
    roundRect(context, left, top, width, bodyHeight, bodyRadius);
    context.clip();
    context.globalAlpha = 0.7;
    context.strokeStyle = style.textColor;
    context.lineWidth = 1.4;
    context.beginPath();
    context.moveTo(left, bottom - 3);
    context.lineTo(right, top + 3);
    context.moveTo(left + 8, bottom);
    context.lineTo(right, top + 11);
    context.stroke();
    context.restore();
  }
  return context.getImageData(0, 0, canvas.width, canvas.height);
}
