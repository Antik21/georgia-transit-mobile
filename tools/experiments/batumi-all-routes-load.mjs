#!/usr/bin/env node

import { performance } from "node:perf_hooks";

const POLL_MS = Number(process.env.BATUMI_EXPERIMENT_POLL_MS ?? 5_000);
const DURATION_MS = Number(process.env.BATUMI_EXPERIMENT_DURATION_MS ?? 120_000);
const DB_URL = "https://batbus.app/api/getDbData";
const LIVE_URL = "https://batbus.app/api/getAllBuses";
const SPEED_URL = "https://api.batbus.app/daadbc5886dd072964db3a93114167e1/speedModel";
const FALLBACK_EFFECTIVE_KMH = 12.4;
const effectiveKmh = new Map([
  ["60acde9ffcc7a224160c587c", 11.2], ["648994879327c728200b3f52", 10.6],
  ["5ed2bd4f657784b5a98a8c7e", 14.0], ["5ed6077c340f60873ff9e1be", 11.8],
  ["5ed60865340f60873ff9e1bf", 10.5], ["67ae3607e37f0ecf8032068f", 9.5],
  ["5f9f0704eb714303efce5631", 16.7], ["6740347dd04d3a04c35d84e4", 11.2],
  ["67402e01d04d3a04c35d84c2", 10.6], ["5ed60aa5340f60873ff9e1c4", 11.8],
  ["5ed60b25340f60873ff9e1c5", 14.2], ["64901c96e25b40c6e2150b34", 15.9],
  ["5ed60be3340f60873ff9e1c6", 16.3], ["6941c100f141b1861c3958a0", 10.2],
  ["5ed60d70340f60873ff9e1c8", 12.7], ["648996c69327c728200b3f63", 10.7],
  ["615182e95dc186cad86e0fd2", 9.7], ["5ed60e97340f60873ff9e1cb", 13.7],
  ["5ed60f32340f60873ff9e1cc", 14.1], ["675bf5b960b00f8db1965a52", 11.0],
  ["66de91d980df11324f155751", 16.8], ["69429301f141b1861c395bec", 13.9],
  ["67d92ac719ba93b977b83df4", 17.1], ["67d9522119ba93b977b83e6f", 12.4],
  ["6724af35cabac5486baeb625", 15.3], ["66de91d980df11324f155751", 16.8],
]);

if (!Number.isFinite(POLL_MS) || POLL_MS < 1_000 || !Number.isFinite(DURATION_MS) || DURATION_MS < POLL_MS) {
  throw new Error("Experiment duration/poll values are invalid");
}

const sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis));
const radians = (degrees) => degrees * Math.PI / 180;
const haversine = (a, b) => {
  const dLat = radians(b.lat - a.lat);
  const dLon = radians(b.lon - a.lon);
  const value = Math.sin(dLat / 2) ** 2 +
    Math.cos(radians(a.lat)) * Math.cos(radians(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 6_371_000 * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
};

function projectSegment(point, start, end) {
  const xScale = 111_320 * Math.cos(radians(point.lat));
  const yScale = 110_540;
  const ax = (start.lon - point.lon) * xScale;
  const ay = (start.lat - point.lat) * yScale;
  const bx = (end.lon - point.lon) * xScale;
  const by = (end.lat - point.lat) * yScale;
  const dx = bx - ax;
  const dy = by - ay;
  const denominator = dx * dx + dy * dy;
  const ratio = denominator === 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / denominator));
  return { ratio, distance: Math.hypot(ax + ratio * dx, ay + ratio * dy) };
}

function distanceToPolyline(point, geometry) {
  let minimum = Number.POSITIVE_INFINITY;
  for (let index = 0; index + 1 < geometry.length; index += 1) {
    minimum = Math.min(minimum, projectSegment(point, geometry[index], geometry[index + 1]).distance);
  }
  return minimum;
}

