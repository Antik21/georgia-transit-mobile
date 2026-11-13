# Render production deployment

The production BFF is defined by the repository-root [`render.yaml`](../../render.yaml). It creates
one paid Docker web service in Frankfurt with a 1 GB persistent disk. The Blueprint intentionally
serves Batumi only; fixtures, Tbilisi adapters, probes, and public metrics remain disabled. The map
proxy uses the public OpenStreetMap raster endpoint with visible attribution and requires no key.

## Before the first deployment

1. Merge the Blueprint, Docker files, and application changes to `main` and confirm all GitHub
   checks pass.
2. Review the recurring cost before applying the Blueprint. The selected `0.5c-512mb` service is the
   current replacement for legacy Starter (USD 7/month), and the 1 GB disk adds USD 0.25/month,
   excluding bandwidth.
3. Open
   `https://dashboard.render.com/blueprint/new?repo=https://github.com/Antik21/georgia-transit-mobile`
   and connect the `Antik21/georgia-transit-mobile` repository if Render asks.
4. Review the service name `antik21-georgia-transit-bff`, branch `main`, Frankfurt region, single
   instance, and `/app/storage` disk, then apply the Blueprint.
5. Wait for the deploy to become live and verify both
   `https://antik21-georgia-transit-bff.onrender.com/healthz` and
   `https://antik21-georgia-transit-bff.onrender.com/v1/cities`. The effective city list must contain
   Batumi as `PRODUCTION_READY` / `REVIEWED_ADAPTER` and no Tbilisi city.

Applying the Blueprint creates paid resources. Do not apply it merely to validate the YAML; use
`render blueprints validate` locally instead.

## Automatic releases

`autoDeployTrigger: checksPass` tracks `main`. A matching BFF or build-configuration change starts a
new Render build only after the Git provider reports the required checks successful. GitHub Actions
builds the same versioned Dockerfile from pinned base inputs and starts it twice with one persistent
test volume. Render then independently rebuilds that Dockerfile for the production deploy.
This exercises Render's injected `PORT`, the `/healthz` gate, initial control-document installation,
POSIX ownership/modes, and reuse of durable control state.
The disk fixtures include directory setuid/setgid/sticky bits. GNU `chmod 0700` preserves
directory setuid/setgid, so the entrypoint uses `chmod 00700` on the storage root and its
private directories to enforce exactly `0700` without modifying stored files.

No `RENDER_DEPLOY_HOOK_URL`, API key, or other GitHub secret is required for this deployment path.
The checked-in Blueprint and Batumi capability document contain no secrets. Future provider
credentials belong in Render's runtime environment (and as `sync: false` Blueprint values), because
mobile artifacts and image layers must never contain them.

The persistent disk disables horizontal scaling and zero-downtime deployments. Keep `numInstances`
at one and expect a brief interruption while Render replaces the instance.

## Capability state lifecycle

At first start, the container creates these process-owned paths:

```text
/app/storage/control                         mode 0700
/app/storage/control/capabilities.json       mode 0600
/app/storage/state                           mode 0700
```

The entrypoint copies the reviewed Batumi document only when the control path does not exist. On
later deploys it validates the physical file, owner, and restrictive mode but never overwrites it.
The BFF writes accepted history and schema-interlock state under `/app/storage/state` with its own
atomic-rename and durability checks.

To change capabilities, prepare a complete reviewed document with a new safe revision, upload it to
a temporary file in `/app/storage/control`, set owner `transit` and mode `0600`, force it to disk,
and atomically rename it to `capabilities.json` from the paid service's authenticated Render
Dashboard shell or SSH session. The image keeps the Docker running account compatible with
Render's shell requirements, while the JVM itself runs as the non-root `transit` user. Follow the
rollback and schema-interlock recovery
rules in [`bff-capability-control.md`](bff-capability-control.md); never edit history files directly.
The service polls for a new document, so a deploy is not required for a kill switch.

## Telegram web client

The same Blueprint declares the free static site `antik21-georgia-transit-web` ([ADR 0021](../adr/0021-telegram-web-hosting.md)). Render does not build it: after every check on `main` passes, the `Publish web client` CI job force-pushes the smoke-tested `webApp/dist` to the generated branch `web-dist`, and the site deploys from that branch.

1. Merge the web client to `main` and wait for the `Publish web client` job. The `web-dist` branch must exist before the site is created, otherwise Render cannot check it out.
2. In the Render Dashboard, open the Blueprint and sync it. Review the new static site: branch `web-dist`, publish path `.`, the `/v1/*` rewrite to the BFF, and the cache headers. Static sites have no instance cost; their traffic counts toward the workspace's included outbound bandwidth.
3. Open `https://antik21-georgia-transit-web.onrender.com/` and check in the browser's network panel that `/v1/cities`, nearby stops with their query string, `/v1/map/style.json`, map tiles and vehicle polling all succeed on that origin. Render does not document how external rewrites forward queries and headers, so this check is required before the bot is published.
4. Create a test bot with BotFather (`/newbot`), then set its menu button or a Mini App (`/newapp`) to the static site's HTTPS URL. The client never needs the bot token; keep it out of the repository and Render.
5. Run the manual Telegram check on Android and iOS from [the Mini App plan](telegram-mini-app.md). Only then point a production bot at the site.

Do not commit to `web-dist`; the next publish replaces it. To roll back, redeploy an earlier `web-dist` deploy from the Render Dashboard or revert the change on `main`.

## Local validation

```text
./gradlew --no-daemon --no-build-cache :transitBff:check :transitBff:installDist
docker build --file transitBff/Dockerfile --tag transit-bff:smoke .
sh transitBff/deploy/render/smoke-test.sh transit-bff:smoke
render blueprints validate
git diff --check
```

The smoke test owns and removes only its uniquely named Docker container and volume. It does not
contact Render or create a cloud resource.
