#!/usr/bin/env node
// Test-only local BFF fake for reproducible vehicle rendering samples.
// It is never bundled with either mobile app and binds loopback only.
import http from "node:http";

const count = Number.parseInt(process.env.VEHICLE_FIXTURE_COUNT ?? "250", 10);
const port = Number.parseInt(process.env.VEHICLE_FIXTURE_PORT ?? "8080", 10);

if (!Number.isInteger(count) || count < 1 || count > 1000) {
  throw new Error("VEHICLE_FIXTURE_COUNT must be an integer in 1..1000");
}

const city = {
  id: "demo",
  name: { ru: "Демо-город", en: "Demo City", ka: "დემო ქალაქი" },
  center: { latitude: 41.715137, longitude: 44.827096 },
  defaultZoom: 13.0,
  capabilities: {
    routes: true,
    stops: true,
    routeGeometry: true,
    vehiclePositions: true,
    officialArrivals: true,
    tripPlanning: true,
    arrivals: true,
  },
  availability: { readiness: "DEVELOPMENT_FIXTURE", source: "FIXTURE" },
  attribution: [],
};

const routeId = "demo:fixture:route:blue";
const directionId = "demo:fixture:direction:blue-outbound";
const routes = [{
  id: routeId,
  providerId: "blue",
  shortName: "D1",
  longName: { ru: "Демо-синяя линия", en: "Demo Blue Line", ka: "დემო ლურჯი ხაზი" },
  color: "#0057B8",
  textColor: "#FFFFFF",
  mode: "bus",
  directions: [{
    id: directionId,
    name: { ru: "В направлении из центра", en: "Outbound", ka: "გასვლა" },
    headsign: { ru: "Демо-парк", en: "Demo Park", ka: "დემო პარკი" },
  }],
}];

function json(response, value, status = 200) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  response.end(JSON.stringify(value));
}

function vehiclePage() {
  const observedAt = new Date().toISOString();
  const phase = Math.floor(Date.now() / 8_000) % 2;
  const items = Array.from({ length: count }, (_, index) => {
    const row = Math.floor(index / 50);
    const column = index % 50;
    return {
      id: `demo:fixture:vehicle:${String(index + 1).padStart(4, "0")}`,
      routeId,
      directionId,
      position: {
        latitude: 41.708 + row * 0.00022 + phase * 0.00001,
        longitude: 44.808 + column * 0.00024 + phase * 0.00001,
      },
      bearing: 90,
      observedAt,
      ageSeconds: 0,
      positionKind: "GPS",
    };
  });
  return { items, observedAt, maxAgeSeconds: 30, stale: false };
}

http.createServer((request, response) => {
  const requestUrl = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  if (request.method !== "GET") return json(response, { error: "GET only" }, 405);

  switch (requestUrl.pathname) {
    case "/healthz":
      return json(response, { status: "ready", mode: "test-only-vehicle-load-fixture", vehicleCount: count });
    case "/v1/cities":
      return json(response, [city]);
    case "/v1/cities/demo/routes":
      return json(response, routes);
    case "/v1/cities/demo/stops/nearby":
      return json(response, []);
    case "/v1/cities/demo/vehicles":
      if (requestUrl.searchParams.get("routeId") !== routeId) {
        return json(response, { error: "expected selected demo route" }, 400);
      }
      return json(response, vehiclePage());
    default:
      return json(response, { error: "test fixture path not implemented" }, 404);
  }
}).listen(port, "127.0.0.1", () => {
  console.log(`test-only vehicle fixture ready on 127.0.0.1:${port} (${count} vehicles)`);
});
