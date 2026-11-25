import { readFileSync, writeFileSync } from "node:fs";

import { cityPagePath, translations } from "../landing/locales.js";
import { renderZugdidiTrips, zugdidiTripPage, zugdidiTripPath, zugdidiTrips } from "./zugdidi-trips.mjs";
import { kutaisiTripPage, kutaisiTripPath, kutaisiTrips, renderKutaisiTrips } from "./kutaisi-trips.mjs";
import { renderTrips, routePage, tripNumbers, tripPath } from "./batumi-trips.mjs";
import { routeGuide } from "./landing-route-guide.mjs";
import { cityCopy } from "./landing-city-copy.mjs";

const site = "https://www.geotransit.com.ge";
const landing = new URL("../landing/", import.meta.url);
const source = readFileSync(new URL("index.html", landing), "utf8");
const check = process.argv.includes("--check");
const languageLabels = Object.fromEntries(
  [...source.matchAll(/<option value="([^"]+)"[^>]*>([^<]+)<\/option>/g)]
    .map(([, language, label]) => [language, label]),
);
const languages = Object.keys(languageLabels);
const cities = ["batumi", "kutaisi", "zugdidi"];
const cityScreens = {
  batumi: [
    { platform: "android", asset: "mobile-live-map", altKey: "altLiveMap" },
    { platform: "ios", asset: "mobile-planner", altKey: "altPlanner" },
  ],
  kutaisi: [{ platform: "android", asset: "mobile-kutaisi-schedule" }],
  zugdidi: [{ platform: "ios", asset: "mobile-zugdidi-map" }],
};
const pages = Object.fromEntries(
  languages.map(language => [language, language === "en" ? "/" : `/${language.toLowerCase()}.html`]),
);

function escapeHtml(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");
}

function searchTags(path, paths) {
  const canonical = site + path;
  return [
    `<link rel="canonical" href="${canonical}">`,
    `<meta property="og:url" content="${canonical}">`,
    ...Object.entries(paths).map(([language, href]) =>
      `<link rel="alternate" hreflang="${language}" href="${site}${href}">`,
    ),
    `<link rel="alternate" hreflang="x-default" href="${site}${paths.en}">`,
  ];
}

function renderPage(language) {
  const copy = translations[language];
  const text = (key) => {
    if (!Object.hasOwn(copy, key)) throw new Error(`Missing ${language} translation for ${key}`);
    return escapeHtml(copy[key]);
  };
  const seoTags = searchTags(pages[language], pages);
  if (language === "en") {
    const website = { "@context": "https://schema.org", "@type": "WebSite", name: "Geo Transit", url: site + "/" };
    seoTags.push(`<script type="application/ld+json">${JSON.stringify(website)}</script>`);
  }

  return source
    .replace(/<!-- seo -->[\s\S]*?<!-- \/seo -->/, `<!-- seo -->\n  ${seoTags.join("\n  ")}\n  <!-- /seo -->`)
    .replace(/\n  <meta name="(?:google-site-verification|yandex-verification)"[^>]*>/g,
      tag => language === "en" ? tag : "")
    .replace('<html lang="en">', `<html lang="${language}">`)
    .replace(/<title>[^<]*<\/title>/, `<title>${text("pageTitle")}</title>`)
    .replace(/(data-i18n="(\w+)"[^>]*>)[^<]*(<\/)/g,
      (_, start, key, end) => start + text(key) + end)
    .replace(/(aria-label|alt)="[^"]*" (data-i18n-(?:aria|alt)="(\w+)")/g,
      (_, attribute, annotation, key) => `${attribute}="${text(key)}" ${annotation}`)
    .replace(/(data-i18n-content="(\w+)") content="[^"]*"/g,
      (_, annotation, key) => `${annotation} content="${text(key)}"`)
    .replace('lang="en" selected', 'lang="en"')
    .replace(`<option value="${language}" lang="${language}"`, `<option value="${language}" lang="${language}" selected`)
    .replace(/href="[^"]*" data-city-link="(\w+)"/g,
      (_, city) => `href="${cityPagePath(city, language)}" data-city-link="${city}"`)
    .replace('href="privacy.html?lang=en"', `href="privacy.html?lang=${language}"`);
}

function phone(platform, asset, alt) {
  const height = platform === "ios" ? 1957 : 2000;
  return `<figure class="phone phone--${platform}">
          <div class="phone__screen">
            <img src="/assets/screens/${asset}.webp" width="900" height="${height}" alt="${escapeHtml(alt)}">
          </div>
        </figure>`;
}

function renderCity(city, language, paths) {
  const { heading, title, description, intro, sections, imageAlt, serviceLink } = cityCopy[language][city];
  const copy = translations[language];
  const esc = escapeHtml;
  const screens = cityScreens[city];
  const guide = routeGuide(city, language, esc);
  const illustrations = screens.map(({ platform, asset, altKey }) =>
    phone(platform, asset, altKey ? copy[altKey] : imageAlt),
  ).join("\n          ");
  const guideSections = sections.map(([name, body], index) => `<section aria-labelledby="guide-${index}">
        <h2 id="guide-${index}">${esc(name)}</h2>
        <p>${esc(body)}</p>${index === 0 && serviceLink
          ? `\n        <a href="https://kutaisi-bus.msplus.ge/">${esc(serviceLink)}</a>` : ""}
      </section>`).join("\n      ");
  const trips = { batumi: renderTrips, kutaisi: renderKutaisiTrips, zugdidi: renderZugdidiTrips }[city]?.(language, esc);
  const main = `<section class="city-hero" data-city="${city}" aria-labelledby="city-title">
      <div class="wrap city-hero__grid">
        <div class="city-hero__copy">
          <h1 id="city-title">${esc(heading)}</h1>
          <p>${esc(intro)}</p>
          <div class="city-hero__actions">
            <a class="button city-guide__cta" href="${guide?.map ?? "https://app.geotransit.com.ge/"}" data-analytics-platform="web">${esc(guide?.button ?? copy.ctaWeb)}</a>
            <a href="https://t.me/geo_transit_bot?startapp" data-analytics-platform="telegram_mini_app">${esc(copy.linkTelegram)}</a>
          </div>
        </div>
        <div class="city-hero__screens">
          ${illustrations}
        </div>
      </div>
    </section>
    ${trips ? trips + "\n    " : ""}${guide ? guide.html + "\n    " : ""}<div class="wrap city-guide__sections">
      ${guideSections}
    </div>`;
  return guideShell(city, language, paths, { title, description, main });
}

