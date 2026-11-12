package com.denis.georgiatransit.shared.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One side of a planned journey (ADR 0023). Stops and addresses are deliberately out of scope. */
sealed interface JourneyEndpoint {
    data object CurrentLocation : JourneyEndpoint
    data class MapPoint(val point: GeoPoint) : JourneyEndpoint
}

enum class JourneyPlanMode { Fastest, LessWalking, FewerTransfers }

@Serializable enum class JourneyWaitBasis { LiveVehicle, HeadwayEstimate }

@Serializable enum class JourneyTimingQuality { Live, Mixed, Estimated }

data class JourneyRequest(
    val origin: JourneyEndpoint,
    val destination: JourneyEndpoint,
    val mode: JourneyPlanMode = JourneyPlanMode.Fastest,
    val maxTransfers: Int = DEFAULT_MAX_TRANSFERS,
    val transferBuffer: Duration = DEFAULT_TRANSFER_BUFFER,
)

/** The only coordinate-bearing planner input that may leave the device; it has no origin-kind field. */
data class JourneyPlanQuery(
    val from: GeoPoint,
    val to: GeoPoint,
    val mode: JourneyPlanMode,
    val maxTransfers: Int,
    val transferBufferSeconds: Int,
    val locale: TransitLocale,
    val departureAt: Instant? = null,
)

sealed interface JourneyResolution {
    data class Ready(val query: JourneyPlanQuery) : JourneyResolution
    /** A and B are closer than [ALREADY_THERE_METERS]; the planner is not called. */
    data class AlreadyThere(val walkMeters: Int, val walkMinutes: Int) : JourneyResolution
    data object NeedsCurrentLocation : JourneyResolution
    data object Invalid : JourneyResolution
}

/** Endpoint rules from ADR 0023, applied before any request is built. */
object JourneyEndpointPolicy {
    fun isValid(request: JourneyRequest): Boolean =
        !(request.origin == JourneyEndpoint.CurrentLocation && request.destination == JourneyEndpoint.CurrentLocation) &&
            request.maxTransfers in 0..MAX_TRANSFERS &&
            request.transferBuffer.inWholeSeconds in 0..MAX_TRANSFER_BUFFER_SECONDS

    fun resolve(request: JourneyRequest, currentLocation: GeoPoint?, locale: TransitLocale): JourneyResolution {
        if (!isValid(request)) return JourneyResolution.Invalid
        val from = request.origin.pointOrNull(currentLocation) ?: return JourneyResolution.NeedsCurrentLocation
        val to = request.destination.pointOrNull(currentLocation) ?: return JourneyResolution.NeedsCurrentLocation
        if (!from.isValidCoordinate() || !to.isValidCoordinate()) return JourneyResolution.Invalid
        val distance = from.distanceMetersTo(to)
        if (distance < ALREADY_THERE_METERS) {
            val walkMeters = distance * WALK_DETOUR_FACTOR
            return JourneyResolution.AlreadyThere(
                walkMeters = walkMeters.roundToLong().toInt(),
                walkMinutes = ceil(walkMeters / WALK_SPEED_METERS_PER_SECOND / 60.0).toInt().coerceAtLeast(1),
            )
        }
        return JourneyResolution.Ready(
            JourneyPlanQuery(
                from = from.roundedForPlanning(),
                to = to.roundedForPlanning(),
                mode = request.mode,
                maxTransfers = request.maxTransfers,
                transferBufferSeconds = request.transferBuffer.inWholeSeconds.toInt(),
                locale = locale,
            ),
        )
    }

    private fun JourneyEndpoint.pointOrNull(currentLocation: GeoPoint?): GeoPoint? = when (this) {
        JourneyEndpoint.CurrentLocation -> currentLocation
        is JourneyEndpoint.MapPoint -> point
    }
}

/** About 11 m of precision: enough for the walks the planner reports, coarse enough to hide a fix. */
fun GeoPoint.roundedForPlanning(): GeoPoint = GeoPoint(latitude.roundTo4(), longitude.roundTo4())

fun GeoPoint.distanceMetersTo(other: GeoPoint): Double {
    val latitudeDelta = (other.latitude - latitude) * PI / 180.0
    val longitudeDelta = (other.longitude - longitude) * PI / 180.0
    val a = sin(latitudeDelta / 2.0).let { it * it } +
        cos(latitude * PI / 180.0) * cos(other.latitude * PI / 180.0) * sin(longitudeDelta / 2.0).let { it * it }
    return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

private fun GeoPoint.isValidCoordinate(): Boolean = latitude.isFinite() && longitude.isFinite() &&
    latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun Double.roundTo4(): Double = (this * 10_000.0).roundToLong() / 10_000.0

const val ALREADY_THERE_METERS = 150.0
const val DEFAULT_MAX_TRANSFERS = 2
const val MAX_TRANSFERS = 2
const val MAX_TRANSFER_BUFFER_SECONDS = 600L
val DEFAULT_TRANSFER_BUFFER: Duration = 60.seconds
// ADR 0022 phase-1 walking model: straight line × 1.3 at 1.3 m/s.
private const val WALK_DETOUR_FACTOR = 1.3
private const val WALK_SPEED_METERS_PER_SECOND = 1.3
private const val EARTH_RADIUS_METERS = 6_371_008.8
