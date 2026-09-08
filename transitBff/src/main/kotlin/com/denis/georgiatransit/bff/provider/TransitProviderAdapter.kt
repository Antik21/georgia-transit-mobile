package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.Arrival
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.Direction
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.observability.TelemetryCapability
import com.denis.georgiatransit.bff.observability.TelemetryOperation
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import java.time.Instant

/**
 * The only boundary where a city-specific provider contract is allowed. Implementations must map
 * provider DTOs and credentials to these normalized BFF models before returning to the service.
 */
interface CityTransitProviderAdapter {
    val city: City

    /** Internal, finite telemetry identifier. It is never part of a public normalized DTO. */
    val telemetryProvider: TelemetryProvider get() = TelemetryProvider.FIXTURE

    suspend fun routes(locale: String, mode: String?): List<Route>

    suspend fun route(routeId: String, locale: String): Route

    suspend fun directionStops(routeId: String, directionId: String, locale: String): List<Stop>

    suspend fun shape(routeId: String, directionId: String): Shape

    suspend fun stopDirectory(locale: String): List<Stop>

    suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles

    suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals

    suspend fun journeys(query: JourneyQuery): List<Journey>

    /** Additive realtime metadata seam; older adapters retain schedule-only journey behavior. */
    suspend fun journeyPage(query: JourneyQuery): RealtimeJourneys = RealtimeJourneys(
        items = journeys(query),
        source = ArrivalSource.SCHEDULE,
        realtime = false,
        observedAt = Instant.now(),
        stale = false,
    )
}

/**
 * Marks an adapter whose every upstream call is already protected at its own provider boundary.
 * This is only for adapters such as Transitous that need to turn a circuit rejection into a
 * bounded stale last-known-good response. Other adapters are protected by TransitService before
 * invoking their boundary. Both paths use the same BffObservability circuit keyed by the finite
 * city/provider/capability tuple, so the marker prevents a second circuit around one call.
 */
internal interface ProviderCircuitProtectedAdapter

/**
 * Internal operator-provisioned probe seam. Targets deliberately carry no display name and are
 * never serialized, logged, or placed in metric labels. They call the same adapter controls as
 * requests but must bypass a provider's last-known-good fallback when reporting fresh success.
 */
internal interface SyntheticProbeProvider {
    val probeTargets: List<SyntheticProbeTarget>

    suspend fun probe(target: SyntheticProbeTarget): SyntheticProbeResult
}

internal data class SyntheticProbeTarget(
    val cityId: String,
    val provider: TelemetryProvider,
    val capability: TelemetryCapability,
    val operation: TelemetryOperation,
    val expectedRealtime: Boolean,
    val privateTarget: String,
)

internal data class SyntheticProbeResult(
    val observedAt: Instant,
    val itemCount: Int,
    val realtime: Boolean,
    val stale: Boolean,
)

data class RealtimeVehicles(
    val items: List<Vehicle>,
    val observedAt: Instant,
    val maxAgeSeconds: Int,
    val stale: Boolean,
)

data class RealtimeArrivals(
    val items: List<Arrival>,
    val source: ArrivalSource,
    val observedAt: Instant,
    val stale: Boolean,
)

data class RealtimeJourneys(
    val items: List<Journey>,
    val source: ArrivalSource,
    val realtime: Boolean,
    val observedAt: Instant,
    val stale: Boolean,
)

data class JourneyQuery(
    val from: GeoPoint,
    val to: GeoPoint,
    val departureAt: Instant,
    val locale: String,
    val maxTransfers: Int,
)

sealed class ProviderFailure(message: String) : RuntimeException(message)

class ProviderInvalidArgument(message: String) : ProviderFailure(message)

class ProviderRouteNotFound(message: String) : ProviderFailure(message)

class ProviderStopNotFound(message: String) : ProviderFailure(message)

class ProviderConflict(message: String) : ProviderFailure(message)

class ProviderRateLimited(message: String, val retryAfterSeconds: Int) : ProviderFailure(message)

class ProviderCapabilityUnavailable(message: String) : ProviderFailure(message)

open class ProviderBadGateway(message: String) : ProviderFailure(message)

/** Internal-only malformed upstream JSON classification; public mapping remains a safe 502. */
class ProviderJsonDecodeFailure(message: String) : ProviderBadGateway(message)

/** Internal-only normalized/schema validation classification; public mapping remains a safe 502. */
class ProviderNormalizedSchemaFailure(message: String) : ProviderBadGateway(message)

class ProviderUnavailable(message: String, val retryAfterSeconds: Int? = null) : ProviderFailure(message)

class ProviderTimeout(message: String) : ProviderFailure(message)

data class RouteDirection(
    val route: Route,
    val direction: Direction,
)
