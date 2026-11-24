import type { LocationCommand, MapEntry } from "./contracts";
import type { TelegramLocationData, TelegramLocationHost } from "./telegram";

// Matches the native adapters' request timeout and the shared precise-fix accuracy limit.
const requestTimeoutMillis = 10_000;
const preciseAccuracyMeters = 250;
const refreshIntervalMillis = 5_000;

type Permission = "notDetermined" | "granted" | "denied" | "settingsRequired" | "unavailable" | "error";

interface Fix {
  readonly latitude: number;
  readonly longitude: number;
  readonly accuracy: number;
  readonly capturedAt: number;
}

interface Provider {
  permission(): Promise<Permission>;
  /** Resolves null when no fix is available; rejects with "timedOut" or "denied". */
  locate(prompt: boolean): Promise<Fix | null>;
  openSettings(): boolean;
}

export interface LocationAdapter {
  handle(command: LocationCommand): void;
  /** Re-reads the permission after a pause; `force` also re-reads it while already active. */
  resume(force?: boolean): void;
  /** Drops pending permission and fix requests while the map is hidden or covered. */
  pause(): void;
  setRefreshing(active: boolean): void;
  dispose(): void;
}

function withTimeout<T>(promise: Promise<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error("timedOut")), requestTimeoutMillis);
    promise.then(
      (value) => { window.clearTimeout(timer); resolve(value); },
      (error) => { window.clearTimeout(timer); reject(error); },
    );
  });
}

function telegramProvider({ manager, checkStatus }: TelegramLocationHost): Provider {
  let checkingStatus: Promise<void> | undefined;

  async function readStatus() {
    if (!manager.isInited) {
      await withTimeout(new Promise<void>((resolve) => manager.init(resolve)));
    } else if (checkStatus) {
      let unsubscribe: (() => void) | undefined;
      try {
        await withTimeout(new Promise<void>((resolve) => { unsubscribe = checkStatus(resolve); }));
      } finally {
        unsubscribe?.();
      }
    }
  }

  function refreshStatus() {
    return checkingStatus ??= readStatus().finally(() => { checkingStatus = undefined; });
  }

  return {
    async permission() {
      await refreshStatus();
      if (!manager.isLocationAvailable) return "unavailable";
      if (!manager.isAccessRequested) return "notDetermined";
      return manager.isAccessGranted ? "granted" : "settingsRequired";
    },
    async locate(prompt) {
      if (!manager.isInited || !manager.isLocationAvailable) await refreshStatus();
      if (!manager.isLocationAvailable) return null;
      if (!prompt && !manager.isAccessGranted) throw new Error("denied");
      const data = await new Promise<TelegramLocationData | null>((resolve) => manager.getLocation(resolve));
      if (!data) {
        if (manager.isAccessRequested && !manager.isAccessGranted) throw new Error("denied");
        return null;
      }
      return {
        latitude: data.latitude,
        longitude: data.longitude,
        accuracy: data.horizontal_accuracy ?? Number.NaN,
        capturedAt: Date.now(),
      };
    },
    openSettings() {
      manager.openSettings();
      return true;
    },
  };
}

function browserProvider(): Provider | undefined {
  const geolocation = window.isSecureContext ? navigator.geolocation : undefined;
  if (!geolocation) return undefined;
  return {
    async permission() {
      try {
        const status = await navigator.permissions?.query({ name: "geolocation" });
        if (status?.state === "granted") return "granted";
        // Browsers can still prompt again after a denial in many cases; settings are not ours to open.
        if (status?.state === "denied") return "denied";
      } catch {
        // Some WebViews expose geolocation without the Permissions API.
      }
      return "notDetermined";
    },
    locate() {
      return new Promise((resolve, reject) => {
        geolocation.getCurrentPosition(
          ({ coords, timestamp }) => resolve({
            latitude: coords.latitude,
            longitude: coords.longitude,
            accuracy: coords.accuracy,
            capturedAt: timestamp,
          }),
          (error) => reject(new Error(
            error.code === error.PERMISSION_DENIED ? "denied" : error.code === error.TIMEOUT ? "timedOut" : "unavailable",
          )),
          { enableHighAccuracy: true, timeout: requestTimeoutMillis, maximumAge: 30_000 },
        );
      });
    },
    openSettings: () => false,
  };
}

