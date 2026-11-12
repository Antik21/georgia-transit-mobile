import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { access, mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import {
  browserFixture, fallbackMapColor, fixtureAttribution, fixtureCityId, fixtureControl, fixtureHeadsign,
  fixtureRouteColor, fixtureRouteId, fixtureShapePath, fixtureStopId, fixtureTileColor, otherCityId,
  otherRouteId, otherShapePath, fixtureVehiclesPath, vehicleHeadlightColor, userLocationDotColor, stopPinGlyphColor,
} from './browser-fixtures.mjs';
import { plannerSmoke } from './planner-smoke.mjs';

const dist = resolve(process.argv[2] ?? fileURLToPath(new URL('../dist', import.meta.url)));
const width = 390;
const height = 844;
const bffRequests = [];
const workerResponses = [];
const errors = [];

await access(join(dist, 'index.html'));
const profile = await mkdtemp(join(tmpdir(), 'georgia-transit-browser-smoke-'));
const { server, origin, fixture } = await startFixtureServer();
const chrome = process.env.CHROME_BIN || (process.platform === 'darwin'
  ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  : 'google-chrome');
const devTools = startChrome(chrome, profile);

try {
  const { targetId } = await devTools.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await devTools.send('Target.attachToTarget', { targetId, flatten: true });
  const send = (method, params) => devTools.send(method, params, sessionId);

  devTools.onEvent(({ method, params }) => {
    if (method === 'Runtime.exceptionThrown') {
      errors.push(params.exceptionDetails.exception?.description ?? params.exceptionDetails.text);
    }
    if (method === 'Fetch.requestPaused') {
      const intercept = new URL(params.request.url).origin === origin
        ? send('Fetch.continueRequest', { requestId: params.requestId })
        : fulfill(send, params, fixture);
      intercept.catch((error) => errors.push(error.message));
    }
  });

  await send('Runtime.enable');
  await send('Fetch.enable', { patterns: [{ urlPattern: 'https://*/*' }, { urlPattern: 'http://*/*' }] });
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile: true });
  await send('Emulation.setTouchEmulationEnabled', { enabled: true });
  const navigation = await send('Page.navigate', { url: `${origin}/` });
  assert.equal(navigation.errorText, undefined, navigation.errorText);

  const evaluate = async (expression) => {
    const { result, exceptionDetails } = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (exceptionDetails) throw new Error(exceptionDetails.exception?.description ?? exceptionDetails.text);
    return result.value;
  };

  const visible = (selector) => `Boolean(document.querySelector(${JSON.stringify(selector)})?.getClientRects().length)`;
  const testId = (id) => `[data-testid="${id}"]`;
  const cityOption = (id) => `${testId('city-option')}[data-city-id="${id}"]`;
  const stopOption = `${testId('stop-option')}[data-stop-id="${fixtureStopId}"]`;
  const routeOption = (id) => `${testId('route-option')}[data-route-id="${id}"]`;
  const routePressed = (id, value) => `document.querySelector(${JSON.stringify(routeOption(id))})?.getAttribute('aria-pressed') === '${value}'`;
  const routesScreen = visible(testId('routes-screen'));
  const mapScreen = visible(testId('map-screen'));
  const backVisible = 'window.__telegramSmoke?.backVisible === true';
  const arrivalsPanel = visible(testId('arrivals-panel'));
  const arrivalRow = `document.querySelector('${testId('arrival-row')}')`;
  const etaVisible = `${arrivalsPanel} && ${arrivalRow}?.textContent.includes(${JSON.stringify(fixtureHeadsign)}) && ${arrivalRow}?.querySelector('[data-kind="minutes"]')?.textContent.trim().startsWith('4 ')`;

  async function waitFor(label, condition) {
    const expiresAt = Date.now() + 15_000;
    let lastError;
    while (Date.now() < expiresAt) {
      try {
        if (await (typeof condition === 'function' ? condition() : evaluate(condition))) {
          console.log(`PASS ${label}`);
          return;
        }
      } catch (error) {
        lastError = error.message;
      }
      await delay(100);
    }
    const summary = await evaluate('document.body?.innerText?.slice(0, 400)').catch(() => 'unavailable');
    throw new Error(`${label} did not complete. Visible text: ${summary}${lastError ? ` (${lastError})` : ''}`);
  }

  // Runs `body` over the map canvas pixels of a fresh screenshot; it sees pixels, width, height and scale.
  async function analyzeMap(body) {
    const { data } = await send('Page.captureScreenshot', { format: 'png', fromSurface: true });
    return evaluate(`(async () => {
      const bounds = document.querySelector('.maplibregl-canvas')?.getBoundingClientRect();
      if (!bounds?.width || !bounds.height) return false;
      const image = new Image();
      image.src = ${JSON.stringify(`data:image/png;base64,${data}`)};
      await image.decode();
      const scale = image.naturalWidth / window.innerWidth;
      const left = Math.max(0, Math.ceil(bounds.left * scale));
      const top = Math.max(0, Math.ceil(bounds.top * scale));
      const right = Math.min(image.naturalWidth, Math.floor(bounds.right * scale));
      const bottom = Math.min(image.naturalHeight, Math.floor(bounds.bottom * scale));
      const canvas = document.createElement('canvas');
      canvas.width = right - left;
      canvas.height = bottom - top;
      if (!canvas.width || !canvas.height) return false;
      const context = canvas.getContext('2d');
      context.drawImage(image, -left, -top);
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
      const { width } = canvas;
      const toPage = (x, y) => ({ x: (x + left) / scale, y: (y + top) / scale });
      ${body}
    })()`);
  }

  function mapContainsColor(color, minimumPixels) {
    return analyzeMap(`
      const [red, green, blue] = ${JSON.stringify(color)};
      let count = 0;
      for (let i = 0; i < pixels.length; i += 4) {
        if (Math.abs(pixels[i] - red) <= 3 && Math.abs(pixels[i + 1] - green) <= 3 && Math.abs(pixels[i + 2] - blue) <= 3) {
          if (++count >= ${minimumPixels}) return true;
        }
      }
      return false;`);
  }

  // Pixels of a colour, as a count and their centroid in page coordinates.
  function colorCentroid(color) {
    return analyzeMap(`
      const [red, green, blue] = ${JSON.stringify(color)};
      let count = 0, sumX = 0, sumY = 0;
      for (let i = 0; i < pixels.length; i += 4) {
        if (Math.abs(pixels[i] - red) <= 3 && Math.abs(pixels[i + 1] - green) <= 3 && Math.abs(pixels[i + 2] - blue) <= 3) {
          count++;
          sumX += (i / 4) % width;
          sumY += Math.floor(i / 4 / width);
        }
      }
      return count ? { count, ...toPage(sumX / count, sumY / count) } : { count };`);
  }

  // The translucent pulse is the only thing that mixes the route colour into the plain fixture tiles.
  function pulseArea() {
    return analyzeMap(`
      const route = ${JSON.stringify(fixtureRouteColor)};
      const tile = ${JSON.stringify(fixtureTileColor)};
      const delta = route.map((value, index) => value - tile[index]);
      const length = Math.hypot(...delta);
      let count = 0, sumX = 0, sumY = 0;
      for (let i = 0; i < pixels.length; i += 4) {
        const offset = [0, 1, 2].map((index) => pixels[i + index] - tile[index]);
        const alpha = offset.reduce((sum, value, index) => sum + value * delta[index], 0) / length / length;
        if (alpha < 0.05 || alpha > 0.6) continue;
        if (Math.hypot(...offset.map((value, index) => value - alpha * delta[index])) > 4) continue;
        count++;
        sumX += (i / 4) % width;
        sumY += Math.floor(i / 4 / width);
      }
      return count ? { count, ...toPage(sumX / count, sumY / count) } : { count };`);
  }

  async function pulseAreas(samples) {
    const areas = [];
    for (let index = 0; index < samples; index++) {
      areas.push((await pulseArea()).count);
      await delay(150);
    }
    return areas;
  }

  async function cursorAt(point) {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await delay(100);
    return evaluate(`document.querySelector('.maplibregl-canvas').style.cursor`);
  }

  async function tapAt(point) {
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] });
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  }

  async function stopPinPoint() {
    const glyph = await colorCentroid(stopPinGlyphColor);
    return glyph.count >= 20 ? glyph : null;
  }

  async function tap(selector) {
    const point = await evaluate(`(() => {
      const element = document.querySelector(${JSON.stringify(selector)});
      if (!element || element.disabled) return null;
      element.scrollIntoView({ block: 'nearest' });
      const rect = element.getBoundingClientRect();
      return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
    })()`);
    assert.ok(point, `No enabled touch target: ${selector}`);
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] });
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  }

  await waitFor('Telegram host ready and expanded', 'window.__telegramSmoke?.ready === 1 && window.__telegramSmoke?.expand === 1');
  await waitFor('City options fit the mobile viewport', `${visible(cityOption(fixtureCityId))} && ${visible(cityOption(otherCityId))} && window.innerWidth === ${width} && document.documentElement.scrollWidth === ${width}`);
  await tap(cityOption(fixtureCityId));
  await waitFor('City selection enables confirmation', `document.querySelector('${testId('confirm-city')}')?.disabled === false`);
  await tap(testId('confirm-city'));
  await waitFor('Selected city opens map with nearby stop', `${mapScreen} && ${visible(stopOption)}`);
  await waitFor('MapLibre worker loads from the production build', () => workerResponses.includes(200));
  await waitFor('MapLibre renders pixels from the fixture raster tiles', () => mapContainsColor(fixtureTileColor, 2_000));
  await waitFor('Tile source attribution is visible', `${visible('.maplibregl-ctrl-attrib')} && document.querySelector('.maplibregl-ctrl-attrib').textContent.includes(${JSON.stringify(fixtureAttribution)})`);
  await tap(stopOption);
  await waitFor('ETA panel shows BFF arrival', etaVisible);
  await waitFor('Telegram BackButton handles the ETA panel', `${backVisible} && typeof window.__telegramSmoke.backHandler === 'function'`);
  await evaluate('window.__telegramSmoke.backHandler(); true');
  await waitFor(
    'Telegram back dismisses ETA',
    `!${arrivalsPanel} && ${visible(testId('map-screen'))} && window.__telegramSmoke?.backVisible === false`,
  );
  await waitFor('The native stop pin draws on the MapLibre canvas', async () => Boolean(await stopPinPoint()));
  assert.equal(await evaluate(`document.querySelectorAll('.maplibregl-marker').length`), 0, 'Stops must not be DOM markers');
  await tapAt(await stopPinPoint());
  await waitFor('Tapping the canvas pin opens the same BFF arrival', etaVisible);
  await tap(testId('dismiss-arrivals'));
  await waitFor('In-app back returns to nearby stops', `!${arrivalsPanel} && ${visible(stopOption)}`);
  let pinPoint;
  await waitFor('The stop pin settles after the ETA camera move', async () => Boolean(pinPoint = await stopPinPoint()));

  await tap(testId('open-routes'));
  await waitFor('Route picker loads both catalog entries', `${routesScreen} && ${visible(routeOption(fixtureRouteId))} && ${visible(routeOption(otherRouteId))}`);
  await tap(testId('route-search'));
  await send('Input.insertText', { text: 'D1' });
  await waitFor('Route search filters by route number', `${visible(routeOption(fixtureRouteId))} && !${visible(routeOption(otherRouteId))}`);
  await tap(routeOption(fixtureRouteId));
  await waitFor('Tapping a route updates the selection draft', routePressed(fixtureRouteId, true));
  await tap(testId('confirm-routes'));
  await waitFor('Confirm returns to the map', `${mapScreen} && !${routesScreen}`);
  await waitFor('The selected route geometry draws on the MapLibre canvas', () => mapContainsColor(fixtureRouteColor, 75));
  await waitFor('A stop on the selected route switches to the route-coloured pin', async () => !(await stopPinPoint()));
  await tapAt(pinPoint);
  await waitFor('Tapping the route-coloured pin opens ETA with a route selected', etaVisible);
  await tap(testId('dismiss-arrivals'));
  await waitFor('ETA closes back to the map with the route selected', `!${arrivalsPanel} && ${visible(stopOption)}`);
  await waitFor('The live vehicle badge draws on the MapLibre canvas', () => mapContainsColor(vehicleHeadlightColor, 6));
  await waitFor('Live vehicles show no degraded status', `!${visible(testId('vehicle-status'))}`);
  const vehicleSummary = `(document.querySelector('${testId('vehicle-summary')}')?.textContent ?? '')`;
  await waitFor('Screen readers get the live vehicle count per route', `${vehicleSummary}.includes('D1: 1, live')`);
  fixtureControl.staleVehicles = true;
  await waitFor('A stale BFF page shows the older-positions status', `${visible(testId('vehicle-status'))} && ${vehicleSummary}.includes('showing older positions')`);
  fixtureControl.staleVehicles = false;
  await waitFor('Fresh positions clear the degraded status', `!${visible(testId('vehicle-status'))} && ${vehicleSummary}.includes('D1: 1, live')`);

  const animatedAreas = await pulseAreas(10);
  assert.ok(Math.min(...animatedAreas) > 0 && Math.max(...animatedAreas) > Math.min(...animatedAreas) * 1.5,
    `The vehicle pulse must grow and shrink, got areas ${animatedAreas}`);
  console.log('PASS The vehicle pulse animates under the badge');
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await delay(300);
  const staticAreas = await pulseAreas(4);
  assert.ok(Math.min(...staticAreas) > 1_000 && Math.max(...staticAreas) - Math.min(...staticAreas) <= Math.max(...staticAreas) * 0.05,
    `Reduced motion must keep a static pulse ring, got areas ${staticAreas}`);
  console.log('PASS Reduced motion freezes the vehicle pulse');

  const vehicleCenter = await pulseArea();
  const nearBadge = { x: vehicleCenter.x, y: vehicleCenter.y + 30 };
  const awayFromBadge = { x: vehicleCenter.x, y: vehicleCenter.y + 60 };
  assert.equal(await cursorAt(nearBadge), 'pointer', 'A point beside the badge must fall inside the 48 px vehicle target');
  assert.equal(await cursorAt(awayFromBadge), '', 'A point well clear of the badge must not target the vehicle');
  console.log('PASS The vehicle target extends to 48×48 around the badge');
  await send('Emulation.setEmulatedMedia', { features: [] });

  const start = await colorCentroid(vehicleHeadlightColor);
  assert.ok(start.count > 0, 'The vehicle headlights must be visible before it moves');
  fixtureControl.vehicleAdvanced = true;
  const travelled = [];
  const travelExpiresAt = Date.now() + 25_000;
  while (Date.now() < travelExpiresAt) {
    const current = await colorCentroid(vehicleHeadlightColor);
    if (current.count) travelled.push(Math.hypot(current.x - start.x, current.y - start.y));
    const settled = travelled.length > 8 && travelled.slice(-8).every((distance) => Math.abs(distance - travelled.at(-1)) < 1);
    if (settled && travelled.at(-1) > 10) break;
    await delay(200);
  }
  const finalDistance = travelled.at(-1) ?? 0;
  assert.ok(finalDistance > 10, `The vehicle must reach its next polled position, travelled ${finalDistance}`);
  assert.ok(travelled.some((distance) => distance > finalDistance * 0.2 && distance < finalDistance * 0.8),
    `The vehicle must glide between polls instead of jumping, distances ${travelled.map(Math.round)}`);
  console.log('PASS The vehicle glides between polled positions');

  await tap(testId('open-routes'));
  const vehicleRequests = () => bffRequests.filter((path) => path === fixtureVehiclesPath).length;
  // Lets a poll already in flight when the picker opened land before the snapshot.
  await delay(1_000);
  const pollsBeforePicker = vehicleRequests();
  await delay(9_000);
  assert.equal(vehicleRequests(), pollsBeforePicker, 'Vehicle polling must pause while the route picker covers the map');
  console.log('PASS Vehicle polling pauses behind the route picker');
  await waitFor('Route picker restores the committed selection', `${routePressed(fixtureRouteId, true)} && ${routePressed(otherRouteId, false)}`);
  await tap(routeOption(fixtureRouteId));
  await waitFor('Tapping a selected route removes it from the draft', routePressed(fixtureRouteId, false));
  await tap(routeOption(otherRouteId));
  await waitFor('The second route can be added to the draft', routePressed(otherRouteId, true));
  await tap(testId('cancel-routes'));
  await waitFor('Cancel returns to the map with the committed route', async () =>
    await evaluate(`${mapScreen} && !${routesScreen}`) && await mapContainsColor(fixtureRouteColor, 75));
  await waitFor('Vehicle polling resumes after the route picker closes', () => vehicleRequests() > pollsBeforePicker);

  const timeOrigin = await evaluate('performance.timeOrigin');
  await send('Page.reload');
  await waitFor('Reload restores the selected city', `performance.timeOrigin !== ${timeOrigin} && ${mapScreen} && ${visible(stopOption)}`);
  await waitFor('Reload restores the selected route geometry', () => mapContainsColor(fixtureRouteColor, 75));
  await tap(testId('open-routes'));
  await waitFor('Only the confirmed route persists after reload', `${routePressed(fixtureRouteId, true)} && ${routePressed(otherRouteId, false)}`);
  await tap(routeOption(otherRouteId));
  await waitFor('A route can be added to the draft before Telegram back', routePressed(otherRouteId, true));
  await waitFor('Telegram BackButton handles the route picker', `${backVisible} && typeof window.__telegramSmoke.backHandler === 'function'`);
  await evaluate('window.__telegramSmoke.backHandler(); true');
  await waitFor('Telegram back closes the route picker', `${mapScreen} && !${routesScreen} && window.__telegramSmoke?.backVisible === false`);
  await tap(testId('open-routes'));
  await waitFor('Telegram back discards the route draft', `${routePressed(fixtureRouteId, true)} && ${routePressed(otherRouteId, false)}`);
  await tap(testId('cancel-routes'));
  await waitFor('Closing the route picker restores the map', `${mapScreen} && !${routesScreen}`);
  await tap(testId('change-city'));
  await waitFor('Change-city action reopens city choices', `${visible(testId('city-screen'))} && ${visible(cityOption(otherCityId))}`);

  fixtureControl.failStyle = true;
  await tap(cityOption(otherCityId));
  await waitFor('Second city enables confirmation', `document.querySelector('${testId('confirm-city')}')?.disabled === false`);
  await tap(testId('confirm-city'));
  await waitFor('Second city opens a fresh map', `${mapScreen} && ${visible('.maplibregl-canvas')} && !${visible(stopOption)}`);
  await waitFor('An unavailable BFF style falls back to the local map style', () => mapContainsColor(fallbackMapColor, 2_000));
  fixtureControl.failStyle = false;
  await evaluate(`window.dispatchEvent(new Event('online')); true`);
  await waitFor('The BFF style is retried when the network returns', () => mapContainsColor(fixtureTileColor, 2_000));
  await waitFor('Second city requests its own nearby stops', () => bffRequests.includes(`/v1/cities/${otherCityId}/stops/nearby`));

  const planner = plannerSmoke({ send, evaluate, waitFor, tap, visible, testId, mapContainsColor, bffRequests });
  await planner.pointToPoint(() => evaluate('window.__telegramSmoke.backHandler(); true'));
  await planner.unavailable();

  const locationButton = testId('my-location');
  const permissionIs = (value) => `document.querySelector('${locationButton}')?.dataset.permission === '${value}'`;
  await waitFor('My location starts by asking for access', `${visible(locationButton)} && ${permissionIs('notDetermined')}`);
  await tap(locationButton);
  await waitFor('A Telegram denial asks the user to open settings', `${permissionIs('settingsRequired')} && ${visible(testId('location-status'))}`);
  await tap(locationButton);
  await waitFor('The settings action opens Telegram location settings', 'window.__telegramSmoke.settingsOpened === 1');
  await evaluate(`(() => {
    const location = window.__telegramSmoke.location;
    location.granted = true;
    location.fix = { latitude: 41.6505, longitude: 41.6395, horizontal_accuracy: 25 };
    document.dispatchEvent(new Event('visibilitychange'));
    return true;
  })()`);
  await waitFor('Returning from settings grants access and locates the user', `${permissionIs('granted')} && !${visible(testId('location-status'))}`);
  await waitFor('The user location dot draws on the MapLibre canvas', () => mapContainsColor(userLocationDotColor, 20));

  // Outside Telegram the same flow runs on navigator.geolocation.
  fixtureControl.telegram = false;
  await devTools.send('Browser.grantPermissions', { origin, permissions: ['geolocation'] });
  await send('Emulation.setGeolocationOverride', { latitude: 41.6512, longitude: 41.6402, accuracy: 30 });
  const browserTimeOrigin = await evaluate('performance.timeOrigin');
  await send('Page.reload');
  await waitFor('A plain browser reopens the map without Telegram', `performance.timeOrigin !== ${browserTimeOrigin} && !window.Telegram && ${mapScreen}`);
  await waitFor('Previously granted browser geolocation locates the user on open', `${permissionIs('granted')} && !${visible(testId('location-status'))}`);
  await waitFor('The browser location dot draws on the MapLibre canvas', () => mapContainsColor(userLocationDotColor, 20));
  await planner.fromMyLocation({ latitude: 41.6512, longitude: 41.6402 });
  await devTools.send('Browser.setPermission', { origin, permission: { name: 'geolocation' }, setting: 'denied' });
  await evaluate(`document.dispatchEvent(new Event('visibilitychange')); true`);
  await waitFor('The browser reports the denied location', permissionIs('denied'));
  await planner.deniedLocation();

  for (const path of ['/v1/cities', '/v1/map/style.json', `/v1/cities/${fixtureCityId}/stops/nearby`, `/v1/cities/${fixtureCityId}/stops/${fixtureStopId}/arrivals`, fixtureShapePath]) {
    assert.ok(bffRequests.includes(path), `Expected a BFF request for ${path}`);
  }
  assert.ok(workerResponses.every((status) => status === 200), 'MapLibre worker requests must serve the built asset');
  assert.ok(bffRequests.some((path) => path.startsWith('/v1/map/tiles/')), 'Expected a BFF request for a raster tile');
  assert.ok(!bffRequests.includes(otherShapePath), 'Cancel must not request geometry for an uncommitted route');
  assert.ok(bffRequests.includes(fixtureVehiclesPath), 'Expected a BFF request for live vehicles');
  assert.deepEqual(errors, [], 'Browser reported uncaught errors');
  console.log('Telegram Mini App Chromium smoke passed');
} catch (error) {
  if (errors.length) console.error('Browser errors:', errors);
  throw error;
} finally {
  await devTools.close();
  await new Promise((done) => server.close(done));
  await rm(profile, { recursive: true, force: true });
}

