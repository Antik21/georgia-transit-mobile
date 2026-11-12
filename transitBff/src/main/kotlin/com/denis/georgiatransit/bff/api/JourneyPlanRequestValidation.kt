package com.denis.georgiatransit.bff.api

import com.denis.georgiatransit.bff.planning.JourneyPlanMode
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.planning.PlannerParameters
import java.time.Duration
import java.time.Instant

private val MaximumPastDeparture = Duration.ofMinutes(5)
private val MaximumFutureDeparture = Duration.ofHours(2)
private const val MaximumTransferBufferSeconds = 600

/** Error messages are fixed text: no coordinate or other request value is ever reflected. */
internal fun JourneyPlanRequest.toQuery(now: Instant): JourneyPlanQuery {
    val departure = departureAt?.let { raw ->
        if (!raw.endsWith("Z")) throw InvalidArgument("departureAt must be an ISO-8601 UTC timestamp")
        try {
            Instant.parse(raw)
        } catch (_: Exception) {
            throw InvalidArgument("departureAt must be an ISO-8601 UTC timestamp")
        }
    } ?: now
    if (departure.isBefore(now.minus(MaximumPastDeparture)) || departure.isAfter(now.plus(MaximumFutureDeparture))) {
        throw InvalidArgument("departureAt must be within five minutes in the past and two hours ahead")
    }
    if (maxTransfers !in 0..PlannerParameters.MaximumTransfers) {
        throw InvalidArgument("maxTransfers must be between 0 and ${PlannerParameters.MaximumTransfers}")
    }
    if (transferBufferSeconds !in 0..MaximumTransferBufferSeconds) {
        throw InvalidArgument("transferBufferSeconds must be between 0 and $MaximumTransferBufferSeconds")
    }
    val planMode = JourneyPlanMode.entries.firstOrNull { it.name == mode }
        ?: throw InvalidArgument("mode must be one of FASTEST, LESS_WALKING, FEWER_TRANSFERS")
    return JourneyPlanQuery(
        from = validateGeoPoint(from),
        to = validateGeoPoint(to),
        departureAt = departure,
        maxTransfers = maxTransfers,
        mode = planMode,
        transferBufferSeconds = transferBufferSeconds,
        locale = validateLocale(locale),
    )
}
