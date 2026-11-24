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

export interface TelegramLocationHost {
  readonly manager: TelegramLocationManager;
  checkStatus?(onUpdated: () => void): () => void;
}

type TelegramEvent = "themeChanged" | "activated" | "locationManagerUpdated";

interface TelegramWebApp {
  readonly platform?: string;
  readonly colorScheme?: "light" | "dark";
  readonly BackButton?: TelegramBackButton;
  readonly LocationManager?: TelegramLocationManager;
  isVersionAtLeast?(version: string): boolean;
  ready(): void;
  expand(): void;
  disableVerticalSwipes?(): void;
  onEvent(event: TelegramEvent, handler: () => void): void;
  offEvent(event: TelegramEvent, handler: () => void): void;
}

declare global {
  interface Window {
    Telegram?: {
      WebApp?: TelegramWebApp;
      WebView?: { postEvent(event: "web_app_check_location", callback: false): void };
    };
  }
}

export function connectTelegram(onBack: () => void, onActivated: () => void) {
  const webApp = window.Telegram?.WebApp;
  const webView = window.Telegram?.WebView;
  const isMiniApp = Boolean(webApp?.platform && webApp.platform !== "unknown");
  const supportsLocation = isMiniApp && webApp?.isVersionAtLeast?.("8.0");
  const manager = supportsLocation ? webApp?.LocationManager : undefined;
  const location: TelegramLocationHost | undefined = manager ? { manager } : undefined;
  if (location && webView && webApp) {
    location.checkStatus = (onUpdated) => {
      const unsubscribe = () => webApp.offEvent("locationManagerUpdated", onUpdated);
      webApp.onEvent("locationManagerUpdated", onUpdated);
      try {
        // LocationManager.init() only asks the host for settings once per page.
        webView.postEvent("web_app_check_location", false);
        return unsubscribe;
      } catch (error) {
        unsubscribe();
        throw error;
      }
    };
  }
  const backButton = webApp?.isVersionAtLeast?.("6.1") === false ? undefined : webApp?.BackButton;
  const updateTheme = () => {
    // Telegram's script is also present in regular browsers; their OS preference still applies.
    if (webApp?.colorScheme && document.documentElement.style.getPropertyValue("--tg-theme-bg-color")) {
      document.documentElement.dataset.colorScheme = webApp.colorScheme;
    }
  };

  updateTheme();
  webApp?.onEvent("themeChanged", updateTheme);
  if (supportsLocation) webApp?.onEvent("activated", onActivated);
  backButton?.onClick(onBack);
  webApp?.ready();
  webApp?.expand();
  // Otherwise a downward map pan collapses the Mini App instead of moving the map.
  if (webApp?.isVersionAtLeast?.("7.7")) webApp.disableVerticalSwipes?.();

  return {
    isMiniApp,
    location,
    setBackVisible(visible: boolean) {
      if (visible) backButton?.show();
      else backButton?.hide();
    },
    dispose() {
      backButton?.hide();
      backButton?.offClick(onBack);
      webApp?.offEvent("themeChanged", updateTheme);
      if (supportsLocation) webApp?.offEvent("activated", onActivated);
    },
  };
}
