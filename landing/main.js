import { cityPagePath, translations } from "./locales.js";
import { initLandingAnalytics, trackLandingLanguage } from "./analytics.js";

// Fill in store links here once they exist; null renders a visible "link coming" placeholder.
const LINKS = {
  android: null,
  ios: null,
};

const linkLabels = { android: "linkAndroid", ios: "linkIos" };
const storage = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch {} },
};
const pageLanguage = document.documentElement.lang;
const localizedPaths = new Map(
  [...document.querySelectorAll('link[rel="alternate"][hreflang]')]
    .map(link => [link.hreflang, new URL(link.href).pathname]),
);

function supportedLanguage(tag) {
  const language = tag?.toLowerCase().split("-")[0];
  if (language === "zh") return "zh-Hans";
  return Object.hasOwn(translations, language) ? language : null;
}

function pickLanguage() {
  const fromUrl = new URLSearchParams(location.search).get("lang");
  const preferred = pageLanguage === "en" ? supportedLanguage(storage.get("gt-landing-lang")) : null;
  return supportedLanguage(fromUrl) ?? preferred ?? pageLanguage;
}

const languageSelect = document.querySelector("#language");
const privacyLink = document.querySelector("#privacy-link");

function applyLinks(copy) {
  for (const element of document.querySelectorAll("[data-link]")) {
    const key = element.dataset.link;
    const url = LINKS[key];
    if (!url || element.tagName === "A" || !element.classList.contains("platform__link")) continue;
    const anchor = document.createElement("a");
    anchor.className = "platform__link";
    anchor.href = url;
    anchor.dataset.link = key;
    anchor.dataset.analyticsPlatform = key;
    anchor.dataset.i18n = linkLabels[key];
    anchor.textContent = copy[linkLabels[key]];
    element.replaceWith(anchor);
  }
}

function applyLanguage(language) {
  const copy = translations[language];
  document.documentElement.lang = language;
  document.title = copy.pageTitle;
  for (const element of document.querySelectorAll("[data-i18n]")) element.textContent = copy[element.dataset.i18n];
  for (const element of document.querySelectorAll("[data-i18n-alt]")) element.alt = copy[element.dataset.i18nAlt];
  for (const element of document.querySelectorAll("[data-i18n-aria]")) element.setAttribute("aria-label", copy[element.dataset.i18nAria]);
  for (const element of document.querySelectorAll("[data-i18n-content]")) element.content = copy[element.dataset.i18nContent];
  languageSelect.value = language;
  privacyLink.href = cityPagePath("privacy", language);
  for (const link of document.querySelectorAll("[data-city-link]")) {
    link.href = cityPagePath(link.dataset.cityLink, language);
  }
  applyLinks(copy);
}

function currentTheme() {
  const explicit = document.documentElement.dataset.theme;
  if (explicit) return explicit;
  return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

languageSelect.addEventListener("change", () => {
  const language = languageSelect.value;
  trackLandingLanguage(language);
  storage.set("gt-landing-lang", language);
  const url = new URL(location.href);
  url.pathname = localizedPaths.get(language);
  url.searchParams.delete("lang");
  if (url.href === location.href) {
    applyLanguage(language);
  } else {
    location.assign(url);
  }
});

document.querySelector(".theme-toggle").addEventListener("click", () => {
  const next = currentTheme() === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  storage.set("gt-landing-theme", next);
});

const heroLine = document.querySelector(".hero__line");
for (const stop of document.querySelectorAll(".hero__stop[data-at]")) {
  const point = heroLine.getPointAtLength(heroLine.getTotalLength() * Number(stop.dataset.at));
  stop.setAttribute("cx", point.x.toFixed(1));
  stop.setAttribute("cy", point.y.toFixed(1));
}

applyLanguage(pickLanguage());
initLandingAnalytics();
