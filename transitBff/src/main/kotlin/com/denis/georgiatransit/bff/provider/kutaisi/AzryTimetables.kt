package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.RouteSchedule
import com.denis.georgiatransit.bff.api.ScheduledStop
import com.denis.georgiatransit.bff.cache.BoundedKeyedTtlCache
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

private val KutaisiZone = ZoneId.of("Asia/Tbilisi")
private val ServiceTime = Regex("(\\d{1,2}):([0-5]\\d)(?::([0-5]\\d))?")

internal class AzryTimetables(private val feed: AzryFeed) : AutoCloseable {
    private val cache = BoundedKeyedTtlCache<ScheduleKey, List<AzrySchedule>>(15.minutes, 256)

    suspend fun forDate(route: AzryRoute, pattern: AzryPattern, date: LocalDate, locale: String): RouteSchedule {
        val schedules = cache.getOrLoad(ScheduleKey(route.id, pattern.patternSuffix, locale)) {
            feed.schedule(route.id, pattern.patternSuffix, locale)
        }
        val dated = schedules.filter { date.toString() in it.serviceDates }
        if (dated.size > 1) invalidAzry()
        return RouteSchedule(
            routeId = azryPublicId(PublicEntityType.Route, route.id),
            directionId = azryDirectionId(route.id, pattern.patternSuffix),
            serviceDate = date.toString(),
            timeZone = KutaisiZone.id,
            stops = dated.singleOrNull()?.stops.orEmpty().sortedBy(AzryScheduleStop::position).map { stop ->
                val stopId = azryPublicId(PublicEntityType.Stop, stop.id)
                ScheduledStop(
                    stopId = stopId,
                    name = KutaisiStopNames.findForTimetable(stopId, stop.name) ?: azryText(stop.name),
                    arrivalTimes = stop.arrivalTimes.orEmpty().split(',').map(String::trim)
                        .filter { it.isNotEmpty() && it != "-" }
                        .map { instantForTime(date, it) }.distinct().sorted(),
                )
            },
        )
    }

    private fun instantForTime(date: LocalDate, value: String): String {
        val time = ServiceTime.matchEntire(value) ?: invalidAzry()
        val hour = time.groupValues[1].toLong()
        if (hour >= 48) invalidAzry()
        val minutes = time.groupValues[2].toLong()
        val seconds = time.groupValues[3].toLongOrNull() ?: 0
        return date.atStartOfDay(KutaisiZone).plusHours(hour).plusMinutes(minutes).plusSeconds(seconds)
            .toInstant().toString()
    }

    override fun close() = cache.close()

    private data class ScheduleKey(val routeId: String, val pattern: String, val locale: String)
}
