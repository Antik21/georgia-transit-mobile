import { TransitSliceRuntime } from "@georgia-transit/shared";
import type { TransitRuntime } from "./contracts";

export function createTransitRuntime(origin: string, languageTag: string): TransitRuntime {
  return new TransitSliceRuntime(origin, languageTag);
}
