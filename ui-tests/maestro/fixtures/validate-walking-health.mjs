#!/usr/bin/env node
// Validates only the owned smoke fixture's coordinate-free introspection contract.
import fs from "node:fs";

const [healthPath, minimumWalkingRequestsText] = process.argv.slice(2);
const minimumWalkingRequests = Number.parseInt(minimumWalkingRequestsText ?? "", 10);
if (!healthPath || !Number.isInteger(minimumWalkingRequests) || minimumWalkingRequests < 0) {
  process.exit(64);
}

let health;
try {
  health = JSON.parse(fs.readFileSync(healthPath, "utf8"));
} catch {
  process.exit(1);
}

const expectedKeys = ["arrivalDelayMillis", "mode", "status", "walkingRequests"];
const actualKeys = health && typeof health === "object" && !Array.isArray(health)
  ? Object.keys(health).sort()
  : [];
const exactShape = JSON.stringify(actualKeys) === JSON.stringify(expectedKeys);
const scalarOnly = exactShape && Object.values(health).every((value) =>
  typeof value === "string" || typeof value === "number"
);
const valid = scalarOnly
  && health.status === "ready"
  && health.mode === "test-only-stop-arrivals-fixture"
  && Number.isInteger(health.arrivalDelayMillis)
  && health.arrivalDelayMillis >= 50
  && health.arrivalDelayMillis <= 5_000
  && Number.isInteger(health.walkingRequests)
  && health.walkingRequests >= minimumWalkingRequests;

process.exit(valid ? 0 : 1);
