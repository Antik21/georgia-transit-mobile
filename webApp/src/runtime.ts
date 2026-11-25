import { TransitSliceRuntime } from "@georgia-transit/shared";
import type { TransitRuntime } from "./contracts";

export function createTransitRuntime(origin: string, languageTag: string, telegramMiniApp: boolean): TransitRuntime {
  const params = new URLSearchParams(window.location.search);
  return new TransitSliceRuntime(origin, languageTag, telegramMiniApp, params.get("city") || null, params.get("route") || null);
}
