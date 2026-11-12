import { readFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';

export const fixtureCityId = 'demo';
export const otherCityId = 'batumi';
export const fixtureStopId = 'demo:fixture:stop:center';
export const fixtureHeadsign = 'Demo Park';
export const fixtureRouteId = 'demo:fixture:route:blue';
export const otherRouteId = 'demo:fixture:route:violet';
export const fixtureRouteColor = [0, 87, 184];
export const fixtureTileColor = [28, 180, 131];
export const fallbackMapColor = [220, 232, 228];
// Only the vehicle badge's headlights use this colour, so it separates the bus from its route line.
export const vehicleHeadlightColor = [255, 220, 122];
export const userLocationDotColor = [21, 101, 192];
// The glyph of a stop pin outside any selected route (StopRouteHighlightUi in common).
export const stopPinGlyphColor = [52, 65, 85];
export const fixtureVehiclesPath = `/v1/cities/${fixtureCityId}/vehicles`;
export const fixtureAttribution = 'Fixture tiles';
// Toggled by the smoke to exercise the local-style fallback and its recovery.
export const fixtureControl = { failStyle: false, staleVehicles: false, telegram: true, vehicleAdvanced: false, planStatus: 200, styleHold: null };
export const plannerRouteColors = { orange: [228, 87, 46], yellow: [242, 193, 78] };
// Every plan request body, so the smoke can assert rounding and the absence of an origin-kind field.
export const planRequests = [];

const position = { latitude: 41.715137, longitude: 44.827096 };
const localized = (en) => ({ en, ru: en, ka: en });
const direction = (name) => ({
  id: `demo:fixture:direction:${name}`,
  name: localized(name),
  headsign: localized(fixtureHeadsign),
});
const blueDirection = direction('blue');
const violetDirection = direction('violet');
export const fixtureShapePath = `/v1/cities/${fixtureCityId}/routes/${fixtureRouteId}/directions/${blueDirection.id}/shape`;
export const otherShapePath = `/v1/cities/${fixtureCityId}/routes/${otherRouteId}/directions/${violetDirection.id}/shape`;

const demoCity = {
  id: fixtureCityId,
  name: localized('Demo City'),
  center: position,
  defaultZoom: 15,
  capabilities: {
    routes: true,
    stops: true,
    routeGeometry: true,
    vehiclePositions: true,
    officialArrivals: true,
    tripPlanning: false,
    arrivals: true,
  },
  availability: { readiness: 'DEVELOPMENT_FIXTURE', source: 'FIXTURE' },
  attribution: [],
};

const batumiCity = {
  ...demoCity,
  id: otherCityId,
  name: localized('Batumi'),
  center: { latitude: 41.6500, longitude: 41.6390 },
  capabilities: { ...demoCity.capabilities, tripPlanning: true },
};

const plannerRoute = (name, color) => ({
  id: `batumi:fixture:route:${name}`,
  providerId: 'browser-test-fixture',
  shortName: name === 'orange' ? 'B7' : 'B12',
  longName: localized(`Batumi ${name}`),
  color,
  textColor: '#FFFFFF',
  mode: 'bus',
  directions: [{ id: `batumi:fixture:direction:${name}`, name: localized(name), headsign: localized(name) }],
});
const plannerRoutes = [plannerRoute('orange', '#E4572E'), plannerRoute('yellow', '#F2C14E')];

const route = {
  id: fixtureRouteId,
  providerId: 'browser-test-fixture',
  shortName: 'D1',
  longName: localized('Demo Blue'),
  color: '#0057B8',
  textColor: '#FFFFFF',
  mode: 'bus',
  directions: [blueDirection],
};

const otherRoute = {
  ...route,
  id: otherRouteId,
  shortName: 'D2',
  longName: localized('Demo Violet'),
  color: '#7C3AED',
  directions: [violetDirection],
};

const stop = {
  id: fixtureStopId,
  providerId: 'browser-test-fixture',
  code: 'D001',
  name: localized('Demo Center'),
  position,
  routeIds: [fixtureRouteId],
  mode: 'bus',
};

// A 32 × 32 solid green PNG. Its color lets the smoke assert raster pixels reached the canvas.
const tile = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAL0lEQVR4nO3OIQEAAAgDMILQlkr0gxg3E/Or3rmkEhAQEBAQEBAQEBAQEBAQSAceAoxIiGVzOuYAAAAASUVORK5CYII=', 'base64');

// The production BFF advertises tiles on its public hostname; the client must rebase them to its own origin.
function mapStyle() {
  return {
    version: 8,
    sources: {
      fixture: {
        type: 'raster',
        tiles: ['https://bff.example.invalid/v1/map/tiles/{z}/{x}/{y}.png'],
        tileSize: 256,
        attribution: fixtureAttribution,
      },
    },
    layers: [{ id: 'fixture-raster', type: 'raster', source: 'fixture', paint: { 'raster-fade-duration': 0 } }],
  };
}

function routeShape(offset) {
  const points = [[0.0012, -0.0027], [0.0010, 0], [0.0018, 0.0029]]
    .map(([latitude, longitude]) => [position.latitude + latitude + offset, position.longitude + longitude]);
  return { format: 'encoded_polyline', precision: 5, value: encodePolyline(points), updatedAt: '2030-01-01T00:00:00Z' };
}

function encodePolyline(points) {
  let previous = [0, 0];
  let value = '';
  for (const [latitude, longitude] of points) {
    const point = [Math.round(latitude * 100_000), Math.round(longitude * 100_000)];
    for (let index = 0; index < point.length; index++) {
      const delta = point[index] - previous[index];
      let encoded = delta < 0 ? ~(delta << 1) : delta << 1;
      while (encoded >= 0x20) {
        value += String.fromCharCode((0x20 | (encoded & 0x1f)) + 63);
        encoded >>>= 5;
      }
      value += String.fromCharCode(encoded + 63);
    }
    previous = point;
  }
  return value;
}

// Two options shaped like the BFF planner: a direct live ride, and a transfer between two different stops.
function journeyPlan({ from, to }) {
  const start = Date.now();
  const at = (minute) => new Date(start + minute * 60_000).toISOString();
  const shift = (point, latitude, longitude = 0) => ({ latitude: point.latitude + latitude, longitude: point.longitude + longitude });
  const coordinates = (...points) => encodePolyline(points.map(({ latitude, longitude }) => [latitude, longitude]));
  const walk = (departure, arrival, fromPosition, toPosition, distanceMeters, stops = {}) => ({
    departureAt: at(departure), arrivalAt: at(arrival), mode: 'WALK', ...stops, fromPosition, toPosition, distanceMeters,
    geometry: coordinates(fromPosition, toPosition), geometryApproximate: true,
  });
  const ride = (name, departure, arrival, fromPosition, toPosition, stops, waitSeconds, waitBasis, intermediateStopCount) => ({
    departureAt: at(departure), arrivalAt: at(arrival), mode: 'TRANSIT',
    routeId: `batumi:fixture:route:${name}`, directionId: `batumi:fixture:direction:${name}`,
    fromStopId: `batumi:fixture:stop:${stops[0]}`, toStopId: `batumi:fixture:stop:${stops[1]}`, fromPosition, toPosition,
    distanceMeters: 2100, geometry: coordinates(fromPosition, shift(fromPosition, 0, 0.002), toPosition), geometryApproximate: false,
    intermediateStopCount, waitSeconds, waitBasis, ...(waitBasis === 'LIVE_VEHICLE' ? { vehicleId: `batumi:fixture:vehicle:${name}` } : {}),
  });
  const legs = (segments) => segments.filter((segment) => segment.mode === 'TRANSIT').map(({ routeId, directionId, fromStopId, toStopId, departureAt, arrivalAt }) =>
    ({ routeId, directionId, fromStopId, toStopId, departureAt, arrivalAt }));
  const boarding = shift(from, 0.0006);
  const alighting = shift(to, -0.0006);
  const direct = [
    walk(0, 4, from, boarding, 300, { toStopId: 'batumi:fixture:stop:s1' }),
    ride('orange', 6, 17, boarding, alighting, ['s1', 's2'], 120, 'LIVE_VEHICLE', 5),
    walk(17, 21, alighting, to, 220, { fromStopId: 'batumi:fixture:stop:s2' }),
  ];
  const transferFrom = shift(boarding, 0.0004, -0.0015);
  const transferTo = shift(transferFrom, 0.0003, 0.0004);
  const transfer = [
    walk(0, 3, from, boarding, 200, { toStopId: 'batumi:fixture:stop:s1' }),
    ride('yellow', 7, 15, boarding, transferFrom, ['s1', 's3'], 240, 'HEADWAY_ESTIMATE', 2),
    walk(15, 18, transferFrom, transferTo, 180, { fromStopId: 'batumi:fixture:stop:s3', toStopId: 'batumi:fixture:stop:s4' }),
    ride('orange', 20, 26, transferTo, alighting, ['s4', 's2'], 120, 'LIVE_VEHICLE', 3),
    walk(26, 30, alighting, to, 250, { fromStopId: 'batumi:fixture:stop:s2' }),
  ];
  return {
    items: [
      { id: 'batumi:fixture:journey:direct', departureAt: at(0), arrivalAt: at(21), transfers: 0, durationSeconds: 1260,
        walkingDistanceMeters: 520, timingQuality: 'LIVE', legs: legs(direct), segments: direct },
      { id: 'batumi:fixture:journey:transfer', departureAt: at(0), arrivalAt: at(30), transfers: 1, durationSeconds: 1800,
        walkingDistanceMeters: 630, timingQuality: 'MIXED', legs: legs(transfer), segments: transfer },
    ],
    observedAt: at(0),
    source: 'CLIENT_ESTIMATE',
    realtime: false,
    stale: false,
  };
}

function vehicles(routeId) {
  const observedAt = new Date().toISOString();
  return {
    items: routeId !== fixtureRouteId ? [] : [{
      id: 'demo:fixture:vehicle:blue-01',
      routeId: fixtureRouteId,
      directionId: blueDirection.id,
      position: { latitude: position.latitude + 0.0004, longitude: position.longitude + (fixtureControl.vehicleAdvanced ? 0.0024 : 0.0012) },
      bearing: 62,
      observedAt,
      ageSeconds: 0,
      positionKind: 'GPS',
    }],
    observedAt,
    maxAgeSeconds: 30,
    stale: fixtureControl.staleVehicles,
  };
}

function arrivals() {
  return {
    items: [{
      stopId: fixtureStopId,
      routeId: fixtureRouteId,
      headsign: localized(fixtureHeadsign),
      expectedInMinutes: 4,
      realtime: true,
      cancelled: false,
      source: 'OFFICIAL_REALTIME',
    }],
    source: 'OFFICIAL_REALTIME',
    observedAt: new Date().toISOString(),
    stale: false,
  };
}

const telegramFixture = `
  (() => {
    const state = { ready: 0, expand: 0, backVisible: false, backHandler: null, settingsOpened: 0 };
    window.__telegramSmoke = state;
    // Mirrors Bot API 8.0: the first getLocation prompts, and the answer sticks until settings change it.
    const location = { inited: false, requested: false, granted: false, allow: false, fix: null };
    state.location = location;
    const locationManager = {
      get isInited() { return location.inited; },
      get isLocationAvailable() { return true; },
      get isAccessRequested() { return location.requested; },
      get isAccessGranted() { return location.granted; },
      init(callback) { location.inited = true; callback?.(); return this; },
      getLocation(callback) {
        if (!location.requested) { location.requested = true; location.granted = location.allow; }
        setTimeout(() => callback(location.granted ? location.fix : null), 50);
        return this;
      },
      openSettings() { state.settingsOpened += 1; return this; },
    };
    const backButton = {
      get isVisible() { return state.backVisible; },
      show() { state.backVisible = true; return this; },
      hide() { state.backVisible = false; return this; },
      onClick(handler) { state.backHandler = handler; return this; },
      offClick(handler) { if (state.backHandler === handler) state.backHandler = null; return this; },
    };
    window.Telegram = { WebApp: {
      platform: 'android',
      version: '9.0',
      colorScheme: 'light',
      themeParams: { bg_color: '#ffffff', text_color: '#18201b' },
      viewportHeight: 844,
      viewportStableHeight: 844,
      safeAreaInset: { top: 0, bottom: 0, left: 0, right: 0 },
      contentSafeAreaInset: { top: 0, bottom: 0, left: 0, right: 0 },
      isVersionAtLeast: () => true,
      ready() { state.ready += 1; },
      expand() { state.expand += 1; },
      onEvent() {},
      offEvent() {},
      setBackgroundColor() {},
      setHeaderColor() {},
      BackButton: backButton,
      LocationManager: locationManager,
    } };
  })();
`;

const contentTypes = new Map([
  ['html', 'text/html; charset=utf-8'],
  ['css', 'text/css; charset=utf-8'],
  ['js', 'text/javascript; charset=utf-8'],
  ['mjs', 'text/javascript; charset=utf-8'],
  ['json', 'application/json; charset=utf-8'],
  ['svg', 'image/svg+xml'],
  ['wasm', 'application/wasm'],
  ['woff2', 'font/woff2'],
]);

const respond = (status, body = '', type = 'text/plain; charset=utf-8') => ({ status, body, type });
const json = (body, status = 200) => respond(status, JSON.stringify(body), contentTypes.get('json'));

export function browserFixture(dist, bffRequests, origin) {
  const root = resolve(dist);
  return async ({ url, method, body }) => {
    const target = new URL(url);
    if (target.origin === 'https://telegram.org' && target.pathname === '/js/telegram-web-app.js') {
      return respond(200, fixtureControl.telegram ? telegramFixture : '', contentTypes.get('js'));
    }
    if (target.origin !== origin) return respond(404, 'Fixture has no external network access');
    const path = decodeURIComponent(target.pathname);
    if (method === 'POST' && path === `/v1/cities/${otherCityId}/journeys/plan`) {
      bffRequests.push(path);
      const request = JSON.parse(body);
      planRequests.push(request);
      if (fixtureControl.planStatus !== 200) {
        return json({ error: { code: 'CAPABILITY_NOT_AVAILABLE', message: 'Trip planning is unavailable', requestId: 'req-1' } },
          fixtureControl.planStatus);
      }
      return json(journeyPlan(request));
    }
    if (method !== 'GET') return respond(405, 'Fixture only implements GET and the plan POST');

    if (path.startsWith('/v1/')) bffRequests.push(path);
    switch (path) {
      case '/v1/cities': return json([demoCity, batumiCity]);
      case '/v1/map/style.json':
        await fixtureControl.styleHold;
        return fixtureControl.failStyle ? json({ error: 'Style unavailable' }, 503) : json(mapStyle());
      case `/v1/cities/${fixtureCityId}/routes`: return json([route, otherRoute]);
      case `/v1/cities/${fixtureCityId}/routes/${fixtureRouteId}`: return json(route);
      case `/v1/cities/${fixtureCityId}/routes/${otherRouteId}`: return json(otherRoute);
      case fixtureShapePath: return json(routeShape(0));
      case otherShapePath: return json(routeShape(-0.003));
      case `/v1/cities/${fixtureCityId}/stops/nearby`: return json([stop]);
      case fixtureVehiclesPath: return json(vehicles(target.searchParams.get('routeId')));
      case `/v1/cities/${fixtureCityId}/stops/${fixtureStopId}/arrivals`: return json(arrivals());
      case `/v1/cities/${otherCityId}/routes`: return json(plannerRoutes);
      case `/v1/cities/${otherCityId}/stops/nearby`: return json([]);
    }
    if (/^\/v1\/map\/tiles\/\d+\/\d+\/\d+\.png$/.test(path)) return respond(200, tile, 'image/png');
    if (path.startsWith('/v1/')) return json({ error: 'Fixture path is not implemented' }, 404);
    if (path === '/favicon.ico') return respond(204);

    const asset = resolve(root, path === '/' ? './index.html' : `.${path}`);
    if (!asset.startsWith(`${root}${sep}`)) return respond(404);
    try {
      const body = await readFile(asset);
      return respond(200, body, contentTypes.get(asset.split('.').at(-1)) ?? 'application/octet-stream');
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      return respond(404, 'Build asset not found');
    }
  };
}
