interface TelegramBackButton {
  show(): void;
  hide(): void;
  onClick(handler: () => void): void;
  offClick(handler: () => void): void;
}

interface TelegramWebApp {
  readonly colorScheme?: "light" | "dark";
  readonly BackButton?: TelegramBackButton;
  isVersionAtLeast?(version: string): boolean;
  ready(): void;
  expand(): void;
  disableVerticalSwipes?(): void;
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
  const backButton = webApp?.isVersionAtLeast?.("6.1") === false ? undefined : webApp?.BackButton;
  const updateTheme = () => {
    // Telegram's script is also present in regular browsers; their OS preference still applies.
    if (webApp?.colorScheme && document.documentElement.style.getPropertyValue("--tg-theme-bg-color")) {
      document.documentElement.dataset.colorScheme = webApp.colorScheme;
    }
  };

  updateTheme();
  webApp?.onEvent("themeChanged", updateTheme);
  backButton?.onClick(onBack);
  webApp?.ready();
  webApp?.expand();
  // Otherwise a downward map pan collapses the Mini App instead of moving the map.
  if (webApp?.isVersionAtLeast?.("7.7")) webApp.disableVerticalSwipes?.();

  return {
    setBackVisible(visible: boolean) {
      if (visible) backButton?.show();
      else backButton?.hide();
    },
    dispose() {
      backButton?.hide();
      backButton?.offClick(onBack);
      webApp?.offEvent("themeChanged", updateTheme);
    },
  };
}