async function startFixtureServer() {
  let origin;
  let fixture;
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, origin).href;
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const { status, type, body } = await fixture({ url, method: request.method, body: Buffer.concat(chunks).toString('utf8') });
      if (/\/maplibre-gl-worker[^/]*\.m?js$/.test(new URL(url).pathname)) workerResponses.push(status);
      response.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' }).end(body);
    } catch (error) {
      errors.push(error.message);
      response.writeHead(500).end();
    }
  });
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', done);
  });
  origin = `http://127.0.0.1:${server.address().port}`;
  fixture = browserFixture(dist, bffRequests, origin);
  return { server, origin, fixture };
}

async function fulfill(send, { requestId, request }, fixture) {
  const response = await fixture(request);
  await send('Fetch.fulfillRequest', {
    requestId,
    responseCode: response.status,
    responseHeaders: [{ name: 'Content-Type', value: response.type }, { name: 'Cache-Control', value: 'no-store' }],
    body: Buffer.from(response.body).toString('base64'),
  });
}

function startChrome(executable, profile) {
  const args = [
    '--headless=new', '--remote-debugging-pipe', `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-dev-shm-usage', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
  ];
  if (process.getuid?.() === 0) args.push('--no-sandbox');
  const processHandle = spawn(executable, args, { stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'] });
  const pending = new Map();
  let sequence = 0;
  let buffer = '';
  let stderr = '';
  let failure;
  let onEvent = () => {};

  function fail(error) {
    failure ??= error;
    for (const { reject } of pending.values()) reject(failure);
    pending.clear();
  }

  processHandle.stderr.setEncoding('utf8').on('data', (chunk) => { stderr = (stderr + chunk).slice(-1000); });
  processHandle.on('error', fail);
  processHandle.stdio[3].on('error', fail);
  processHandle.stdio[4].on('error', fail);
  const closed = new Promise((done) => {
    processHandle.on('close', (code, signal) => {
      fail(new Error(`Chrome closed (${code ?? signal}). ${stderr.trim()}`));
      done();
    });
  });
  processHandle.stdio[4].setEncoding('utf8').on('data', (chunk) => {
    const messages = (buffer + chunk).split('\0');
    buffer = messages.pop();
    for (const message of messages) {
      if (!message) continue;
      const payload = JSON.parse(message);
      const response = pending.get(payload.id);
      if (response) {
        pending.delete(payload.id);
        if (payload.error) response.reject(new Error(payload.error.message));
        else response.resolve(payload.result);
      } else if (payload.method) {
        onEvent(payload);
      }
    }
  });

  return {
    onEvent(handler) { onEvent = handler; },
    send(method, params, sessionId) {
      if (failure) return Promise.reject(failure);
      const id = ++sequence;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new Error(`Chrome timed out handling ${method}. ${stderr.trim()}`));
        }, 12_000);
        pending.set(id, {
          resolve(result) { clearTimeout(timer); resolve(result); },
          reject(error) { clearTimeout(timer); reject(error); },
        });
        processHandle.stdio[3].write(`${JSON.stringify({ id, method, params, sessionId })}\0`);
      });
    },
    async close() {
      processHandle.kill();
      await Promise.race([closed, delay(2_000, undefined, { ref: false })]);
    },
  };
}
