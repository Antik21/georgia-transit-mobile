#!/usr/bin/env node
// Handwritten DEN-69 BFF fake. It is loopback-only, test-only, and never bundled with either app.
import http from "node:http";

const port = Number.parseInt(process.env.ROUTE_GEOMETRY_FIXTURE_PORT ?? "8080", 10);
const partialFailure = process.env.ROUTE_GEOMETRY_PARTIAL_FAILURE === "true";
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error("ROUTE_GEOMETRY_FIXTURE_PORT must be 1..65535");
}

const cityId = "demo";
const direction = (routeIndex, name) => ({
  // Public BFF IDs are exactly four opaque colon-separated components. The direction variant is
  // deliberately part of the final component, not a fifth component interpreted by the mapper.
  id: `${cityId}:fixture:direction:${name}-${routeIndex}`,
  name: { ru: name, en: name, ka: name },
  headsign: { ru: `${name} ${routeIndex}`, en: `${name} ${routeIndex}`, ka: `${name} ${routeIndex}` },
});
const colors = ["#0057B8", "#0F766E", "#BE123C", "#7F1D1D", "#14532D", "#1E3A8A", "#854D0E", "#3F6212", "#C2410C", "#A21CAF"];
const routes = Array.from({ length: 10 }, (_, offset) => {
  const index = offset + 1;
  return {
    id: `${cityId}:fixture:route:${index}`,
    providerId: "handwritten-den-69-fixture",
    shortName: `G${index}`,
    longName: { ru: `Линия ${index}`, en: `Route ${index}`, ka: `ხაზი ${index}` },
    color: colors[offset],
    textColor: "#FFFFFF",
    mode: "bus",
    directions: [direction(index, "outbound"), direction(index, "return")],
  };
});
const city = {
  id: cityId,
  name: { ru: "Демо-город", en: "Demo City", ka: "დემო ქალაქი" },
  center: { latitude: 41.715137, longitude: 44.827096 },
  defaultZoom: 13,
  capabilities: {
    routes: true,
    // CitySelection intentionally gates Continue on the baseline stops capability. Keep this
    // test-only city selectable; /stops/nearby below remains a deterministic empty response.
    stops: true,
    routeGeometry: true,
    vehiclePositions: false,
    officialArrivals: false,
    tripPlanning: false,
    arrivals: false,
  },
  availability: { readiness: "DEVELOPMENT_FIXTURE", source: "FIXTURE" },
  attribution: [],
};

function encodePolyline(points, precision) {
  const scale = 10 ** precision;
  let previousLatitude = 0;
  let previousLongitude = 0;
  let encoded = "";
  const append = (delta) => {
    let value = delta < 0 ? ~(delta << 1) : delta << 1;
    while (value >= 0x20) {
      encoded += String.fromCharCode((0x20 | (value & 0x1f)) + 63);
      value >>>= 5;
    }
    encoded += String.fromCharCode(value + 63);
  };
  for (const point of points) {
    const latitude = Math.round(point.latitude * scale);
    const longitude = Math.round(point.longitude * scale);
    append(latitude - previousLatitude);
    append(longitude - previousLongitude);
    previousLatitude = latitude;
    previousLongitude = longitude;
  }
  return encoded;
}

function shape(routeIndex, directionIndex) {
  const precision = directionIndex === 0 ? 5 : 6;
  const baseLatitude = 41.680 + routeIndex * 0.002 + directionIndex * 0.0003;
  const baseLongitude = 44.780 + routeIndex * 0.0015 + directionIndex * 0.0004;
  return {
    format: "encoded_polyline",
    precision,
    value: encodePolyline([
      { latitude: baseLatitude, longitude: baseLongitude },
      { latitude: baseLatitude + 0.0012, longitude: baseLongitude + 0.0020 },
      { latitude: baseLatitude + 0.0021, longitude: baseLongitude + 0.0033 },
    ], precision),
    updatedAt: "2030-01-01T00:00:00Z",
  };
}

const partialDirectionId = routes[0].directions[1].id;
// Intentionally uncapped: the harness requires exactly four calls, so it catches an unexpected
// duplicate or automatic retry. Calls 1..3 are the initial GET plus TransitBffClient's two
// retries; call 4 is the explicit route-legend retry.
let partialDirectionRequests = 0;

function send(response, payload, status = 200) {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  response.end(JSON.stringify(payload));
}

function routeDirectionFromPath(pathname) {
  const segments = pathname.split("/").filter(Boolean);
  if (segments.length !== 8 || segments[0] !== "v1" || segments[1] !== "cities" || segments[3] !== "routes" || segments[5] !== "directions" || segments[7] !== "shape") {
    return null;
  }
  return { city: segments[2], route: segments[4], direction: segments[6] };
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  if (request.method !== "GET") return send(response, { error: "GET only" }, 405);
  if (url.pathname === "/healthz") {
    return send(response, {
      status: "ready",
      mode: "test-only-route-geometry-fixture",
      routes: routes.length,
      directions: routes.length * 2,
      precision5: true,
      precision6: true,
      partialFailure,
      partialDirectionRequests,
      partialFailureMode: partialFailure ? "retryable-upstream-bad-response" : "disabled",
      partialAutomaticFailureAttempts: partialFailure ? 3 : 0,
    });
  }
  if (url.pathname === "/v1/cities") return send(response, [city]);
  if (url.pathname === `/v1/cities/${cityId}/routes`) return send(response, routes);
  if (/^\/v1\/cities\/[^/]+\/stops\/nearby$/.test(url.pathname)) return send(response, []);

  const shapeRequest = routeDirectionFromPath(url.pathname);
  if (shapeRequest?.city === cityId) {
    const routeIndex = routes.findIndex((route) => route.id === shapeRequest.route);
    const directionIndex = routes[routeIndex]?.directions.findIndex((item) => item.id === shapeRequest.direction) ?? -1;
    if (routeIndex < 0 || directionIndex < 0) return send(response, { error: "shape not found" }, 404);
    if (partialFailure && shapeRequest.direction === partialDirectionId) {
      partialDirectionRequests += 1;
      if (partialDirectionRequests <= 3) {
        return send(response, {
          error: {
            code: "UPSTREAM_BAD_RESPONSE",
            message: "controlled initial logical shape load failure",
            requestId: "test-only-den-69-partial-shape",
          },
        }, 502);
      }
    }
    return send(response, shape(routeIndex + 1, directionIndex));
  }
  return send(response, { error: "test fixture path not implemented" }, 404);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`test-only route-geometry fixture ready on 127.0.0.1:${port} (partialFailure=${partialFailure})`);
});
