package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneyPlanMode
import com.denis.georgiatransit.shared.domain.model.JourneyPlanQuery
import com.denis.georgiatransit.shared.domain.model.JourneyTimingQuality
import com.denis.georgiatransit.shared.domain.model.JourneyWaitBasis
import com.denis.georgiatransit.shared.domain.model.TransitLocale

internal fun JourneyPlanQuery.toDto() = JourneyPlanRequestDto(
    from = from.toDto(),
    to = to.toDto(),
    departureAt = departureAt?.toString(),
    maxTransfers = maxTransfers,
    mode = when (mode) {
        JourneyPlanMode.Fastest -> "FASTEST"
        JourneyPlanMode.LessWalking -> "LESS_WALKING"
        JourneyPlanMode.FewerTransfers -> "FEWER_TRANSFERS"
    },
    transferBufferSeconds = transferBufferSeconds,
    locale = when (locale) {
        TransitLocale.Georgian -> "ka"
        TransitLocale.English -> "en"
        TransitLocale.Russian -> "ru"
    },
)

/**
 * The plan page is a `CLIENT_ESTIMATE` (ADR 0022). One malformed option is dropped instead of
 * failing the whole answer; a page whose every option is malformed is still a contract failure.
 */
internal fun JourneyPageDto.toPlanDomain(): JourneyPage {
    val journeys = items.mapNotNull { item -> runCatching { item.toDomain() }.getOrNull() }
    require(items.isEmpty() || journeys.isNotEmpty()) { "every journey option is invalid" }
    return JourneyPage(
        items = journeys.take(MaxPlanOptions),
        observedAt = observedAt.toUtcInstant("journey page observedAt"),
        source = source.toDomain(),
        realtime = realtime,
        stale = stale,
    )
}

internal fun Double?.nonNegativeOrNull(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }

internal fun String?.toWaitBasisOrNull(): JourneyWaitBasis? = when (this) {
    "LIVE_VEHICLE" -> JourneyWaitBasis.LiveVehicle
    "HEADWAY_ESTIMATE" -> JourneyWaitBasis.HeadwayEstimate
    else -> null
}

internal fun String?.toTimingQualityOrNull(): JourneyTimingQuality? = when (this) {
    "LIVE" -> JourneyTimingQuality.Live
    "MIXED" -> JourneyTimingQuality.Mixed
    "ESTIMATED" -> JourneyTimingQuality.Estimated
    else -> null
}

private const val MaxPlanOptions = 3
