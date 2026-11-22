# Geo Transit landing page

Geo Transit’s static landing page covers Batumi, Kutaisi and Zugdidi. New visitors see English by
default. The selector saves the chosen language and opens its shareable page. Existing `?lang=`
links are supported. Light and dark themes follow the system until changed with the header button.

## Run locally

```sh
python3 -m http.server 4180 --directory landing
```

Then open `http://127.0.0.1:4180/`. Any static file server works; there is nothing to build.

## Store and bot links

Set the Android and iOS store URLs in `LINKS` in [main.js](main.js) after publication. When a URL
is `null`, the page shows a localized link placeholder. The web and Telegram links are set in
[index.html](index.html).

## Privacy Policy

The footer button opens [privacy.html](privacy.html) in the selected language. The direct policy
URL defaults to English and can be used in store listings after the site is deployed.

## Search indexing

The localized landing and city pages, `robots.txt`, and `sitemap.xml` are generated alongside the
English landing page so Render can serve them as static files. City copy lives in
[`tools/landing-city-copy.mjs`](../tools/landing-city-copy.mjs). After editing page copy or the
landing HTML, run `node tools/build-landing-seo.mjs` from the repository root with Node.js 22 or
later. Run it with `--check` to verify the checked-in output.

## Screenshots

`assets/screens/mobile-*.webp` are captures of the English Android and iOS apps using production
Batumi data. The light and dark live map, stop arrivals and route list were captured on Android.
The selected routes on the map and the journey planner were captured on iOS. Screens are encoded
at 900 px wide with their device proportions. CSS supplies the device frames.

The city guides also show the Kutaisi timetable on Android and the Zugdidi route map on iOS.

## Hosting

Published by the `antik21-georgia-transit-landing` static site in [render.yaml](../render.yaml):
the `landing/` folder is served as is from `main` (no build step) after the required checks pass,
and only changes under `landing/` trigger a deploy. The default address is
`https://antik21-georgia-transit-landing.onrender.com`.
