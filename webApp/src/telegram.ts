interface TelegramBackButton {
  show(): void;
  hide(): void;
  onClick(handler: () => void): void;
  offClick(handler: () => void): void;
}

export interface TelegramLocationData {
  readonly latitude: number;
  readonly longitude: number;
  readonly horizontal_accuracy?: number | null;
}

export interface TelegramLocationManager {
  readonly isInited: boolean;
  readonly isLocationAvailable: boolean;
  readonly isAccessRequested: boolean;
  readonly isAccessGranted: boolean;
  init(callback?: () => void): void;
  getLocation(callback: (data: TelegramLocationData | null) => void): void;
  openSettings(): void;
}

interface TelegramWebApp {
  readonly colorScheme?: "light" | "dark";
  readonly BackButton?: TelegramBackButton;
  readonly LocationManager?: TelegramLocationManager;
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
    // Bot API 8.0 location access lives in Telegram's own permission, not the WebView's geolocation.
    locationManager: webApp?.isVersionAtLeast?.("8.0") ? webApp.LocationManager : undefined,
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
