package com.denis.georgiatransit.shared.domain.interactor

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfiguration
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BootstrapTransitSessionTest {
    @Test
    fun firstLaunchWithoutCacheOpensCitySelection() = runTest {
        val store = FakeSelectedCityStore()
        val session = RuntimeTransitSession(store)
        val repository = FakeRepository(cities = listOf(city("tbilisi")))

        val result = bootstrap(store = store, session = session, repository = repository).bootstrap()

        assertEquals(BootstrapTransitSession.Result.OpenCitySelection, result)
        assertNull(session.selectedCity.value)
        assertEquals(0, repository.snapshotCalls)
    }

    @Test
    fun validPersistedCurrentCityOpensMapAndRefreshesItsSnapshot() = runTest {
        val cached = city("tbilisi", name = "Old Tbilisi")
        val refreshed = city("tbilisi", name = "Tbilisi")
        val store = FakeSelectedCityStore(snapshot = CachedCitySnapshot(city = cached))
        val session = RuntimeTransitSession(store)

        val result = bootstrap(
            store = store,
            session = session,
            cities = listOf(refreshed),
        ).bootstrap()

        assertEquals(BootstrapTransitSession.Result.OpenMap, result)
        assertEquals(refreshed, session.selectedCity.value)
        assertEquals(refreshed, store.savedCities.single())
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun removedDisabledAndInvalidCachedCitiesAreClearedBeforeCitySelection() = runTest {
        val scenarios = listOf(
            BootstrapScenario(
                name = "removed",
                cached = city("tbilisi"),
                currentCities = emptyList(),
            ),
            BootstrapScenario(
                name = "disabled",
                cached = city("tbilisi"),
                currentCities = listOf(city("tbilisi", stopsEnabled = false)),
            ),
            BootstrapScenario(
                name = "invalid cache",
                cached = city("", name = "Invalid"),
                currentCities = listOf(city("tbilisi")),
            ),
        )

        scenarios.forEach { scenario ->
            val store = FakeSelectedCityStore(snapshot = CachedCitySnapshot(city = scenario.cached))
            val session = RuntimeTransitSession(store)

            val result = bootstrap(
                store = store,
                session = session,
                cities = scenario.currentCities,
            ).bootstrap()

            assertEquals(BootstrapTransitSession.Result.OpenCitySelection, result, scenario.name)
            assertNull(session.selectedCity.value, scenario.name)
            assertTrue(store.clearCalls >= 1, scenario.name)
        }
    }

    @Test
    fun incompatibleSchemaAndMalformedCacheAreClearedAndIgnored() = runTest {
        val incompatibleStore = FakeSelectedCityStore(
            snapshot = CachedCitySnapshot(schemaVersion = CachedCitySnapshot.SchemaVersion + 1, city = city("tbilisi")),
        )
        val incompatibleResult = bootstrap(store = incompatibleStore).bootstrap()

        assertEquals(BootstrapTransitSession.Result.OpenCitySelection, incompatibleResult)
        assertTrue(incompatibleStore.clearCalls >= 1)

        val malformedStore = FakeSelectedCityStore(readFailure = IllegalArgumentException("bad cache"))
        val malformedResult = bootstrap(store = malformedStore).bootstrap()

        assertEquals(BootstrapTransitSession.Result.OpenCitySelection, malformedResult)
        assertTrue(malformedStore.clearCalls >= 1)
    }

    @Test
    fun repositoryTimeoutOrExceptionUsesValidCacheForOfflineMap() = runTest {
        listOf(
            suspend { delay(RuntimeBootstrapConfiguration.default.bootstrapTimeoutMillis + 1) },
            suspend { throw IllegalStateException("BFF unavailable") },
        ).forEachIndexed { index, loadSnapshot ->
            val cached = city("tbilisi")
            val store = FakeSelectedCityStore(snapshot = CachedCitySnapshot(city = cached))
            val session = RuntimeTransitSession(store)
            val repository = FakeRepository(cities = listOf(cached), loadSnapshot = loadSnapshot)

            val result = bootstrap(store = store, session = session, repository = repository).bootstrap()

            assertEquals(BootstrapTransitSession.Result.OpenMap, result, "failure $index")
            assertEquals(cached, session.selectedCity.value, "failure $index")
        }
    }

    @Test
    fun firstLaunchDoesNotMaskCatalogFailureInSplash() = runTest {
        listOf(
            FakeRepository(loadSnapshot = { delay(RuntimeBootstrapConfiguration.default.bootstrapTimeoutMillis + 1) }),
            FakeRepository(loadSnapshot = { throw IllegalStateException("BFF unavailable") }),
        ).forEach { repository ->
            assertEquals(
                BootstrapTransitSession.Result.OpenCitySelection,
                bootstrap(repository = repository).bootstrap(),
            )
            assertEquals(0, repository.snapshotCalls)
        }
    }

    @Test
    fun unavailableInvalidAndFailingRuntimeConfigurationNeverUsesCache() = runTest {
        val scenarios = listOf(
            RuntimeConfigurationScenario("timeout") { delay(1_001); RuntimeBootstrapConfiguration.default },
            RuntimeConfigurationScenario("exception") { throw IllegalStateException("settings unavailable") },
            RuntimeConfigurationScenario("null") { null },
            RuntimeConfigurationScenario("invalid") {
                RuntimeBootstrapConfiguration(
                    bootstrapTimeoutMillis = 999,
                    compatibleCachedCitySchemaVersions = setOf(CachedCitySnapshot.SchemaVersion),
                )
            },
        )

        scenarios.forEach { scenario ->
            val cached = city("tbilisi")
            val store = FakeSelectedCityStore(snapshot = CachedCitySnapshot(city = cached))
            val session = RuntimeTransitSession(store).also { it.selectCity(cached) }

            val result = bootstrap(
                store = store,
                session = session,
                configurationSource = FakeConfigurationSource(scenario.load),
            ).bootstrap()

            val expectedFailure = if (scenario.name == "invalid") {
                BootstrapTransitSession.Failure.RuntimeConfigurationInvalid
            } else {
                BootstrapTransitSession.Failure.RuntimeConfigurationUnavailable
            }
            assertEquals(BootstrapTransitSession.Result.Error(expectedFailure), result, scenario.name)
            assertNull(session.selectedCity.value, scenario.name)
            assertEquals(0, store.readCalls, scenario.name)
        }
    }

    @Test
    fun cancellationFromRuntimeConfigurationRepositoryAndPersistenceIsRethrown() = runTest {
        assertCancellationRethrown {
            bootstrap(
                configurationSource = FakeConfigurationSource { throw CancellationException("configuration cancelled") },
            ).bootstrap()
        }
        assertCancellationRethrown {
            bootstrap(
                repository = FakeRepository(loadSnapshot = { throw CancellationException("repository cancelled") }),
                store = FakeSelectedCityStore(snapshot = CachedCitySnapshot(city = city("tbilisi"))),
            ).bootstrap()
        }
        assertCancellationRethrown {
            bootstrap(
                store = FakeSelectedCityStore(readFailure = CancellationException("store cancelled")),
            ).bootstrap()
        }
    }

    @Test
    fun readSaveAndClearFailuresDoNotBlockTheCurrentBootstrapOutcome() = runTest {
        val readFailureStore = FakeSelectedCityStore(readFailure = IllegalStateException("read failed"))
        assertEquals(BootstrapTransitSession.Result.OpenCitySelection, bootstrap(store = readFailureStore).bootstrap())
        assertTrue(readFailureStore.clearCalls >= 1)

        val cached = city("tbilisi", name = "cached")
        val refreshed = city("tbilisi", name = "refreshed")
        val saveFailureStore = FakeSelectedCityStore(
            snapshot = CachedCitySnapshot(city = cached),
            saveFailure = IllegalStateException("save failed"),
        )
        val saveFailureSession = RuntimeTransitSession(saveFailureStore)
        assertEquals(
            BootstrapTransitSession.Result.OpenMap,
            bootstrap(
                store = saveFailureStore,
                session = saveFailureSession,
                cities = listOf(refreshed),
            ).bootstrap(),
        )
        assertEquals(refreshed, saveFailureSession.selectedCity.value)

        val clearFailureStore = FakeSelectedCityStore(
            snapshot = CachedCitySnapshot(city = city("removed")),
            clearFailure = IllegalStateException("clear failed"),
        )
        val clearFailureSession = RuntimeTransitSession(clearFailureStore)
        assertEquals(
            BootstrapTransitSession.Result.OpenCitySelection,
            bootstrap(store = clearFailureStore, session = clearFailureSession).bootstrap(),
        )
        assertNull(clearFailureSession.selectedCity.value)
        assertTrue(clearFailureStore.clearCalls >= 1)
    }

    private fun bootstrap(
        store: FakeSelectedCityStore = FakeSelectedCityStore(),
        session: RuntimeTransitSession = RuntimeTransitSession(store),
        repository: FakeRepository = FakeRepository(cities = listOf(city("tbilisi"))),
        configurationSource: RuntimeBootstrapConfigurationSource = FakeConfigurationSource { RuntimeBootstrapConfiguration.default },
        cities: List<TransitCity> = repository.cities(),
    ): BootstrapTransitSession = BootstrapTransitSession(
        repository = repository.copy(cities = cities),
        session = session,
        selectedCityStore = store,
        runtimeConfigurationSource = configurationSource,
    )

    private suspend fun assertCancellationRethrown(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected cancellation to be rethrown")
        } catch (failure: CancellationException) {
            assertTrue(failure.message?.contains("cancelled") == true)
        }
    }

    private data class BootstrapScenario(
        val name: String,
        val cached: TransitCity,
        val currentCities: List<TransitCity>,
    )

    private data class RuntimeConfigurationScenario(
        val name: String,
        val load: suspend () -> RuntimeBootstrapConfiguration?,
    )

    private class FakeConfigurationSource(
        private val loadConfiguration: suspend () -> RuntimeBootstrapConfiguration?,
    ) : RuntimeBootstrapConfigurationSource {
        override suspend fun load(): RuntimeBootstrapConfiguration? = loadConfiguration()
    }

    private data class FakeRepository(
        val cities: List<TransitCity> = emptyList(),
        val loadSnapshot: suspend () -> Unit = {},
    ) : TransitRepository {
        var snapshotCalls = 0
            private set

        override fun cities(): List<TransitCity> = cities

        override suspend fun loadCityCapabilitySnapshot(): List<TransitCity> {
            snapshotCalls += 1
            loadSnapshot()
            return cities
        }

        override fun routes(cityId: CityId): List<TransitRoute> = emptyList()
    }

    private class FakeSelectedCityStore(
        private var snapshot: CachedCitySnapshot? = null,
        private val readFailure: Throwable? = null,
        private val saveFailure: Throwable? = null,
        private val clearFailure: Throwable? = null,
    ) : SelectedCityStore {
        val savedCities = mutableListOf<TransitCity>()
        var readCalls = 0
            private set
        var clearCalls = 0
            private set

        override fun read(): CachedCitySnapshot? {
            readCalls += 1
            readFailure?.let { throw it }
            return snapshot
        }

        override fun save(city: TransitCity) {
            saveFailure?.let { throw it }
            savedCities += city
            snapshot = CachedCitySnapshot(city = city)
        }

        override fun clear() {
            clearCalls += 1
            clearFailure?.let { throw it }
            snapshot = null
        }
    }

    private companion object {
        fun city(
            id: String,
            name: String = id,
            stopsEnabled: Boolean = true,
        ) = TransitCity(
            id = CityId(id),
            name = name,
            countryCode = "GE",
            center = GeoPoint(41.7151, 44.8271),
            capabilities = CityCapabilities(
                stops = stopsEnabled,
                vehicles = true,
                arrivals = true,
                routeShapes = true,
                journeyPlanning = true,
            ),
        )
    }
}
