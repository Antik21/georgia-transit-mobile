package com.denis.georgiatransit.shared.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.ProviderId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.map.StopPinScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MapBridgeStopsTest {
    @Test
    fun stopLayerCarriesNativePinsAndTapsOpenEta() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val session = RuntimeTransitSession().also {
                it.selectCity(city)
                it.selectRoutes(city.id, setOf(blue.id, violet.id))
            }
            val factory = viewModelFactory { initializer { MapViewModel(Repository, session, RuntimeLocationSession(scope = backgroundScope)) } }
            val vm = ViewModelProvider.create(store, factory)["map", MapViewModel::class]
            vm.test(this) {
                val entry = MapBridge(vm, store, city) {}
                try {
                    var latest: StopMapState? = null
                    entry.subscribe { latest = it }
                    this@runTest.runCurrent()
                    runOnCreate()
                    entry.setVisible(true)
                    this@runTest.runCurrent()
                    this@runTest.advanceTimeBy(351)
                    this@runTest.runCurrent()

                    val layer = assertNotNull(latest?.stopLayer, "The bridge did not publish map stops")
                    val pins = layer.stops.associateBy { it.id }
                    val single = assertNotNull(pins[singleStop.id.value])
                    assertTrue(single.highlighted)
                    assertEquals("#0057b8", single.pin.color)
                    assertEquals(0, single.pin.badges.size)
                    assertEquals(StopPinScale.toDouble(), single.scale)
                    assertEquals("Single", single.label)

                    val shared = assertNotNull(pins[sharedStop.id.value])
                    assertEquals("#455a64", shared.pin.color)
                    assertEquals(listOf("10", "20"), shared.pin.badges.map { it.label })
                    assertEquals(listOf("#0057b8", "#7c3aed"), shared.pin.badges.map { it.color })
                    assertTrue(shared.pin.cardHeight > 0 && shared.pin.height > single.pin.height)

                    val outside = assertNotNull(pins[otherStop.id.value])
                    assertTrue(outside.outsideSelectedRoutes)
                    assertEquals("#eef1f5", outside.pin.color)
                    assertEquals("#344155", outside.pin.glyphColor)
                    assertEquals(StopPinScale * 0.75, outside.scale, 1e-6)
                    assertEquals(0, layer.clusters.size)

                    entry.tapStop(single.id, "${layer.revision}9")
                    this@runTest.runCurrent()
                    assertNull(latest?.arrivals, "A tap from a stale stop source must be ignored")
                    assertSame(layer, latest?.stopLayer, "Unchanged stops must keep the projected layer")

                    entry.tapStop(single.id, layer.revision)
                    this@runTest.runCurrent()
                    assertEquals(single.id, assertNotNull(latest?.arrivals, "A current tap must open arrivals").stopId)
                    val selected = assertNotNull(latest?.stopLayer).stops.single { it.id == single.id }
                    assertTrue(selected.selected)
                    assertEquals(StopPinScale * 1.1, selected.scale, 1e-6)
                    assertTrue(latest?.stopLayer?.revision != layer.revision)
                } finally {
                    entry.dispose()
                    cancelAndIgnoreRemainingItems()
                }
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private object Repository : TransitRepository {
        override fun cities() = listOf(city)
        override fun routes(cityId: CityId) = listOf(blue, violet)
        override suspend fun nearbyStops(cityId: CityId, center: GeoPoint, radiusMeters: Int, limit: Int, locale: TransitLocale) =
            TransitLoadResult.Data(listOf(singleStop, sharedStop, otherStop), TransitFreshness.Network)
        override suspend fun arrivals(cityId: CityId, stopId: StopId, limit: Int, locale: TransitLocale) =
            TransitLoadResult.Data(ArrivalPage(emptyList(), ArrivalSource.Schedule, Instant.parse("2030-01-01T00:00:00Z"), false),
                TransitFreshness.Network)
    }

    private companion object {
        val city = TransitCity(CityId("test"), "Test", center = GeoPoint(41.7151, 44.8271), defaultZoom = 16.0,
            capabilities = CityCapabilities(true, false, true, false, false))
        val blue = TransitRoute(RouteId("test:blue"), city.id, "10", "Route 10", 0xFF0057B8)
        val violet = TransitRoute(RouteId("test:violet"), city.id, "20", "Route 20", 0xFF7C3AED)
        val singleStop = stop("single", "Single", 0.0, listOf(blue.id))
        val sharedStop = stop("shared", "Shared", 0.0005, listOf(blue.id, violet.id))
        val otherStop = stop("other", "Other", -0.0005, listOf(RouteId("test:other")))

        fun stop(id: String, name: String, offset: Double, routes: List<RouteId>) = TransitStop(
            StopId("test:$id"), ProviderId(id), id, LocalizedText.fromLegacy(name),
            GeoPoint(city.center.latitude + offset, city.center.longitude), routes, TransitMode.Bus,
        )
    }
}