function buildRoute(routeId, rawStops, geometry) {
  const groups = new Map();
  for (const [stopId, stop] of Object.entries(rawStops)) {
    const membership = stop.routes?.[routeId];
    if (!membership) continue;
    const status = Number(membership.Status ?? 0);
    const entry = {
      id: stop.BusStopIdGeoGps ?? stopId,
      lat: Number(stop.BusStopLatitude ?? stop.Lat),
      lon: Number(stop.BusStopLongitude ?? stop.Lon),
      order: Number(membership.Order ?? 0),
      status,
      cumulative: 0,
    };
    if (!groups.has(status)) groups.set(status, []);
    groups.get(status).push(entry);
  }
  const chain = [...groups.entries()]
    .sort(([left], [right]) => left - right)
    .flatMap(([, stops]) => stops.sort((left, right) => left.order - right.order));
  let cumulative = 0;
  for (let index = 0; index < chain.length; index += 1) {
    chain[index].cumulative = cumulative;
    if (index + 1 < chain.length) cumulative += haversine(chain[index], chain[index + 1]);
  }
  const total = chain.length > 1 ? cumulative + haversine(chain.at(-1), chain[0]) : cumulative;
  return { routeId, chain, total, geometry };
}

function projectBus(route, bus) {
  const point = { lat: Number(bus.Lat), lon: Number(bus.Lon) };
  const status = Number(bus.Status);
  if (!Number.isFinite(point.lat) || !Number.isFinite(point.lon)) return null;
  if (route.geometry.length > 1 && distanceToPolyline(point, route.geometry) > 150) return null;
  let best = null;
  for (let index = 0; index < route.chain.length; index += 1) {
    const start = route.chain[index];
    const end = route.chain[(index + 1) % route.chain.length];
    if (start.status !== status || end.status !== status) continue;
    const projection = projectSegment(point, start, end);
    if (!best || projection.distance < best.distance) {
      const segmentLength = haversine(start, end);
      best = {
        distance: projection.distance,
        position: start.cumulative + projection.ratio * segmentLength,
      };
    }
  }
  return best;
}

function timeBand(date) {
  const hour = (date.getUTCHours() + 4) % 24;
  if (hour < 8) return "0";
  if (hour < 10) return "1";
  if (hour < 16) return "2";
  if (hour < 19) return "3";
  if (hour < 22) return "4";
  return "5";
}

function integrateMinutes(route, model, fromMeters, distanceMeters, now) {
  const base = (effectiveKmh.get(route.routeId) ?? FALLBACK_EFFECTIVE_KMH) * 1_000 / 60;
  const routeModel = model.routes?.[route.routeId];
  if (model.off || !routeModel || !Number.isFinite(routeModel.total) || Math.abs(routeModel.total - route.total) / route.total > 0.02) {
    return distanceMeters / base;
  }
  const bin = Number(model.bin);
  const multipliers = routeModel.bands?.[timeBand(now)] ?? routeModel.day;
  if (!Array.isArray(multipliers) || !Number.isFinite(bin) || bin <= 0) return distanceMeters / base;
  let position = ((fromMeters % route.total) + route.total) % route.total;
  let remaining = distanceMeters;
  let minutes = 0;
  while (remaining > 0.01) {
    const modelPosition = position / route.total * routeModel.total;
    const index = Math.floor(modelPosition / bin) % multipliers.length;
    const available = Math.max(0.01, bin - (modelPosition % bin)) * route.total / routeModel.total;
    const step = Math.min(remaining, available);
    minutes += step / Math.max(1, base * Number(multipliers[index] ?? 1));
    remaining -= step;
    position = (position + step) % route.total;
  }
  return minutes;
}

function calculateAll(routes, liveByRoute, speedModel, now) {
  let acceptedVehicles = 0;
  let predictions = 0;
  for (const route of routes.values()) {
    for (const bus of liveByRoute[route.routeId] ?? []) {
      const projected = projectBus(route, bus);
      if (!projected) continue;
      acceptedVehicles += 1;
      for (const stop of route.chain) {
        let distance = stop.cumulative - projected.position;
        if (distance < -150) distance += route.total;
        else if (distance < 0) distance = 0;
        const minutes = integrateMinutes(route, speedModel, projected.position, distance, now);
        if (Number.isFinite(minutes)) predictions += 1;
      }
    }
  }
  return { acceptedVehicles, predictions };
}

