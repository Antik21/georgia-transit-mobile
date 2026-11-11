interface TelegramBackButton {
  show(): void;
  hide(): void;
  onClick(handler: () => void): void;
  offClick(handler: () => void): void;
}

interface TelegramWebApp {
  readonly colorScheme?: "light" | "dark";
  readonly BackButton?: TelegramBackButton;
  ready(): void;
  expand(): void;
  onEvent(event: "themeChanged", handler: () => void): void;
  offEvent(event: "themeChanged", handler: () => void): void;
}

declare global {
  interface Window {
    Telegram?: { WebApp?: TelegramWebApp };
  }
}

export function connectTelegram(onBack: () => void) {
  const webApp = window.Telegram?.WebApp;
  const updateTheme = () => {
    // Telegram's script is also present in regular browsers; their OS preference still applies.
    if (webApp?.colorScheme && document.documentElement.style.getPropertyValue("--tg-theme-bg-color")) {
      document.documentElement.dataset.colorScheme = webApp.colorScheme;
    }
  };

  updateTheme();
  webApp?.onEvent("themeChanged", updateTheme);
  webApp?.BackButton?.onClick(onBack);
  webApp?.ready();
  webApp?.expand();

  return {
    setBackVisible(visible: boolean) {
      if (visible) webApp?.BackButton?.show();
      else webApp?.BackButton?.hide();
    },
    dispose() {
      webApp?.BackButton?.hide();
      webApp?.BackButton?.offClick(onBack);
      webApp?.offEvent("themeChanged", updateTheme);
    },
  };
}