function guideShell(city, language, paths, { title, description, main }) {
  const copy = translations[language];
  const esc = escapeHtml;
  const localeLinks = languages.map(tag => {
    const current = tag === language ? ' aria-current="page"' : "";
    return `<a href="${paths[tag]}" lang="${tag}" hreflang="${tag}" data-analytics-language="${tag}"${current}>${esc(languageLabels[tag])}</a>`;
  }).join("\n        ");
  const related = cities.filter(id => id !== city)
    .map(id => `<a href="${cityPagePath(id, language)}">${esc(copy[id])}</a>`).join("\n        ");
  const shareImage = cityScreens[city][0].asset;

  return `<!doctype html>
<html lang="${language}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="color-scheme" content="light dark">
  <title>${esc(title)}</title>
  <meta name="description" content="${esc(description)}">
  <meta property="og:title" content="${esc(title)}">
  <meta property="og:description" content="${esc(description)}">
  <meta property="og:type" content="website">
  <meta property="og:site_name" content="Geo Transit">
  <meta property="og:image" content="${site}/assets/screens/${shareImage}.webp">
  ${searchTags(paths[language], paths).join("\n  ")}
  <link rel="icon" type="image/png" sizes="192x192" href="/assets/brand/icon-192.png">
  <link rel="stylesheet" href="/styles.css">
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&amp;family=Noto+Sans+Georgian:wght@400;600;700;800&amp;display=swap">
  <script>
    try {
      const theme = localStorage.getItem("gt-landing-theme");
      if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;
    } catch {}
  </script>
</head>
<body class="city-guide" data-analytics-page="city">
  <a class="skip" href="#main">${esc(copy.skip)}</a>
  <header class="topbar">
    <div class="wrap topbar__row">
      <a class="brand" href="${pages[language]}?lang=${language}" aria-label="Geo Transit">
        <img src="/assets/brand/icon-192.png" width="40" height="40" alt="">
        <span>Geo Transit</span>
      </a>
      <a class="city-guide__home" href="${pages[language]}?lang=${language}#cities">${esc(copy.navCities)}</a>
    </div>
  </header>
  <main id="main">
    ${main}
  </main>
  <footer class="footer">
    <div class="wrap city-guide__footer">
      <nav aria-label="${esc(copy.navCities)}">
        ${related}
      </nav>
      <nav aria-label="${esc(copy.langLabel)}">
        ${localeLinks}
      </nav>
      <div class="city-guide__footer-links">
        <a href="${pages[language]}?lang=${language}#platforms">Android, iOS</a>
        <a href="/privacy.html?lang=${language}">${esc(copy.privacy)}</a>
        <button type="button" class="analytics-settings" id="analytics-settings" aria-controls="analytics-choice" aria-expanded="false" hidden>${esc(copy.analyticsSettings)}</button>
      </div>
      <p class="footer__small">${esc(copy.footerNote)}</p>
    </div>
  </footer>
  <script type="module" src="/analytics.js"></script>
</body>
</html>
`;
}

function output(file, content) {
  const url = new URL(file, landing);
  if (!check) {
    writeFileSync(url, content);
  } else if (readFileSync(url, "utf8") !== content) {
    throw new Error(`${file} is out of date. Run node tools/build-landing-seo.mjs.`);
  }
}

for (const language of languages) {
  const file = language === "en" ? "index.html" : pages[language].slice(1);
  // The English source doubles as the published page.
  output(file, renderPage(language));
}

const sitemapPaths = [...Object.values(pages), "/privacy.html"];
for (const city of cities) {
  const paths = Object.fromEntries(languages.map(language => [language, cityPagePath(city, language)]));
  for (const [language, path] of Object.entries(paths)) {
    output(path.slice(1), renderCity(city, language, paths));
    sitemapPaths.push(path);
  }
}
for (const [city, trips, pathFor, pageFor] of [
  ["batumi", tripNumbers, tripPath, routePage],
  ["zugdidi", zugdidiTrips, zugdidiTripPath, zugdidiTripPage],
  ["kutaisi", kutaisiTrips, kutaisiTripPath, kutaisiTripPage],
]) {
  for (const trip of trips) {
    const paths = Object.fromEntries(languages.map(language => [language, pathFor(trip, language)]));
    for (const [language, path] of Object.entries(paths)) {
      output(path.slice(1), guideShell(city, language, paths, pageFor(trip, language, escapeHtml)));
      sitemapPaths.push(path);
    }
  }
}
output("sitemap.xml", [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
  ...sitemapPaths.map(path => `  <url><loc>${site}${path}</loc></url>`),
  "</urlset>",
  "",
].join("\n"));
output("robots.txt", `User-agent: *\nAllow: /\n\nSitemap: ${site}/sitemap.xml\n`);
