// Keep links to the former query-based translations working.
const requested = new URLSearchParams(location.search).get("lang")?.toLowerCase();
if (requested) {
  const language = requested === "zh" ? "zh-hans" : requested;
  const alternate = [...document.querySelectorAll('link[rel="alternate"][hreflang]')]
    .find(link => link.hreflang.toLowerCase() === language);
  if (alternate && language !== "x-default") {
    const url = new URL(location.href);
    url.pathname = new URL(alternate.href).pathname;
    url.searchParams.delete("lang");
    location.replace(url);
  }
}

try {
  const theme = localStorage.getItem("gt-landing-theme");
  if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;
} catch {}
