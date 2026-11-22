package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.domain.analytics.AnalyticsClient
import com.denis.georgiatransit.shared.domain.analytics.AnalyticsEvent
import com.denis.georgiatransit.shared.domain.analytics.AnalyticsRecord
import com.denis.georgiatransit.shared.domain.analytics.ProductAnalytics
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.ProviderId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitStop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StopArrivalsAnalyticsTest {
    @Test
    fun tracksOpeningSwitchingAndReopeningWithTheStopCity() = runTest {
        val repository = PreviewTransitRepository()
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val batumi = repository.cities().first { it.id.value == "batumi" }
        val records = mutableListOf<AnalyticsRecord>()
        val analytics = ProductAnalytics(
            AnalyticsClient.Android, "device", backgroundScope, { 1_000L }, { records.add(it) },
        )
        val arrivals = StopArrivalsFeature(repository, analytics)
        val first = stop(tbilisi, "first")
        val second = stop(tbilisi, "second")

        arrivals.open(first, tbilisi)
        arrivals.open(first, tbilisi)
        arrivals.open(second, tbilisi)
        arrivals.reset()
        arrivals.open(second, tbilisi)
        arrivals.reset()
        arrivals.open(stop(batumi, "first"), batumi)
        runCurrent()

        val openings = records.mapNotNull { it.event as? AnalyticsEvent.StopOpened }
        assertEquals(listOf(tbilisi.id, tbilisi.id, tbilisi.id, batumi.id), openings.map { it.cityId })
        assertEquals(4, openings.map { it.eventId }.toSet().size)
        assertEquals(setOf(1_000L), records.map { it.sessionId }.toSet())
    }

    private fun stop(city: TransitCity, id: String) = TransitStop(
        id = StopId("${city.id.value}:fixture:stop:$id"),
        providerId = ProviderId("fixture"),
        code = id,
        name = LocalizedText(ru = id, en = id, ka = id),
        position = city.center,
        routeIds = emptyList(),
        mode = TransitMode.Bus,
    )
}
