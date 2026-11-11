import type { LocationCommand, MapEntry } from "./contracts";
import type { TelegramLocationManager } from "./telegram";

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
  locate(): Promise<Fix | null>;
  openSettings(): boolean;
}

export interface LocationAdapter {
  handle(command: LocationCommand): void;
  /** Reports the current permission, as native adapters do when the screen resumes. */
  resume(): void;
  /** Cancels an in-flight request when the map is hidden or covered. */
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

function telegramProvider(manager: TelegramLocationManager): Provider {
  const ready = new Promise<void>((resolve) => {
    if (manager.isInited) resolve();
    else manager.init(() => resolve());
  });
  return {
    async permission() {
      await ready;
      if (!manager.isLocationAvailable) return "unavailable";
      if (!manager.isAccessRequested) return "notDetermined";
      return manager.isAccessGranted ? "granted" : "settingsRequired";
    },
    async locate() {
      await ready;
      const data = await new Promise<Parameters<Parameters<TelegramLocationManager["getLocation"]>[0]>[0]>(
        (resolve) => manager.getLocation(resolve),
      );
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

export function createLocationAdapter(entry: MapEntry, manager: TelegramLocationManager | undefined): LocationAdapter {
  const provider = manager ? telegramProvider(manager) : browserProvider();
  // The shared session ties precision to the permission; browsers only reveal it per fix.
  let precise = true;
  let activeRequest: string | undefined;
  let generation = 0;
  let refreshTimer: number | undefined;
  let disposed = false;

  function reportPermission(permission: Permission) {
    if (disposed) return;
    entry.locationPermission(permission, precise, permission === "error");
  }

  async function queryPermission() {
    if (!provider) {
      reportPermission("unavailable");
      return;
    }
    try {
      reportPermission(await provider.permission());
    } catch {
      entry.locationPermission("error", precise, true);
    }
  }

  async function requestLocation(id: string, prompt: boolean) {
    if (!provider) {
      reportPermission("unavailable");
      return;
    }
    const current = ++generation;
    activeRequest = prompt ? undefined : id;
    try {
      const fix = await withTimeout(provider.locate());
      if (disposed || current !== generation) return;
      if (prompt) {
        reportPermission(fix ? "granted" : await provider.permission());
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
        reportPermission(manager ? "settingsRequired" : "denied");
        if (!prompt) entry.locationFailed(id, "unavailable");
      } else if (prompt) {
        await queryPermission();
      } else {
        entry.locationFailed(id, reason === "timedOut" ? "timedOut" : "unavailable");
      }
    } finally {
      if (current === generation) activeRequest = undefined;
    }
  }

  return {
    handle(command) {
      if (disposed) return;
      switch (command.kind) {
        case "queryPermission":
          void queryPermission();
          break;
        case "requestPermission":
          void requestLocation(command.id, true);
          break;
        case "requestLocation":
          if (command.isRefresh && document.visibilityState !== "visible") entry.locationCancelled(command.id);
          else void requestLocation(command.id, false);
          break;
        case "openAppSettings":
        case "openLocationSettings":
          if (!provider?.openSettings()) entry.locationPermission("error", precise, true);
          break;
      }
    },
    resume() {
      if (!disposed) void queryPermission();
    },
    pause() {
      const id = activeRequest;
      if (!id) return;
      generation++;
      activeRequest = undefined;
      entry.locationCancelled(id);
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
