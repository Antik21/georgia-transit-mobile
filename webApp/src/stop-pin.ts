import type { StopPin } from "./contracts";
import { roundRect } from "./vehicle-badge";

// Geometry and colours are copied from the native StopPinImages.kt / MapLibreMapViewBridge.swift renderers.
const cardEdgeColor = "#d6d8dc";
const cardInset = 0.75;
const badgeHeight = 15;
const badgeTextSize = 9;
const badgeTextPadding = 4;
const clusterRadius = 12;
const clusterStrokeWidth = 2;
const clusterColor = "#264653";
const fontFamily = "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
const cssColor = /^#[0-9a-f]{6}$/i;

function safeColor(color: string, fallback: string) {
  return cssColor.test(color) ? color : fallback;
}

function canvasFor(width: number, height: number, pixelRatio: number) {
  const canvas = document.createElement("canvas");
  canvas.width = Math.ceil(width * pixelRatio);
  canvas.height = Math.ceil(height * pixelRatio);
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D is unavailable");
  context.scale(pixelRatio, pixelRatio);
  return { canvas, context };
}

function drawRouteCard(context: CanvasRenderingContext2D, pin: StopPin) {
  const radius = pin.cardCornerRadius;
  context.fillStyle = cardEdgeColor;
  roundRect(context, 0, 0, pin.cardWidth, pin.cardHeight, radius);
  context.fill();
  context.fillStyle = "#ffffff";
  roundRect(context, cardInset, cardInset, pin.cardWidth - cardInset * 2, pin.cardHeight - cardInset * 2, radius - cardInset);
  context.fill();

  context.textAlign = "center";
  context.textBaseline = "middle";
  for (const badge of pin.badges) {
    context.fillStyle = safeColor(badge.color, "#455a64");
    roundRect(context, pin.badgeX, badge.y, pin.badgeWidth, badgeHeight, pin.badgeCornerRadius);
    context.fill();
    let size = badgeTextSize;
    context.font = `bold ${size}px ${fontFamily}`;
    const labelWidth = context.measureText(badge.label).width;
    if (labelWidth > pin.badgeWidth - badgeTextPadding) {
      size *= (pin.badgeWidth - badgeTextPadding) / labelWidth;
      context.font = `bold ${size}px ${fontFamily}`;
    }
    context.fillStyle = safeColor(badge.textColor, "#ffffff");
    context.fillText(badge.label, pin.badgeX + pin.badgeWidth / 2, badge.y + badgeHeight / 2);
  }
}

function drawPinBody(context: CanvasRenderingContext2D, color: string, glyph: string) {
  context.fillStyle = "#ffffff";
  context.beginPath();
  context.moveTo(-5, 20);
  context.lineTo(5, 20);
  context.lineTo(0, 28);
  context.closePath();
  context.fill();
  roundRect(context, -12, 0, 24, 23, 7);
  context.fill();
  context.fillStyle = color;
  roundRect(context, -10, 2, 20, 19, 5);
  context.fill();

  context.fillStyle = glyph;
  for (const [x, y, width, height, radius] of [[-6, 5, 12, 11, 2], [-5, 15, 3, 3, 1], [2, 15, 3, 3, 1]]) {
    roundRect(context, x, y, width, height, radius);
    context.fill();
  }
  context.fillStyle = color;
  roundRect(context, -4.5, 6.5, 9, 4.5, 1);
  context.fill();
  for (const x of [-3.5, 3.5]) {
    context.beginPath();
    context.arc(x, 13.5, 0.8, 0, Math.PI * 2);
    context.fill();
  }
}

export function drawStopPin(pin: StopPin, pixelRatio: number): ImageData {
  const { canvas, context } = canvasFor(pin.width, pin.height, pixelRatio);
  if (pin.badges.length) {
    context.save();
    context.translate(0, pin.cardTop);
    drawRouteCard(context, pin);
    context.restore();
  }
  context.translate(pin.width / 2, pin.bodyTop);
  context.scale(pin.bodyScale, pin.bodyScale);
  drawPinBody(context, safeColor(pin.color, "#eef1f5"), safeColor(pin.glyphColor, "#344155"));
  return context.getImageData(0, 0, canvas.width, canvas.height);
}

// Native clusters are a plain circle; the count is drawn into the image so no glyph server is needed.
export function drawStopCluster(count: number, pixelRatio: number): ImageData {
  const size = (clusterRadius + clusterStrokeWidth) * 2;
  const { canvas, context } = canvasFor(size, size, pixelRatio);
  context.beginPath();
  context.arc(size / 2, size / 2, clusterRadius + clusterStrokeWidth / 2, 0, Math.PI * 2);
  context.fillStyle = clusterColor;
  context.fill();
  context.lineWidth = clusterStrokeWidth;
  context.strokeStyle = "#ffffff";
  context.stroke();
  const label = count > 99 ? "99+" : String(count);
  context.font = `bold ${label.length > 2 ? 9 : 11}px ${fontFamily}`;
  context.fillStyle = "#ffffff";
  context.textAlign = "center";
  context.textBaseline = "middle";
  context.fillText(label, size / 2, size / 2 + 0.5);
  return context.getImageData(0, 0, canvas.width, canvas.height);
}
