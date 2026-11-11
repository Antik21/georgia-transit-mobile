package com.denis.georgiatransit.shared.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapBridgeLocationTest {
    @Test
    fun hostCommandsAndEventsDriveTheSharedLocationFlow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = EmptyRepository()
            val session = RuntimeTransitSession().also { it.selectCity(repository.city) }
            val locationSession = RuntimeLocationSession(scope = backgroundScope, nowMillis = { now })
            val factory = viewModelFactory {
                initializer { MapViewModel(repository, session, locationSession) }
            }
            val vm = ViewModelProvider.create(store, factory)["map", MapViewModel::class]
            // Orbit's test mode captures side effects, so this test runs the real container and its dispatcher.
            run {
                val entry = MapBridge(vm, store, repository.city, locationSession = locationSession) {}
                try {
                    var state: StopMapState? = null
                    entry.subscribe { state = it }
                    settle()
                    entry.setVisible(true)
                    settle()

                    entry.locationPermission("notDetermined", precise = true, canRetry = false)
                    entry.myLocation()
                    settle()

                    val commands = mutableListOf<LocationCommand>()
                    entry.subscribeLocationCommands { commands += it }
                    assertEquals(listOf("requestPermission"), commands.map { it.kind }, "Commands issued before the host subscribed must be delivered")
                    assertEquals("notDetermined", assertNotNull(state?.location).permission)

                    entry.locationPermission("not-a-permission", precise = true, canRetry = true)
                    settle()
                    assertEquals("notDetermined", assertNotNull(state?.location).permission, "Unknown host states must be ignored")

                    entry.locationPermission("granted", precise = true, canRetry = false)
                    settle()
                    val request = commands.last()
                    assertEquals("requestLocation", request.kind, "Granting access must start a location request")
                    assertEquals(false, request.isRefresh)
                    assertTrue(assertNotNull(state?.location).locating)
                    assertEquals(false, state?.location?.actionable)

                    val cameraBefore = state?.camera?.revision
                    entry.locationFix(request.id, fix.latitude, fix.longitude, 20.0, now.toDouble())
                    settle()
                    val location = assertNotNull(state?.userLocation, "An accepted fix must reach the map")
                    assertEquals(fix.latitude, location.latitude)
                    assertTrue(location.showDot)
                    assertEquals(65, location.accuracyRing.size)
                    assertEquals(false, state?.location?.locating)
                    assertTrue(state?.camera?.revision != cameraBefore, "The first fix recenters the map")

                    entry.myLocation()
                    settle()
                    val retry = commands.last()
                    assertEquals("requestLocation", retry.kind)
                    entry.locationFailed(retry.id, "timedOut")
                    settle()
                    assertEquals("timedOut", state?.location?.failure)

                    entry.locationPermission("granted", precise = false, canRetry = false)
                    settle()
                    assertNull(state?.userLocation, "A precise fix must not survive a switch to approximate access")
                    assertEquals(false, state?.location?.precise)

                    assertTrue(locationSession.state.value.activeRequestId != null, "Approximate access starts a new request")
                    entry.dispose()
                    assertNull(locationSession.state.value.activeRequestId, "Disposal must not leave an orphaned shared request")
                    val delivered = commands.size
                    entry.myLocation()
                    settle()
                    assertEquals(delivered, commands.size)
                } finally {
                    entry.dispose()
                }
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private suspend fun TestScope.settle() {
        repeat(5) {
            withContext(Dispatchers.Default) { delay(20) }
            runCurrent()
        }
    }

    private class EmptyRepository : TransitRepository {
        val city = TransitCity(
            id = CityId("test-city"),
            name = "Test city",
            center = GeoPoint(41.70, 44.80),
            capabilities = CityCapabilities(stops = true, vehicles = false, arrivals = false, routeShapes = false, journeyPlanning = false),
        )

        override fun cities() = listOf(city)
        override fun routes(cityId: CityId) = emptyList<TransitRoute>()
        override suspend fun nearbyStops(
            cityId: CityId,
            center: GeoPoint,
            radiusMeters: Int,
            limit: Int,
            locale: TransitLocale,
        ): TransitLoadResult<List<TransitStop>> = TransitLoadResult.Empty(TransitFreshness.Network)
    }

    private companion object {
        const val now = 1_900_000_000_000L
        val fix = GeoPoint(41.715, 44.827)
    }
}
