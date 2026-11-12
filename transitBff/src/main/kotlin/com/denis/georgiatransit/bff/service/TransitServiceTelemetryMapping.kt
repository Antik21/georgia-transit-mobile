package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.cache.CacheLookupOutcome
import com.denis.georgiatransit.bff.cache.SingleFlightLookupOutcome
import com.denis.georgiatransit.bff.observability.CacheOutcome
import com.denis.georgiatransit.bff.observability.TelemetryCapability

internal fun CacheLookupOutcome.telemetryOutcome(): CacheOutcome = when (this) {
    CacheLookupOutcome.HIT -> CacheOutcome.HIT
    CacheLookupOutcome.MISS_OWNER -> CacheOutcome.MISS_OWNER
    CacheLookupOutcome.COALESCED -> CacheOutcome.COALESCED
}

internal fun SingleFlightLookupOutcome.telemetryOutcome(): CacheOutcome = when (this) {
    SingleFlightLookupOutcome.MISS_OWNER -> CacheOutcome.MISS_OWNER
    SingleFlightLookupOutcome.COALESCED -> CacheOutcome.COALESCED
}

internal fun CityCapabilities.capabilityEnabled(
    capability: TelemetryCapability,
): Boolean = when (capability) {
    TelemetryCapability.ROUTES -> routes
    TelemetryCapability.STOPS -> stops
    TelemetryCapability.ROUTE_GEOMETRY -> routeGeometry
    TelemetryCapability.VEHICLE_POSITIONS -> vehiclePositions
    TelemetryCapability.ARRIVALS -> arrivals
    TelemetryCapability.TRIP_PLANNING -> tripPlanning
}
