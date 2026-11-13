# Geo Transit landing page

Static business-card site for Geo Transit: plain HTML, CSS and JavaScript with no build step and
no dependencies. Russian is the default language; English and Georgian are available from the
switch in the header (or `?lang=en`, `?lang=ka`). Light and dark themes follow the system, and the
header button overrides it.

## Run locally

```sh
python3 -m http.server 4180 --directory landing
```

Then open `http://127.0.0.1:4180/`. Any static file server works; there is nothing to build.

## Store and bot links

Android, iOS and Telegram links are not published yet. Fill them in `LINKS` at the top of
[main.js](main.js); while a value is `null`, the page shows a "link will appear here" placeholder
instead of a fake badge.

## Screenshots

`assets/screens/*.webp` are real captures of the web client at 390×844 CSS px, encoded at 780 px
wide. The live map, arrivals and routes come from production. The A→B planner was captured
against a local BFF build with Batumi `tripPlanning` enabled, because the production capability
was switched off at capture time (2026-11-13). Replace a file with a same-named 780×1688 WebP to
update it.

## Hosting

Published by the `antik21-georgia-transit-landing` static site in [render.yaml](../render.yaml):
the `landing/` folder is served as is from `main` (no build step) after the required checks pass,
and only changes under `landing/` trigger a deploy. The default address is
`https://antik21-georgia-transit-landing.onrender.com`.
