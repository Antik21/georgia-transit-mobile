import { translations } from "./locales.js";

const preferenceKey = "gt-landing-analytics";
const sessionKey = "gt-landing-analytics-session";
const inactivityLimit = 30 * 60 * 1000;
let preference = null;
try {
  preference = localStorage.getItem(preferenceKey);
} catch {}
let session = null;

function getSession(time) {
  if (!session) {
    try {
      session = JSON.parse(sessionStorage.getItem(sessionKey));
    } catch {}
  }
  if (!session?.deviceId || !Number.isSafeInteger(session.sessionId) ||
      !Number.isSafeInteger(session.lastEvent) || time < session.lastEvent ||
      time - session.lastEvent >= inactivityLimit) {
    session = { deviceId: crypto.randomUUID(), sessionId: time };
  }
  session.lastEvent = time;
  try {
    sessionStorage.setItem(sessionKey, JSON.stringify(session));
  } catch {}
  return session;
}

function record(action, properties = {}) {
  if (preference !== "allowed" || typeof crypto.randomUUID !== "function") return;
  const time = Date.now();
  const { deviceId, sessionId } = getSession(time);
  const landing = {
    action,
    language: document.documentElement.lang,
    eventId: crypto.randomUUID(),
    ...properties,
  };

  void fetch("/v1/analytics", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "omit",
    referrerPolicy: "no-referrer",
    keepalive: true,
    body: JSON.stringify({ client: "landing", deviceId, sessionId, time, landing }),
  }).catch(() => {});
}

export function trackLandingLanguage(language) {
  const previousLanguage = document.documentElement.lang;
  if (language !== previousLanguage) {
    record("language_changed", { language, previousLanguage });
  }
}

function createConsentPanel(copy) {
  const panel = document.createElement("aside");
  panel.id = "analytics-choice";
  panel.className = "analytics-choice";
  panel.setAttribute("aria-labelledby", "analytics-choice-title");

  const title = document.createElement("h2");
  title.id = "analytics-choice-title";
  title.textContent = copy.analyticsTitle;
  const summary = document.createElement("p");
  summary.textContent = copy.analyticsBody;
  const actions = document.createElement("div");
  actions.className = "analytics-choice__actions";

  for (const [value, label] of [["allowed", copy.analyticsAllow], ["declined", copy.analyticsDecline]]) {
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.analyticsChoice = value;
    button.textContent = label;
    actions.append(button);
  }
  const policy = document.createElement("a");
  policy.href = `/privacy.html?lang=${document.documentElement.lang}`;
  policy.textContent = copy.privacy;
  actions.append(policy);
  panel.append(title, summary, actions);
  return panel;
}

export function initLandingAnalytics() {
  const settings = document.querySelector("#analytics-settings");
  const panel = createConsentPanel(translations[document.documentElement.lang]);
  let viewed = false;
  const view = () => {
    if (preference === "allowed" && !viewed) {
      viewed = true;
      record("page_viewed");
    }
  };
  const showChoice = (visible) => {
    panel.hidden = !visible;
    settings.setAttribute("aria-expanded", String(visible));
  };
  const applyPreference = () => {
    if (preference !== "allowed") {
      session = null;
      try {
        sessionStorage.removeItem(sessionKey);
      } catch {}
    }
    showChoice(!preference);
    view();
  };

  document.body.append(panel);
  settings.hidden = false;
  settings.addEventListener("click", () => {
    showChoice(panel.hidden);
    if (!panel.hidden) panel.querySelector("button").focus();
  });
  panel.addEventListener("click", (event) => {
    const choice = event.target.closest("button[data-analytics-choice]");
    if (!choice) return;
    preference = choice.dataset.analyticsChoice;
    try {
      localStorage.setItem(preferenceKey, preference);
    } catch {}
    applyPreference();
    settings.focus({ preventScroll: true });
  });

  function trackLink(event) {
    if (event.defaultPrevented || (event.button !== 0 && event.button !== 1)) return;
    const link = event.target.closest("a[href]");
    if (link?.dataset.analyticsPlatform) {
      record("platform_clicked", { targetPlatform: link.dataset.analyticsPlatform });
    } else if (link?.dataset.analyticsLanguage) {
      trackLandingLanguage(link.dataset.analyticsLanguage);
    }
  }
  document.addEventListener("click", trackLink);
  document.addEventListener("auxclick", trackLink);
  window.addEventListener("storage", (event) => {
    if (event.key === preferenceKey || event.key === null) {
      preference = event.newValue;
      applyPreference();
    }
  });
  window.addEventListener("pageshow", (event) => {
    if (!event.persisted) return;
    try {
      preference = localStorage.getItem(preferenceKey);
    } catch {}
    viewed = false;
    applyPreference();
  });

  applyPreference();
  if (location.hash === "#analytics-settings") showChoice(true);
  window.addEventListener("hashchange", () => {
    if (location.hash === "#analytics-settings") showChoice(true);
  });
}

if (document.body.dataset.analyticsPage === "city") initLandingAnalytics();
