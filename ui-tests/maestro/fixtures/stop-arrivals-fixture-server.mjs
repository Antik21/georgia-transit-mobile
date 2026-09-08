#!/usr/bin/env node
// Handwritten test-only mobile BFF fake. It binds loopback only and is never bundled with an app.
import http from "node:http";

const port = Number.parseInt(process.env.STOP_ARRIVALS_FIXTURE_PORT ?? "8080", 10);
// Keep Loading observable across Maestro's Android tap-settle window. Callers can still opt in to
// a different bounded delay, but the default must cover the cross-platform smoke contract.
const arrivalDelayMillis = Number.parseInt(process.env.STOP_ARRIVALS_FIXTURE_DELAY_MS ?? "3000", 10);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("STOP_ARRIVALS_FIXTURE_PORT must be 1..65535");
if (!Number.isInteger(arrivalDelayMillis) || arrivalDelayMillis < 50 || arrivalDelayMillis > 5000) {
  throw new Error("STOP_ARRIVALS_FIXTURE_DELAY_MS must be 50..5000");
}

const city = {
  id: "demo",
  name: { ru: "Демо-город", en: "Demo City", ka: "დემო ქალაქი" },
  center: { latitude: 41.715137, longitude: 44.827096 },
  defaultZoom: 13,
  capabilities: {
    routes: true,
    stops: true,
    routeGeometry: false,
    vehiclePositions: false,
    officialArrivals: true,
    tripPlanning: true,
    arrivals: true,
  },
  availability: { readiness: "DEVELOPMENT_FIXTURE", source: "FIXTURE" },
  attribution: [],
};

const route = {
  id: "demo:fixture:route:blue",
  providerId: "handwritten-test-fixture",
  shortName: "D1",
  longName: { ru: "Демо-синий", en: "Demo Blue", ka: "დემო ლურჯი" },
  color: "#0057B8",
  textColor: "#FFFFFF",
  mode: "bus",
  directions: [],
};

const stop = {
  id: "demo:fixture:stop:center",
  providerId: "handwritten-test-center",
  code: "D001",
  name: { ru: "Демо-центр", en: "Demo Center", ka: "დემო ცენტრი" },
  position: { latitude: 41.715137, longitude: 44.827096 },
  routeIds: [route.id],
  mode: "bus",
};

function send(response, payload, status = 200) {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  response.end(JSON.stringify(payload));
}

function arrivals() {
  const observedAt = new Date().toISOString();
  return {
    items: [{
      stopId: stop.id,
      routeId: route.id,
      headsign: { ru: "Демо-парк", en: "Demo Park", ka: "დემო პარკი" },
      expectedInMinutes: 0,
      realtime: true,
      cancelled: false,
      source: "OFFICIAL_REALTIME",
    }],
    source: "OFFICIAL_REALTIME",
    observedAt,
    stale: false,
  };
}

let walkingRequests = 0;

async function walkingEstimate(request, response) {
  if (request.headers["cache-control"] !== "no-store") {
    return send(response, { error: "walking request must be no-store" }, 400);
  }
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  let body;
  try {
    body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    return send(response, { error: "invalid JSON" }, 400);
  }
  const points = [body?.from, body?.to];
  const valid = points.every((point) =>
    Number.isFinite(point?.latitude) && Number.isFinite(point?.longitude)
  ) && ["ka", "en", "ru"].includes(body?.locale);
  if (!valid) return send(response, { error: "invalid walking request" }, 400);
  walkingRequests += 1;
  // Coordinates remain local to this request handler and are never copied into health or logs.
  return send(response, {
    distanceMeters: 400,
    durationSeconds: 300,
    observedAt: new Date().toISOString(),
  });
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  if (request.method === "POST" && url.pathname === "/v1/cities/demo/walking-estimate") {
    return walkingEstimate(request, response);
  }
  if (request.method !== "GET") return send(response, { error: "method not allowed" }, 405);
  switch (url.pathname) {
    case "/healthz":
      // Fixed, flat introspection contract: request bodies and coordinates are never exposed here.
      return send(response, { status: "ready", mode: "test-only-stop-arrivals-fixture", arrivalDelayMillis, walkingRequests });
    case "/v1/cities":
      return send(response, [city]);
    case "/v1/cities/demo/routes":
      return send(response, [route]);
    case "/v1/cities/demo/stops/nearby":
      return send(response, [stop]);
    case "/v1/cities/demo/stops/demo:fixture:stop:center/arrivals":
      return setTimeout(() => send(response, arrivals()), arrivalDelayMillis);
    default:
      return send(response, { error: "test fixture path not implemented" }, 404);
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`test-only stop-arrivals fixture ready on 127.0.0.1:${port}`);
});
