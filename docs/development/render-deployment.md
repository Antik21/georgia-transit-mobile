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
