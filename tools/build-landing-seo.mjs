import { readFileSync, writeFileSync } from "node:fs";

import { translations } from "../landing/locales.js";

const site = "https://www.geotransit.com.ge";
const landing = new URL("../landing/", import.meta.url);
const source = readFileSync(new URL("index.html", landing), "utf8");
const check = process.argv.includes("--check");
const languages = ["en", ...Object.keys(translations).filter(language => language !== "en")];
const pages = Object.fromEntries(
  languages.map(language => [language, language === "en" ? "/" : `/${language.toLowerCase()}.html`]),
);
const alternateTags = languages.map(language =>
  `<link rel="alternate" hreflang="${language}" href="${site}${pages[language]}">`,
);
alternateTags.push(`<link rel="alternate" hreflang="x-default" href="${site}/">`);

function escapeHtml(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");
}

function renderPage(language) {
  const copy = translations[language];
  const text = (key) => {
    if (!Object.hasOwn(copy, key)) throw new Error(`Missing ${language} translation for ${key}`);
    return escapeHtml(copy[key]);
  };
  const canonical = site + pages[language];
  const seoTags = [
    `<link rel="canonical" href="${canonical}">`,
    `<meta property="og:url" content="${canonical}">`,
    ...alternateTags,
  ];
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
    .replace('href="privacy.html?lang=en"', `href="privacy.html?lang=${language}"`);
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
output("sitemap.xml", [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
  ...sitemapPaths.map(path => `  <url><loc>${site}${path}</loc></url>`),
  "</urlset>",
  "",
].join("\n"));
output("robots.txt", `User-agent: *\nAllow: /\n\nSitemap: ${site}/sitemap.xml\n`);
