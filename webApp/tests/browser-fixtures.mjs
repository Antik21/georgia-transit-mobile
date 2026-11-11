import { readFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';

export const origin = 'https://transit.example.test';
export const fixtureCityId = 'demo';
export const otherCityId = 'batumi';
export const fixtureStopId = 'demo:fixture:stop:center';
export const fixtureHeadsign = 'Demo Park';

const position = { latitude: 41.715137, longitude: 44.827096 };
const routeId = 'demo:fixture:route:blue';
const localized = (en) => ({ en, ru: en, ka: en });

const demoCity = {
  id: fixtureCityId,
  name: localized('Demo City'),
  center: position,
  defaultZoom: 15,
  capabilities: {
    routes: true,
    stops: true,
    routeGeometry: false,
    vehiclePositions: false,
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
};

const route = {
  id: routeId,
  providerId: 'browser-test-fixture',
  shortName: 'D1',
  longName: localized('Demo Blue'),
  color: '#0057B8',
  textColor: '#FFFFFF',
  mode: 'bus',
  directions: [],
};

const stop = {
  id: fixtureStopId,
  providerId: 'browser-test-fixture',
  code: 'D001',
  name: localized('Demo Center'),
  position,
  routeIds: [routeId],
  mode: 'bus',
};

const mapStyle = {
  version: 8,
  sources: {},
  layers: [{ id: 'fixture-background', type: 'background', paint: { 'background-color': '#e8efe4' } }],
};

function arrivals() {
  return {
    items: [{
      stopId: fixtureStopId,
      routeId,
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
    const state = { ready: 0, expand: 0, backVisible: false, backHandler: null };
    window.__telegramSmoke = state;
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

export function browserFixture(dist, bffRequests) {
  const root = resolve(dist);
  return async ({ url, method }) => {
    const target = new URL(url);
    if (target.origin === 'https://telegram.org' && target.pathname === '/js/telegram-web-app.js') {
      return respond(200, telegramFixture, contentTypes.get('js'));
    }
    if (target.origin !== origin) return respond(404, 'Fixture has no external network access');
    if (method !== 'GET') return respond(405, 'Fixture only implements GET');

    const path = decodeURIComponent(target.pathname);
    if (path.startsWith('/v1/')) bffRequests.push(path);
    switch (path) {
      case '/v1/cities': return json([demoCity, batumiCity]);
      case '/v1/map/style.json': return json(mapStyle);
      case `/v1/cities/${fixtureCityId}/routes`: return json([route]);
      case `/v1/cities/${fixtureCityId}/routes/${routeId}`: return json(route);
      case `/v1/cities/${fixtureCityId}/stops/nearby`: return json([stop]);
      case `/v1/cities/${fixtureCityId}/stops/${fixtureStopId}/arrivals`: return json(arrivals());
      case `/v1/cities/${otherCityId}/routes`:
      case `/v1/cities/${otherCityId}/stops/nearby`: return json([]);
    }
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
