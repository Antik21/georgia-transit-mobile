package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.ProviderId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitArrival
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ScheduledStopTimeTest {
    @Test
    fun showsScheduledCountdownWithoutMarkingItAsLive() {
        val city = CityId("kutaisi")
        val route = TransitRoute(RouteId("kutaisi:azry:route:a"), city, "3", "Line 3", 0xFF11518A)
        val stop = TransitStop(
            id = StopId("kutaisi:azry:stop:a"),
            providerId = ProviderId("a"),
            code = "244",
            name = LocalizedText.fromLegacy("Central"),
            position = GeoPoint(42.27, 42.70),
            routeIds = listOf(route.id),
            mode = TransitMode.Bus,
        )
        val observed = Instant.parse("2026-11-19T09:00:00Z")
        val scheduled = TransitArrival(
            stopId = stop.id,
            routeId = route.id,
            tripId = null,
            headsign = LocalizedText.fromLegacy("Terminal"),
            scheduledAt = Instant.parse("2026-11-19T09:08:00Z"),
            expectedAt = null,
            expectedInMinutes = null,
            realtime = false,
            cancelled = false,
            source = ArrivalSource.Schedule,
        )
        val scheduledNow = scheduled.copy(scheduledAt = observed, expectedInMinutes = 0)
        val scheduledNowWithoutCountdown = scheduledNow.copy(expectedInMinutes = null)
        val liveNow = scheduledNow.copy(realtime = true, source = ArrivalSource.OfficialRealtime)
        val page = ArrivalPage(
            listOf(scheduled, scheduledNow, scheduledNowWithoutCountdown, liveNow),
            ArrivalSource.Schedule,
            observed,
            stale = false,
        )
        val mapper = StopArrivalsUiMapper(
            stop, TransitLocale.English, listOf(route), RouteSelectionProjection(), page, TransitFreshness.Network,
        )
        val rows = mapper.withArrivalPage(
            StopArrivalsSheetUi(stop.id, "Central"), page, TransitFreshness.Network,
        ).rows
        assertEquals(
            listOf(
                ArrivalSourceUi.Schedule to StopArrivalTimeUi.Minutes(0),
                ArrivalSourceUi.Schedule to StopArrivalTimeUi.Minutes(0),
                ArrivalSourceUi.OfficialRealtime to StopArrivalTimeUi.Arriving,
                ArrivalSourceUi.Schedule to StopArrivalTimeUi.Minutes(8),
            ),
            rows.map { it.source to it.time },
        )
    }
}
