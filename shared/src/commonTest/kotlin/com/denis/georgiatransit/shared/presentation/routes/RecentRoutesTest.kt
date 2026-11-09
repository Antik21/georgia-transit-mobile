package com.denis.georgiatransit.shared.presentation.routes

import com.denis.georgiatransit.shared.data.cache.TransitCache
import com.denis.georgiatransit.shared.data.cache.TransitCacheStore
import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.data.repository.StoredRecentRouteHistory
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RecentRoutesTest {
    @Test
    fun storesEightUniqueRoutesInRecencyOrderAndSeparatesCitiesAcrossRecreation() = runTest {
        val store = MemoryStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val history = StoredRecentRouteHistory(TransitCache(store), dispatcher)
        val batumi = CityId("batumi")
        val tbilisi = CityId("tbilisi")
        val routes = (1..10).map { RouteId("route:$it") }
        history.record(batumi, routes.take(9).toSet())
        history.record(batumi, setOf(routes[4], routes[9]))
        history.record(tbilisi, setOf(routes.first()))
        history.record(batumi, emptySet())

        val recreated = StoredRecentRouteHistory(TransitCache(store), dispatcher)
        assertEquals(listOf(10, 5, 9, 8, 7, 6, 4, 3).map { RouteId("route:$it") }, recreated.read(batumi))
        assertEquals(listOf(routes.first()), recreated.read(tbilisi))
    }

    @Test
    fun historyUsesTheFullCatalogAndSharesTheDraftThroughSearchUntilConfirmation() = runTest {
        val repository = PreviewTransitRepository()
        val city = repository.cities().first()
        val routes = repository.routes(city.id)
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val history = StoredRecentRouteHistory(TransitCache(MemoryStore()), StandardTestDispatcher(testScheduler))
        val unavailable = RouteId("removed")
        history.record(city.id, setOf(routes.first().id, unavailable))
        val viewModel = RoutesViewModel(repository, session, history)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()
            assertEquals(listOf(routes.first().id), viewModel.container.stateFlow.value.recentRoutes.map { it.id })

            routes.forEach { viewModel.dispatchAction(Action.RouteToggled(it.id)) }
            viewModel.dispatchAction(Action.SearchChanged("airport"))
            this@runTest.runCurrent()
            val selected = routes.map { it.id }.toSet()
            val filtered = viewModel.container.stateFlow.value
            assertEquals(selected, filtered.selectedIds)
            assertEquals(listOf(routes[1].id), filtered.visibleRoutes.map { it.id })
            assertEquals(listOf(routes.first().id), filtered.recentRoutes.map { it.id })
            assertEquals(emptySet(), session.selectedRouteIds.value)

            viewModel.dispatchAction(Action.RouteToggled(filtered.recentRoutes.single().id))
            this@runTest.runCurrent()
            val updated = selected - routes.first().id
            assertEquals(updated, viewModel.container.stateFlow.value.selectedIds)
            assertEquals(listOf(unavailable, routes.first().id), history.read(city.id))

            viewModel.dispatchAction(Action.ConfirmClicked)
            this@runTest.runCurrent()
            assertEquals(updated, session.selectedRouteIds.value)
            assertEquals(listOf(routes[2].id, routes[1].id, unavailable, routes[0].id), history.read(city.id))
            cancelAndIgnoreRemainingItems()
        }
    }

    private class MemoryStore : TransitCacheStore {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun keys(prefix: String): List<String> = values.keys.filter { it.startsWith(prefix) }
    }
}
