package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.ConnectionErrorNotices
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapConnectionNoticeViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun runTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block(this)
    }

    @Test
    fun oneOutagePostsOneNoticeAndANewRouteSelectionCanPostAnother() = runTest {
        val city = TransitCity(
            id = CityId("batumi"),
            name = "Batumi",
            center = GeoPoint(latitude = 41.64, longitude = 41.64),
            capabilities = CityCapabilities(
                stops = false,
                vehicles = false,
                arrivals = false,
                routeShapes = false,
                journeyPlanning = false,
            ),
            defaultZoom = 13.0,
        )
        val route = TransitRoute(id = RouteId("opaque:1"), cityId = city.id, shortName = "1", name = "Route 1", colorArgb = 0xFF0057B8)
        val session = RuntimeTransitSession().also { it.restoreCitySelection(city, linkedSetOf()) }
        val notices = ConnectionErrorNotices()
        val viewModel = MapViewModel(
            repository = RoutesOnlyRepository(listOf(route)),
            session = session,
            locationSession = RuntimeLocationSession(scope = this, nowMillis = { testScheduler.currentTime }),
            connectionNotices = notices,
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()

            notices.onTransportFailure()
            notices.onTransportFailure()
            this@runTest.runCurrent()
            assertEquals(1, drainConnectionNotices())

            assertTrue(session.selectRoutes(city.id, setOf(route.id)))
            this@runTest.runCurrent()
            notices.onTransportFailure()
            this@runTest.runCurrent()
            assertEquals(1, drainConnectionNotices())
            cancelAndIgnoreRemainingItems()
        }
    }

    private suspend fun OrbitTestContext<ViewState, SideEffect, MapViewModel>.drainConnectionNotices(): Int {
        var count = 0
        while (true) {
            val item = runCatching { awaitItem() }.getOrNull() ?: return count
            if (item is Item.SideEffectItem && item.value == SideEffect.ShowConnectionError) count++
        }
    }

    private class RoutesOnlyRepository(private val routeList: List<TransitRoute>) : TransitRepository {
        override fun cities(): List<TransitCity> = emptyList()

        override fun routes(cityId: CityId): List<TransitRoute> = routeList.filter { it.cityId == cityId }
    }
}
