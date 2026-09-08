package com.denis.georgiatransit.shared.domain.interactor

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.WalkingEstimate
import com.denis.georgiatransit.shared.domain.model.WalkingEstimateResult
import com.denis.georgiatransit.shared.domain.model.WalkingEstimateSource
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CancellationException
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.Instant

private const val EarthRadiusMeters = 6_371_000.0

/**
 * Applies the product's privacy and honesty rules around a one-shot walking query. It never
 * invokes the BFF while trip planning is unavailable, and it never turns a failure into a value
 * labelled as directions.
 */
class EstimateWalkingToStop(
    private val repository: TransitRepository,
    private val clock: WalkingEstimateClock = SystemWalkingEstimateClock,
) {
    suspend operator fun invoke(
        city: TransitCity,
        from: GeoPoint,
        to: GeoPoint,
        locale: TransitLocale,
    ): WalkingEstimateResult {
        if (!city.capabilities.journeyPlanning) return approximate(from, to)
        val remote = try {
            repository.walkingEstimate(city.id, from, to, locale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return approximate(from, to)
        }
        val routed = (remote as? TransitLoadResult.Data)?.value?.takeIf { it.isValidWalkingEstimate() }
        return if (routed != null) {
            WalkingEstimateResult(routed, WalkingEstimateSource.Routed)
        } else {
            approximate(from, to)
        }
    }

    private fun approximate(from: GeoPoint, to: GeoPoint): WalkingEstimateResult {
        val distance = (haversineMeters(from, to) * DetourFactor).coerceAtLeast(0.0)
        val durationSeconds = ceil(distance / WalkingSpeedMetersPerSecond).toLong().coerceAtLeast(1L)
        return WalkingEstimateResult(
            estimate = WalkingEstimate(distance, durationSeconds, clock.now()),
            source = WalkingEstimateSource.Approximate,
        )
    }

    private fun WalkingEstimate.isValidWalkingEstimate(): Boolean =
        distanceMeters.isFinite() && distanceMeters >= 0.0 && durationSeconds > 0L

    private companion object {
        const val DetourFactor = 1.25
        const val WalkingSpeedMetersPerSecond = 1.4
    }
}

fun interface WalkingEstimateClock { fun now(): Instant }

private object SystemWalkingEstimateClock : WalkingEstimateClock {
    override fun now(): Instant = Clock.System.now()
}

private fun haversineMeters(from: GeoPoint, to: GeoPoint): Double {
    val latitudeDelta = (to.latitude - from.latitude) * PI / 180.0
    val longitudeDelta = (to.longitude - from.longitude) * PI / 180.0
    val a = sin(latitudeDelta / 2).pow(2) +
        cos(from.latitude * PI / 180.0) * cos(to.latitude * PI / 180.0) * sin(longitudeDelta / 2).pow(2)
    return 2.0 * EarthRadiusMeters * asin(min(1.0, kotlin.math.sqrt(a)))
}
