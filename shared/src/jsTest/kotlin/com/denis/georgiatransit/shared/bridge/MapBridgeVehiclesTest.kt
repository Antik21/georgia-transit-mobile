package com.denis.georgiatransit.shared.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.map.VehicleRealtimeClock
import com.denis.georgiatransit.shared.presentation.map.VehicleRealtimeTickerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class MapBridgeVehiclesTest {
    @Test
    fun vehiclesStreamSeparatelyAndStopWhenHiddenOrDisposed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val clock = clock()
            val repository = VehicleRepository(clock)
            val session = RuntimeTransitSession().also {
                it.selectCity(repository.city)
                it.selectRoutes(repository.city.id, setOf(routeId))
            }
            val factory = viewModelFactory {
                initializer { MapViewModel(repository, session, RuntimeLocationSession(scope = backgroundScope), clock, ticker) }
            }
            // Disposing the bridge clears this store, which is what stops the ViewModel's polling.
            val vm = ViewModelProvider.create(store, factory)["map", MapViewModel::class]
            vm.test(this) {
                var closed = false
                val entry = MapBridge(vm, store, repository.city) { closed = true }
                try {
                    var state: StopMapState? = null
                    var stateEmissions = 0
                    var layer: VehicleLayer? = null
                    var vehicleEmissions = 0
                    entry.subscribe { stateEmissions++; state = it }
                    entry.subscribeVehicles { vehicleEmissions++; layer = it }
                    this@runTest.runCurrent()
                    runOnCreate()
                    entry.setVisible(true)
                    this@runTest.runCurrent()
                    // Lets the debounced nearby-stop load settle so later state changes come only from vehicles.
                    this@runTest.advanceTimeBy(351)
                    this@runTest.runCurrent()

                    val vehicle = assertNotNull(layer?.vehicles?.singleOrNull(), "The bridge did not publish the vehicle")
                    assertEquals("vehicle-1", vehicle.id)
                    assertEquals(routeId.value, vehicle.routeId)
                    assertEquals("#0057b8", vehicle.color)
                    assertEquals(90.0, vehicle.bearing)
                    assertEquals("gps", vehicle.positionKind)
                    assertEquals(false, vehicle.stale)
                    assertEquals("live", assertNotNull(state).vehicleStatus)

                    val statesBeforeFrames = stateEmissions
                    val vehiclesBeforeFrames = vehicleEmissions
                    this@runTest.advanceTimeBy(ticker.pollIntervalMillis + ticker.frameIntervalMillis * 4)
                    this@runTest.runCurrent()
                    assertTrue(vehicleEmissions > vehiclesBeforeFrames, "Animation frames must reach the vehicle stream")
                    assertEquals(statesBeforeFrames, stateEmissions, "Animation frames must not rebuild the map state")

                    entry.selectVehicle(vehicle.id, assertNotNull(layer).revision)
                    entry.selectVehicle(vehicle.id, "not-a-revision")
                    this@runTest.runCurrent()

                    entry.setVisible(false)
                    this@runTest.runCurrent()
                    val hiddenRequests = repository.requests
                    this@runTest.advanceTimeBy(ticker.pollIntervalMillis * 3)
                    this@runTest.runCurrent()
                    assertEquals(hiddenRequests, repository.requests, "Hidden maps must not poll vehicles")

                    entry.setVisible(true)
                    this@runTest.runCurrent()
                    assertTrue(repository.requests > hiddenRequests, "Showing the map again must resume vehicle polling")
                    entry.dispose()
                    val disposedEmissions = vehicleEmissions
                    val disposedRequests = repository.requests
                    this@runTest.advanceTimeBy(ticker.pollIntervalMillis * 3)
                    this@runTest.runCurrent()
                    assertTrue(closed)
                    assertEquals(disposedEmissions, vehicleEmissions)
                    assertEquals(disposedRequests, repository.requests)
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

    private fun TestScope.clock() = object : VehicleRealtimeClock {
        override fun wallNow(): Instant = now + testScheduler.currentTime.toDuration(DurationUnit.MILLISECONDS)
        override fun monotonicNowMillis(): Long = testScheduler.currentTime
    }

    private class VehicleRepository(private val clock: VehicleRealtimeClock) : TransitRepository {
        val city = TransitCity(
            id = CityId("test-city"),
            name = "Test city",
            center = origin,
            capabilities = CityCapabilities(stops = true, vehicles = true, arrivals = false, routeShapes = false, journeyPlanning = false),
        )
        private val route = TransitRoute(routeId, city.id, "10", "Route 10", 0xFF0057B8)
        var requests = 0

        override fun cities() = listOf(city)
        override fun routes(cityId: CityId) = if (cityId == city.id) listOf(route) else emptyList()

        override suspend fun vehicles(cityId: CityId, routeId: RouteId, directionId: DirectionId?): TransitLoadResult<VehiclePage> {
            requests++
            val vehicle = TransitVehicle(
                id = VehicleId("vehicle-1"),
                routeId = routeId,
                directionId = DirectionId("outbound"),
                position = GeoPoint(origin.latitude, origin.longitude + requests * 0.0005),
                bearing = 90.0,
                nextStopId = null,
                observedAt = clock.wallNow(),
                ageSeconds = 0,
                positionKind = VehiclePositionKind.Gps,
            )
            return TransitLoadResult.Data(VehiclePage(listOf(vehicle), clock.wallNow(), maxAgeSeconds = 30, stale = false), TransitFreshness.Network)
        }

        override suspend fun nearbyStops(
            cityId: CityId,
            center: GeoPoint,
            radiusMeters: Int,
            limit: Int,
            locale: TransitLocale,
        ): TransitLoadResult<List<TransitStop>> = TransitLoadResult.Empty(TransitFreshness.Network)
    }

    private companion object {
        val now: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val origin = GeoPoint(41.715, 44.827)
        val routeId = RouteId("route-10")
        val ticker = object : VehicleRealtimeTickerPolicy {
            override val pollIntervalMillis: Long = 8_000
            override val frameIntervalMillis: Long = 50
        }
    }
}
