# ADR 0014: Render production deployment

- Status: Accepted
- Date: 2026-09-15

The map-proxy-disabled part of this deployment decision is superseded by
[ADR 0015](0015-public-osm-raster-basemap.md).

## Context

The Transit BFF now has an explicitly reviewed Batumi adapter and durable runtime capability
control. Production must retain accepted control history and schema-drift interlocks across process
restarts and deploys. Render's default filesystem is ephemeral, and its persistent disks require a
paid, single-instance service. The repository is a Kotlin multiplatform monorepo, so the BFF also
needs a reproducible server-only build that does not package mobile code or local configuration.

Production releases must follow successful repository checks without a manual deploy action. No
current Batumi origin requires a credential. Future provider credentials remain server-runtime
secrets and must never enter mobile builds, the Docker image, Blueprint literals, or capability
documents.

## Decision

- Deploy `transitBff` as the Git-backed Docker web service `antik21-georgia-transit-bff` in Render's
  Frankfurt region.
- Build an `installDist` application with Java 17 in a multi-stage Dockerfile whose Temurin base
  images and Ubuntu `gosu` package are pinned to reviewed versions, and run it as the
  non-root `transit` user. A root entrypoint exists only long enough to provision the mounted disk,
  then uses `gosu` and `exec` so the JVM receives platform signals directly.
- Keep the Docker-configured root account available only for Render's authenticated SSH/dashboard
  shell path, with the required private `/root/.ssh` directory. This gives operators a supported
  path to atomically replace the disk-backed capability document; the application JVM remains
  non-root.
- Listen on `0.0.0.0` and use Render's injected `PORT`; `BFF_PORT` remains the explicit local
  override when both variables are present.
- Attach a 1 GB disk at `/app/storage`, use one instance, and store the control document, accepted
  document history, and schema-interlock state below that mount.
- On an empty disk, atomically install the reviewed Batumi-only capability document with mode
  `0600`; create control and state directories with mode `0700`. Existing documents and state are
  validated and reused, never overwritten by a deploy.
- Keep Tbilisi adapters, fixtures, probes, and public metrics disabled in production. Map proxying
  follows ADR 0015.
- Gate automatic deploys from `main` with `autoDeployTrigger: checksPass`. GitHub Actions builds the
  versioned Dockerfile from pinned base inputs and smoke-tests it twice against one Docker volume
  to verify initial provisioning and
  restart persistence.
- Do not create PR preview instances. Applying the Blueprint remains an explicit operator action
  because the web service and disk incur recurring cost.

## Consequences

- Production can expose Batumi through `https://antik21-georgia-transit-bff.onrender.com` after the
  Blueprint is applied and its health check succeeds.
- Capability history and schema-drift latches survive deployments. A new bundled template does not
  silently change an existing production control document; operators must publish a reviewed new
  revision atomically.
- The disk forces a single instance and disables zero-downtime deployments, so a brief service
  interruption is possible during release.
- The selected `0.5c-512mb` instance plus 1 GB disk costs approximately USD 7.25 per month before
  bandwidth at the rates reviewed on 2026-09-15. The service is not created by this repository
  change alone.
- No Render deploy hook or GitHub deployment secret is required. If a future provider needs a
  credential, store it in Render runtime secrets (declared `sync: false` if represented in the
  Blueprint), not in GitHub Actions unless a build-time workflow itself needs it.

## Alternatives considered

- A free Render instance was rejected because persistent disks are unavailable and the control
  plane would lose safety state on every deploy.
- A deploy-hook GitHub Action was rejected because Git-backed `checksPass` already provides the
  required automatic, CI-gated deployment without another long-lived secret.
- Ephemeral capability state was rejected because restart-cleared schema interlocks violate the
  production safety boundary.
- Multiple instances were rejected because one Render disk cannot be attached to more than one
  instance.

## Verification

```text
./gradlew --no-daemon --no-build-cache :transitBff:check :transitBff:installDist
docker build --file transitBff/Dockerfile --tag transit-bff:smoke .
sh transitBff/deploy/render/smoke-test.sh transit-bff:smoke
render blueprints validate
git diff --check
```
