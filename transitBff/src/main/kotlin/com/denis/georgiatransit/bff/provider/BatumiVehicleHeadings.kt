package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Owned by the sequential city feed. Vehicle IDs include the route. */
internal class BatumiVehicleHeadings {
    private data class Track(val anchor: GeoPoint, val seenAt: Instant, val status: Int?, val bearing: Double?)

    private val tracks = mutableMapOf<String, Track>()
    private var source: BatumiLiveSource? = null

    fun update(generation: BatumiLiveGeneration): BatumiLiveGeneration {
        if (source != generation.source) tracks.clear()
        source = generation.source
        val oldest = generation.observedAt.minus(BatumiLiveMaximumAge)
        tracks.values.removeIf { it.seenAt < oldest }
        return generation.copy(vehiclesByRoute = generation.vehiclesByRoute.mapValues { (_, vehicles) ->
            vehicles.map { live ->
                val vehicle = live.vehicle
                val time = vehicle.observedAt?.let(Instant::parse) ?: generation.observedAt
                val track = advance(tracks[vehicle.id], vehicle.position, time, live.status)
                tracks[vehicle.id] = track
                live.copy(vehicle = vehicle.copy(bearing = track.bearing))
            }
        })
    }

    private fun advance(previous: Track?, point: GeoPoint, time: Instant, status: Int?): Track {
        if (previous == null || previous.status != status || time < previous.seenAt ||
            time > previous.seenAt.plus(BatumiLiveMaximumAge)
        ) return Track(point, time, status, null)
        if (time == previous.seenAt) return previous
        val distance = greatCircleMeters(previous.anchor, point)
        // Keep the anchor through sub-threshold steps so slow movement can accumulate.
        if (distance < MinimumMovementMeters) return previous.copy(seenAt = time)
        val seconds = (time.toEpochMilli() - previous.seenAt.toEpochMilli()) / 1000.0
        if (distance > MinimumMovementMeters + seconds * MaximumMetersPerSecond) return Track(point, time, status, null)
        return Track(point, time, status, travelBearing(previous.anchor, point))
    }

    private fun travelBearing(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val east = sin(deltaLon) * cos(lat2)
        val north = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
    }

    private companion object {
        const val MinimumMovementMeters = 8.0
        const val MaximumMetersPerSecond = 25.0
    }
}
