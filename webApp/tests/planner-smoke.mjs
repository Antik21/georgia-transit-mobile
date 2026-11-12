import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { fallbackMapColor, fixtureControl, otherCityId, planRequests, plannerRouteColors } from './browser-fixtures.mjs';

const planPath = `/v1/cities/${otherCityId}/journeys/plan`;
const requestKeys = ['from', 'locale', 'maxTransfers', 'mode', 'to', 'transferBufferSeconds'];

// Shared smoke helpers from smoke.mjs.
export function plannerSmoke({ send, evaluate, waitFor, tap, visible, testId, mapContainsColor, analyzeMap, bffRequests }) {
  const panel = visible(testId('planner-panel'));
  const options = `document.querySelectorAll('${testId('planner-option')}')`;
  const optionText = (index) => `(${options}[${index}]?.textContent ?? '')`;
  const statusText = `(document.querySelector('${testId('planner-status')}')?.textContent ?? '')`;
  const pressed = (selector) => `document.querySelector(${JSON.stringify(selector)})?.getAttribute('aria-pressed') === 'true'`;

  async function mapPoint(x, y) {
    return evaluate(`(() => {
      const bounds = document.querySelector('.maplibregl-canvas').getBoundingClientRect();
      return { x: bounds.left + bounds.width * ${x}, y: bounds.top + bounds.height * ${y} };
    })()`);
  }

  // A held touch without movement, as on a phone; MapLibre must not treat it as a tap on a stop.
  async function longPress(x, y) {
    const point = await mapPoint(x, y);
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] });
    await delay(700);
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  }

  async function longPressAndChoose(x, y, choice) {
    await longPress(x, y);
    await waitFor(`Long press offers ${choice}`, visible(testId(choice)));
    await tap(testId(choice));
  }

  function holdStyle() {
    let release;
    fixtureControl.styleHold = new Promise((resolve) => { release = resolve; });
    return () => {
      fixtureControl.styleHold = null;
      release();
    };
  }

  // Without a camera fitted to the answer its line spans about half the map; fitted, about three quarters.
  async function planCloseTogether() {
    await longPressAndChoose(0.35, 0.5, 'planner-route-here');
    await waitFor('The planner waits for the start point', pressed(testId('planner-pick-origin')));
    await longPress(0.65, 0.5);
    await waitFor('The plan answers', `${options}.length === 2`);
  }

  function framedRoute() {
    return analyzeMap(`
      const [red, green, blue] = ${JSON.stringify(plannerRouteColors.orange)};
      let count = 0, minX = Infinity, maxX = -Infinity;
      for (let i = 0; i < pixels.length; i += 4) {
        if (Math.abs(pixels[i] - red) <= 3 && Math.abs(pixels[i + 1] - green) <= 3 && Math.abs(pixels[i + 2] - blue) <= 3) {
          const x = (i / 4) % width;
          count++;
          minX = Math.min(minX, x);
          maxX = Math.max(maxX, x);
        }
      }
      return count >= 75 && (maxX - minX) / width >= 0.65;`);
  }

  async function closePlanner() {
    await tap(testId('planner-close'));
    await waitFor('The planner closes', `!${panel}`);
  }

  function isRounded(point) {
    return [point.latitude, point.longitude].every((value) => Math.abs(value * 1e4 - Math.round(value * 1e4)) < 1e-6);
  }

  function assertPrivateRequest(request) {
    assert.deepEqual(Object.keys(request).sort(), requestKeys, 'The plan body must not say which side is the device location');
    assert.ok(isRounded(request.from) && isRounded(request.to), `Plan points must be rounded to 4 decimals: ${JSON.stringify(request)}`);
  }

  return {
    /** Point-to-point planning without any location permission, on a city with tripPlanning. */
    async pointToPoint(telegramBack) {
      await longPressAndChoose(0.3, 0.65, 'planner-route-here');
      await waitFor('Route here opens the planner and asks for the start point',
        `${panel} && ${visible(testId('planner-pick-origin'))} && ${pressed(testId('planner-pick-origin'))}`);
      assert.equal(planRequests.length, 0, 'Without a granted location the planner waits for a start point');
      await longPress(0.7, 0.35);
      await waitFor('Choosing A by long press plans and shows the options',
        `${options}.length === 2 && ${optionText(0)}.includes('≈ 21 min') && ${optionText(0)}.includes('Walk to the stop: 300 m · 4 min')
          && ${optionText(0)}.includes('6 stops · 11 min') && ${optionText(0)}.includes('wait 2 min · live bus')
          && ${optionText(1)}.includes('Walk to the transfer') && ${optionText(1)}.includes('wait ≈ 4 min · estimate')
          && ${visible(testId('planner-disclaimer'))}`);
      assert.equal(planRequests.length, 1);
      assertPrivateRequest(planRequests[0]);
      assert.equal(bffRequests.filter((path) => path === planPath).length, 1);
      await waitFor('The selected option draws its route-coloured line on the canvas',
        () => mapContainsColor(plannerRouteColors.orange, 75));

      await tap(`${testId('planner-option')}[data-option-id="batumi:fixture:journey:transfer"]`);
      await waitFor('Selecting another option redraws its lines',
        async () => await evaluate(pressed(`${testId('planner-option')}[data-option-id="batumi:fixture:journey:transfer"]`))
          && await mapContainsColor(plannerRouteColors.yellow, 75));

      await tap(testId('planner-swap'));
      await waitFor('Swap re-plans in the other direction', () => planRequests.length === 2);
      assert.deepEqual([planRequests[1].from, planRequests[1].to], [planRequests[0].to, planRequests[0].from]);
      await waitFor('Swapped options arrive', `${options}.length === 2`);

      await tap(testId('planner-clear'));
      await waitFor('Clear drops the options and the planned lines',
        async () => await evaluate(`${options}.length === 0 && ${statusText}.includes('Press and hold the map')`)
          && !(await mapContainsColor(plannerRouteColors.orange, 75)) && !(await mapContainsColor(plannerRouteColors.yellow, 75)));

      await waitFor('Telegram BackButton handles the planner', `window.__telegramSmoke?.backVisible === true`);
      await telegramBack();
      await waitFor('Telegram back closes the planner', `!${panel} && window.__telegramSmoke?.backVisible === false`);
    },

    /** A plan that lands before the map style has loaded is drawn and framed once the style arrives. */
    async beforeStyleLoads() {
      const release = holdStyle();
      const timeOrigin = await evaluate('performance.timeOrigin');
      await send('Page.reload');
      await waitFor('The map reopens while its style is still loading', `performance.timeOrigin !== ${timeOrigin} && ${visible('.maplibregl-canvas')}`);
      await planCloseTogether();
      release();
      await waitFor('A plan made before the style loaded is drawn and framed', framedRoute);
      await closePlanner();
    },

    /** The same while the style is being replaced: a fallback style switching back to the BFF style. */
    async duringStyleSwap() {
      fixtureControl.failStyle = true;
      const timeOrigin = await evaluate('performance.timeOrigin');
      await send('Page.reload');
      await waitFor('The map falls back to its local style', async () =>
        await evaluate(`performance.timeOrigin !== ${timeOrigin}`) && await mapContainsColor(fallbackMapColor, 2_000));
      const release = holdStyle();
      fixtureControl.failStyle = false;
      await evaluate(`window.dispatchEvent(new Event('online')); true`);
      await planCloseTogether();
      release();
      await waitFor('A plan made during a style swap is drawn and framed', framedRoute);
      await closePlanner();
    },

    async unavailable() {
      fixtureControl.planStatus = 501;
      await longPressAndChoose(0.3, 0.65, 'planner-route-from-here');
      await waitFor('Route from here asks for the destination', pressed(testId('planner-pick-destination')));
      await longPress(0.7, 0.35);
      await waitFor('A 501 answer shows the unavailable state', `${statusText}.includes("isn't available in this city")`);
      fixtureControl.planStatus = 200;
      await tap(testId('planner-close'));
      await waitFor('The close button leaves the planner', `!${panel}`);
    },

    /** With access granted, "Route here" starts at the device location; the BFF only sees a rounded point. */
    async fromMyLocation(expected) {
      const before = planRequests.length;
      await longPressAndChoose(0.15, 0.85, 'planner-route-here');
      await waitFor('Route here starts at my location when access is already granted',
        `${panel} && document.querySelector('${testId('planner-origin-value')}')?.textContent === 'My location' && ${options}.length === 2`);
      const request = planRequests.at(-1);
      assert.equal(planRequests.length, before + 1);
      assertPrivateRequest(request);
      assert.deepEqual(request.from, expected);
      await tap(testId('planner-close'));
      await waitFor('The planner closes', `!${panel}`);
    },

    /** A denied location still leaves point-to-point planning fully usable. */
    async deniedLocation() {
      const before = planRequests.length;
      await longPressAndChoose(0.3, 0.65, 'planner-route-here');
      await waitFor('Without access the start stays empty', `${panel} && ${pressed(testId('planner-pick-origin'))}`);
      await tap(testId('planner-my-location-origin'));
      await waitFor('A denied My location explains itself and offers a map point',
        `${statusText}.includes('Allow location access') && ${visible(testId('planner-pick-instead'))}`);
      await tap(testId('planner-pick-instead'));
      await longPress(0.7, 0.35);
      await waitFor('A map point plans despite the denied location', `${options}.length === 2`);
      assert.equal(planRequests.length, before + 1);
      assertPrivateRequest(planRequests.at(-1));
      await tap(testId('planner-close'));
      await waitFor('The planner closes after the denied flow', `!${panel}`);
    },
  };
}