export function createLocationAdapter(entry: MapEntry, host: TelegramLocationHost | undefined): LocationAdapter {
  const provider = host ? telegramProvider(host) : browserProvider();
  // The shared session ties precision to the permission; browsers only reveal it per fix.
  let precise = true;
  let activeRequest: string | undefined;
  let permissionPending = false;
  let permissionFix: Fix | undefined;
  let generation = 0;
  let refreshTimer: number | undefined;
  let disposed = false;
  let paused = false;

  function reportPermission(permission: Permission) {
    if (disposed) return;
    if (permission !== "granted") permissionFix = undefined;
    entry.locationPermission(permission, precise, permission === "error" || (Boolean(host) && permission === "unavailable"));
  }

  async function queryPermission() {
    const current = generation;
    try {
      const permission = provider ? await provider.permission() : "unavailable";
      if (current === generation && !paused) reportPermission(permission);
    } catch {
      if (current === generation && !paused) reportPermission("error");
    }
  }

  async function requestLocation(id: string, prompt: boolean) {
    if (!provider) {
      reportPermission("unavailable");
      return;
    }
    if (prompt && (permissionPending || activeRequest)) return;
    const current = ++generation;
    permissionPending = prompt;
    activeRequest = prompt ? undefined : id;
    const recentPermissionFix = !prompt && permissionFix && Date.now() - permissionFix.capturedAt < requestTimeoutMillis
      ? permissionFix : undefined;
    permissionFix = undefined;
    try {
      const fix = recentPermissionFix ?? await withTimeout(provider.locate(prompt));
      if (disposed || current !== generation) return;
      if (prompt) {
        if (fix) {
          // Telegram returns a fix with the grant. The next shared request can use it immediately.
          if (host) permissionFix = fix;
          reportPermission("granted");
        } else {
          await queryPermission();
        }
        return;
      }
      if (!fix) {
        entry.locationFailed(id, "unavailable");
        return;
      }
      const nextPrecise = !(fix.accuracy > preciseAccuracyMeters);
      if (nextPrecise !== precise) {
        precise = nextPrecise;
        reportPermission("granted");
      }
      entry.locationFix(id, fix.latitude, fix.longitude, fix.accuracy, fix.capturedAt);
    } catch (error) {
      if (disposed || current !== generation) return;
      const reason = error instanceof Error ? error.message : "unavailable";
      if (reason === "denied") {
        reportPermission(host ? "settingsRequired" : "denied");
        if (!prompt) entry.locationFailed(id, "unavailable");
      } else if (prompt) {
        await queryPermission();
      } else {
        entry.locationFailed(id, reason === "timedOut" ? "timedOut" : "unavailable");
      }
    } finally {
      if (current === generation) {
        activeRequest = undefined;
        permissionPending = false;
      }
    }
  }

  return {
    handle(command) {
      if (disposed) return;
      switch (command.kind) {
        case "queryPermission":
          if (!permissionPending) void queryPermission();
          break;
        case "requestPermission":
          void requestLocation(command.id, true);
          break;
        case "requestLocation":
          // A grant can arrive while hidden; locating then would recenter a map nobody sees.
          if (paused || document.visibilityState !== "visible") entry.locationCancelled(command.id);
          else void requestLocation(command.id, false);
          break;
        case "openAppSettings":
        case "openLocationSettings":
          try {
            if (provider?.openSettings()) break;
          } catch {
            // Telegram can reject opening settings if its permission snapshot just changed.
          }
          reportPermission("error");
          break;
      }
    },
    resume(force = false) {
      if (disposed || (!paused && !force)) return;
      paused = false;
      if (!permissionPending) void queryPermission();
    },
    pause() {
      if (paused) return;
      paused = true;
      // Also invalidates a pending permission prompt, which never sets activeRequest.
      generation++;
      permissionPending = false;
      permissionFix = undefined;
      const id = activeRequest;
      activeRequest = undefined;
      if (id) entry.locationCancelled(id);
    },
    setRefreshing(active) {
      if (active && refreshTimer === undefined && !disposed) {
        refreshTimer = window.setInterval(() => entry.refreshLocation(), refreshIntervalMillis);
      } else if (!active && refreshTimer !== undefined) {
        window.clearInterval(refreshTimer);
        refreshTimer = undefined;
      }
    },
    dispose() {
      disposed = true;
      generation++;
      if (refreshTimer !== undefined) window.clearInterval(refreshTimer);
      refreshTimer = undefined;
    },
  };
}