async function getText(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10_000);
  const started = performance.now();
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const text = await response.text();
    return { text, millis: performance.now() - started, bytes: Buffer.byteLength(text) };
  } finally {
    clearTimeout(timeout);
  }
}

const [catalogResponse, modelResponse] = await Promise.all([getText(DB_URL), getText(SPEED_URL)]);
const catalog = JSON.parse(catalogResponse.text).data;
const speedModel = JSON.parse(modelResponse.text);
const routes = new Map();
for (const routeId of Object.keys(catalog.routesNames)) {
  const geometry = (catalog.routeCoordinatesGrouped[routeId] ?? []).map((point) => ({
    lat: Number(point.lat), lon: Number(point.lon),
  }));
  const route = buildRoute(routeId, catalog.busStops, geometry);
  if (route.chain.length > 1 && route.geometry.length > 1 && route.total > 0) routes.set(routeId, route);
}

const startedAt = performance.now();
const cpuStarted = process.cpuUsage();
const fetchMillis = [];
const computeMillis = [];
let snapshots = 0;
let errors = 0;
let liveBytes = 0;
let peakRss = process.memoryUsage().rss;
let lastCounts = { acceptedVehicles: 0, predictions: 0 };

while (performance.now() - startedAt < DURATION_MS) {
  const cycleStarted = performance.now();
  try {
    const liveResponse = await getText(LIVE_URL);
    const parsed = JSON.parse(liveResponse.text);
    const computeStarted = performance.now();
    lastCounts = calculateAll(routes, parsed.data ?? {}, speedModel, new Date());
    computeMillis.push(performance.now() - computeStarted);
    fetchMillis.push(liveResponse.millis);
    liveBytes += liveResponse.bytes;
    snapshots += 1;
  } catch (error) {
    errors += 1;
    process.stderr.write(`snapshot_error=${error.name}\n`);
  }
  peakRss = Math.max(peakRss, process.memoryUsage().rss);
  const remaining = POLL_MS - (performance.now() - cycleStarted);
  if (remaining > 0) await sleep(remaining);
}

const wallMillis = performance.now() - startedAt;
const cpu = process.cpuUsage(cpuStarted);
const sorted = (values) => [...values].sort((left, right) => left - right);
const percentile = (values, ratio) => {
  if (values.length === 0) return null;
  const ordered = sorted(values);
  return ordered[Math.min(ordered.length - 1, Math.floor(ordered.length * ratio))];
};
const average = (values) => values.length === 0 ? null : values.reduce((sum, value) => sum + value, 0) / values.length;
const averageBytes = snapshots === 0 ? 0 : liveBytes / snapshots;

process.stdout.write(`${JSON.stringify({
  configuredDurationSeconds: DURATION_MS / 1_000,
  actualDurationSeconds: wallMillis / 1_000,
  pollSeconds: POLL_MS / 1_000,
  routes: routes.size,
  stops: Object.keys(catalog.busStops).length,
  snapshots,
  errors,
  latestAcceptedVehicles: lastCounts.acceptedVehicles,
  latestPredictions: lastCounts.predictions,
  averageLiveBytes: averageBytes,
  projectedLiveTrafficMiBPerDay: averageBytes * 86_400 / POLL_MS * 1_000 / 1_048_576,
  fetchMillis: { average: average(fetchMillis), p95: percentile(fetchMillis, 0.95), max: Math.max(...fetchMillis) },
  computeMillis: { average: average(computeMillis), p95: percentile(computeMillis, 0.95), max: Math.max(...computeMillis) },
  processCpuPercentOfOneCore: (cpu.user + cpu.system) / (wallMillis * 1_000) * 100,
  peakRssMiB: peakRss / 1_048_576,
  staticCatalogBytes: catalogResponse.bytes,
  staticSpeedModelBytes: modelResponse.bytes,
}, null, 2)}\n`);
