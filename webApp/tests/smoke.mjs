import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { access, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { browserFixture, fixtureCityId, fixtureHeadsign, fixtureStopId, origin, otherCityId } from './browser-fixtures.mjs';

const dist = resolve(process.argv[2] ?? fileURLToPath(new URL('../dist', import.meta.url)));
const width = 390;
const height = 844;
const bffRequests = [];
const errors = [];
const fixture = browserFixture(dist, bffRequests);

await access(join(dist, 'index.html'));
const profile = await mkdtemp(join(tmpdir(), 'georgia-transit-browser-smoke-'));
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
      fulfill(send, params).catch((error) => errors.push(error.message));
    }
  });

  await send('Runtime.enable');
  await send('Fetch.enable', { patterns: [{ urlPattern: 'https://*/*' }, { urlPattern: 'http://*/*' }] });
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile: true });
  await send('Emulation.setTouchEmulationEnabled', { enabled: true });
  const navigation = await send('Page.navigate', { url: `${origin}/` });
  assert.equal(navigation.errorText, undefined, navigation.errorText);

  const evaluate = async (expression) => {
    const { result, exceptionDetails } = await send('Runtime.evaluate', { expression, returnByValue: true });
    if (exceptionDetails) throw new Error(exceptionDetails.exception?.description ?? exceptionDetails.text);
    return result.value;
  };

  const visible = (selector) => `Boolean(document.querySelector(${JSON.stringify(selector)})?.getClientRects().length)`;
  const testId = (id) => `[data-testid="${id}"]`;
  const cityOption = (id) => `${testId('city-option')}[data-city-id="${id}"]`;
  const stopOption = `${testId('stop-option')}[data-stop-id="${fixtureStopId}"]`;
  const mapStop = `${testId('map-stop-marker')}[data-stop-id="${fixtureStopId}"]`;
  const backVisible = 'window.__telegramSmoke?.backVisible === true';
  const arrivalsPanel = visible(testId('arrivals-panel'));
  const arrivalRow = `document.querySelector('${testId('arrival-row')}')`;
  const etaVisible = `${arrivalsPanel} && ${arrivalRow}?.textContent.includes(${JSON.stringify(fixtureHeadsign)}) && ${arrivalRow}?.querySelector('[data-kind="minutes"]')?.textContent.trim().startsWith('4 ')`;

  async function waitFor(label, expression) {
    const expiresAt = Date.now() + 15_000;
    let lastError;
    while (Date.now() < expiresAt) {
      try {
        if (await evaluate(expression)) {
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
  await waitFor('Selected city opens map with nearby stop', `${visible(testId('map-screen'))} && ${visible(stopOption)}`);
  await tap(stopOption);
  await waitFor('ETA panel shows BFF arrival', etaVisible);
  await waitFor('Telegram BackButton handles the ETA panel', `${backVisible} && typeof window.__telegramSmoke.backHandler === 'function'`);
  await evaluate('window.__telegramSmoke.backHandler(); true');
  await waitFor(
    'Telegram back dismisses ETA',
    `!${arrivalsPanel} && ${visible(testId('map-screen'))} && window.__telegramSmoke?.backVisible === false`,
  );
  await waitFor('MapLibre canvas and stop marker are visible', `${visible('.maplibregl-canvas')} && ${visible(mapStop)}`);
  await tap(mapStop);
  await waitFor('Map marker opens the same BFF arrival', etaVisible);
  await tap(testId('dismiss-arrivals'));
  await waitFor('In-app back returns to nearby stops', `!${arrivalsPanel} && ${visible(stopOption)}`);

  const timeOrigin = await evaluate('performance.timeOrigin');
  await send('Page.reload');
  await waitFor('Reload restores the selected city', `performance.timeOrigin !== ${timeOrigin} && ${visible(testId('map-screen'))} && ${visible(stopOption)}`);
  await tap(testId('change-city'));
  await waitFor('Change-city action reopens city choices', `${visible(testId('city-screen'))} && ${visible(cityOption(otherCityId))}`);

  for (const path of ['/v1/cities', '/v1/map/style.json', `/v1/cities/${fixtureCityId}/stops/nearby`, `/v1/cities/${fixtureCityId}/stops/${fixtureStopId}/arrivals`]) {
    assert.ok(bffRequests.includes(path), `Expected a BFF request for ${path}`);
  }
  assert.deepEqual(errors, [], 'Browser reported uncaught errors');
  console.log('Telegram Mini App Chromium smoke passed');
} catch (error) {
  if (errors.length) console.error('Browser errors:', errors);
  throw error;
} finally {
  await devTools.close();
  await rm(profile, { recursive: true, force: true });
}

async function fulfill(send, { requestId, request }) {
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
