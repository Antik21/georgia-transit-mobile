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
import com.denis.georgiatransit.shared.domain.model.TransitArrival
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
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MapBridgeTest {
    @Test
    fun stopToEtaAndDispose() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val city = TransitCity(CityId("test"), "Test", center = GeoPoint(41.7151, 44.8271), defaultZoom = 13.0,
                capabilities = CityCapabilities(true, false, true, false, false))
            val route = TransitRoute(RouteId("test:route"), city.id, "10", "Route 10", 0xFF0057B8)
            val stop = TransitStop(StopId("test:stop"), ProviderId("stop"), "001", LocalizedText.fromLegacy("Central"), city.center, listOf(route.id), TransitMode.Bus)
            var requestedCenter: GeoPoint? = null
            val repository = object : TransitRepository {
                override fun cities() = listOf(city)
                override fun routes(cityId: CityId) = listOf(route)
                override suspend fun nearbyStops(cityId: CityId, center: GeoPoint, radiusMeters: Int, limit: Int, locale: TransitLocale): TransitLoadResult<List<TransitStop>> {
                    requestedCenter = center
                    return TransitLoadResult.Data(listOf(stop), TransitFreshness.Network)
                }
                override suspend fun arrivals(cityId: CityId, stopId: StopId, limit: Int, locale: TransitLocale) =
                    TransitLoadResult.Data(ArrivalPage(listOf(TransitArrival(stop.id, route.id, tripId = null,
                        headsign = LocalizedText.fromLegacy("Airport"), scheduledAt = null, expectedAt = null,
                        expectedInMinutes = 4, realtime = false, cancelled = false, source = ArrivalSource.Schedule)),
                        ArrivalSource.Schedule, Instant.parse("2030-01-01T00:00:00Z"), false), TransitFreshness.Network)
            }
            val session = RuntimeTransitSession().also { it.selectCity(city) }
            val factory = viewModelFactory { initializer { MapViewModel(repository, session, RuntimeLocationSession(scope = backgroundScope)) } }
            val vm = ViewModelProvider.create(store, factory)["map", MapViewModel::class]
            vm.test(this) {
                var closed = false
                val entry = MapBridge(vm, store, city) { closed = true }
                try {
                    var latest: StopMapState? = null
                    var emissions = 0
                    entry.subscribe {
                        emissions++
                        if (emissions == 1) error("Host callback failure")
                        latest = it
                    }
                    this@runTest.runCurrent()
                    runOnCreate()
                    entry.setVisible(true)
                    this@runTest.runCurrent()
                    this@runTest.advanceTimeBy(351)
                    this@runTest.runCurrent()
                    val initial = assertNotNull(latest, "Map bridge did not emit state")
                    assertEquals(city.id.value, initial.cityId)
                    val camera = assertNotNull(initial.camera)
                    assertEquals(city.center.latitude, camera.latitude)
                    assertEquals(city.center.longitude, camera.longitude)
                    assertEquals(city.defaultZoom, camera.zoom)
                    val pannedCenter = GeoPoint(41.723, 44.841)
                    entry.updateViewport(pannedCenter.latitude, pannedCenter.longitude, 2_500, 16.0)
                    this@runTest.runCurrent()
                    this@runTest.advanceTimeBy(351)
                    this@runTest.runCurrent()
                    assertEquals(pannedCenter, requestedCenter)
                    val target = assertNotNull(latest).stops.single()
                    assertEquals(stop.position.latitude, target.latitude)
                    assertEquals(stop.position.longitude, target.longitude)
                    entry.selectStop(target.id, target.sourceRevision)
                    this@runTest.runCurrent()
                    val sheet = assertNotNull(latest?.arrivals, "The selected stop did not publish arrivals")
                    assertEquals("ready", sheet.status)
                    assertEquals(1, sheet.rows.size)
                    assertEquals("minutes", sheet.rows.single().timeKind)
                    assertEquals(4, sheet.rows.single().minutes)
                    assertEquals("#0057b8", sheet.rows.single().routeColor)
                    assertEquals("#ffffff", sheet.rows.single().routeTextColor)
                    val passing = sheet.routes.single()
                    assertEquals(listOf("10", "#0057b8", "#ffffff"), listOf(passing.label, passing.color, passing.textColor))
                    entry.dispose()
                    val count = emissions
                    entry.selectStop(target.id, target.sourceRevision)
                    this@runTest.advanceTimeBy(30_000)
                    this@runTest.runCurrent()
                    assertTrue(emissions > 1)
                    assertTrue(closed)
                    assertEquals(count, emissions)
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
}
