#!/usr/bin/env node
// Handwritten loopback-only BFF fake for DEN-67. It is never packaged with either app.
import http from "node:http";

const port = Number.parseInt(process.env.ROUTE_SELECTION_FIXTURE_PORT ?? "8080", 10);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error("ROUTE_SELECTION_FIXTURE_PORT must be 1..65535");
}

const city = (id, name, latitude, longitude) => ({
  id,
  name: { ru: name, en: name, ka: name },
  center: { latitude, longitude },
  defaultZoom: 13,
  capabilities: {
    routes: true,
    stops: true,
    routeGeometry: false,
    vehiclePositions: false,
    officialArrivals: false,
    tripPlanning: false,
    arrivals: false,
  },
  availability: { readiness: "DEVELOPMENT_FIXTURE", source: "FIXTURE" },
  attribution: [],
});

const cities = [
  city("demo", "Demo City", 41.715137, 44.827096),
  city("tbilisi", "Tbilisi", 41.715137, 44.827096),
];

const route = (cityId, index) => ({
  id: `${cityId}:fixture:route:${index}`,
  providerId: "handwritten-den-67-fixture",
  shortName: `${index}`,
  longName: { ru: `${cityId} route ${index}`, en: `${cityId} route ${index}`, ka: `${cityId} route ${index}` },
  color: "#0057B8",
  textColor: "#FFFFFF",
  mode: "bus",
  directions: [],
});

const demoRoutes = Array.from({ length: 11 }, (_, index) => route("demo", index + 1));
const tbilisiRoutes = [route("tbilisi", 1), route("tbilisi", 2)];
const routesByCity = new Map([
  ["demo", demoRoutes],
  ["tbilisi", tbilisiRoutes],
]);

const send = (response, payload, status = 200) => {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  response.end(JSON.stringify(payload));
};

const server = http.createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  if (request.method !== "GET") return send(response, { error: "method not allowed" }, 405);
  if (url.pathname === "/healthz") {
    return send(response, { status: "ready", mode: "test-only-route-selection-fixture", cities: cities.length, demoRoutes: demoRoutes.length });
  }
  if (url.pathname === "/v1/cities") return send(response, cities);
  const routesMatch = url.pathname.match(/^\/v1\/cities\/([^/]+)\/routes$/);
  if (routesMatch) {
    const routes = routesByCity.get(routesMatch[1]);
    return routes ? send(response, routes) : send(response, { error: "city not found" }, 404);
  }
  if (/^\/v1\/cities\/[^/]+\/stops\/nearby$/.test(url.pathname)) return send(response, []);
  return send(response, { error: "test fixture path not implemented" }, 404);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`test-only route-selection fixture ready on 127.0.0.1:${port}`);
});
