# ADR 0021 — Telegram web client hosting

Status: Proposed

## Context

[ADR 0020](0020-telegram-mini-app-web-client.md) requires the web client and `/v1/` on one HTTPS origin, with `/v1/` forwarded to the Transit BFF. Telegram opens a Mini App only from a public HTTPS URL. The BFF already runs on Render ([render-deployment.md](../development/render-deployment.md)).

Building the client needs a JDK, the Android SDK for Gradle configuration, Yarn Classic and Chrome for the smoke. Render's static-site build environment provides Node but no JDK, and static sites cannot use Docker.

## Decision

- Host `webApp/dist` as the Render static site `antik21-georgia-transit-web`, declared in the existing `render.yaml` Blueprint.
- Render does not build the client. On every push to `main`, after the Quality, Transit BFF, Android, Browser and iOS jobs pass, the `Publish web client` job force-pushes the exact bundle the Browser job built and smoke-tested to the generated branch `web-dist`, as a single commit. The static site deploys from that branch with a no-op build check.
- A Render rewrite forwards `/v1/*` to `https://antik21-georgia-transit-bff.onrender.com/v1/*`. The browser keeps one origin, so the BFF needs no CORS, and the client's existing rebasing of style and tile URLs stays valid.
- Hashed `/assets/*` are cached as immutable; `index.html` is revalidated so a deploy takes effect on the next open. No framing headers are set, because Telegram Web embeds Mini Apps in an iframe.
- The Telegram bot is configured in BotFather with the static site's HTTPS URL. The client needs no bot token, and none is stored in the repository or on Render.

## Consequences

- Static sites are free on Render, but responses count toward the workspace's included outbound bandwidth (Hobby 5 GB, then USD 0.15/GB). Whether proxied `/v1/` responses count is not documented; tiles are the largest share and are already cached for seven days by the BFF.
- Render does not document how an external rewrite forwards query strings, methods, timeouts or caching headers. The first deploy must verify `/v1/` requests with query parameters, map tiles and vehicle polling through the proxy before the bot is published.
- Every `/v1/` request reaches the BFF from Render's proxy, not the user's address. The BFF has no per-client inbound limit today; any future one must not key on the connecting address alone.
- `web-dist` is generated. Manual commits to it are overwritten by the next publish, and the CI job holds `contents: write` only for this step.

## Alternatives

- Building on Render by installing a JDK and Android SDK in the build command: slow, unpinned and outside the CI that already proves the bundle.
- Serving the client from the BFF container: couples web releases to BFF deploys and paid instance resources.
- GitHub Pages or another CDN: needs a separate `/v1/` proxy or BFF CORS, which ADR 0020 rejected.

## Verification

After applying the Blueprint and the first publish, open the static site's URL and check in the network panel that `/v1/cities`, `/v1/cities/batumi/stops/nearby?...`, `/v1/map/style.json`, `/v1/map/tiles/...` and `/v1/cities/batumi/vehicles?routeId=...` return the BFF's responses on the static site's origin. Then run the manual Telegram check from [the Mini App plan](../development/telegram-mini-app.md).
