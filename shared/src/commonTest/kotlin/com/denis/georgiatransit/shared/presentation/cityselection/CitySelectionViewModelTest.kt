package com.denis.georgiatransit.shared.presentation.cityselection

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationFixCandidate
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CitySelectionViewModelTest {
    @Test
    fun disabledTbilisiKeepsItsPublicAttributionVisibleWithoutBecomingSelectable() = runTest {
        val attribution = listOf(
            TransitAttribution(
                id = "transitous",
                label = LocalizedText(ru = "Источники Transitous", en = "Transitous sources", ka = "Transitous-ის წყაროები"),
                url = "https://transitous.org/sources/",
            ),
            TransitAttribution(
                id = "openstreetmap",
                label = LocalizedText(
                    ru = "© участники OpenStreetMap (ODbL)",
                    en = "© OpenStreetMap contributors (ODbL)",
                    ka = "© OpenStreetMap-ის მონაწილეები (ODbL)",
                ),
                url = "https://www.openstreetmap.org/copyright",
            ),
        )
        val disabledTbilisi = tbilisi.copy(
            capabilities = tbilisi.capabilities.copy(stops = false),
            attribution = attribution,
        )
        val viewModel = CitySelectionViewModel(
            CatalogRepository(results = mutableListOf(TransitLoadResult.Data(listOf(disabledTbilisi), TransitFreshness.Network))),
            RuntimeTransitSession(),
            testLocationSession(),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()

            val item = viewModel.container.stateFlow.value.cities.single()
            assertEquals(disabledTbilisi.id, item.id)
            assertFalse(item.isEnabled)
            assertEquals(attribution, item.attribution)
            assertEquals(
                listOf("https://transitous.org/sources/", "https://www.openstreetmap.org/copyright"),
                item.attribution.map(TransitAttribution::url),
            )

            viewModel.dispatchAction(Action.CityClicked(disabledTbilisi.id))
            this@runTest.runCurrent()
            assertNull(viewModel.container.stateFlow.value.selectedCityId)
            assertFalse(viewModel.container.stateFlow.value.canContinue)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun remoteSnapshotControlsWhichCitiesAreVisibleAndSelectable() = runTest {
        val repository = CatalogRepository(
            results = mutableListOf(
                TransitLoadResult.Data(
                    listOf(tbilisi, batumi, kutaisi(stopsEnabled = false)),
                    TransitFreshness.Network,
                ),
            ),
        )
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()

            val state = viewModel.container.stateFlow.value
            assertEquals(CityCatalogState.Populated(TransitFreshness.Network), state.catalog)
            assertEquals(listOf("tbilisi", "batumi", "kutaisi"), state.cities.map { it.id.value })
            assertTrue(state.cities.first { it.id == tbilisi.id }.isEnabled)
            assertTrue(state.cities.first { it.id == batumi.id }.isEnabled)
            assertFalse(state.cities.first { it.id.value == "kutaisi" }.isEnabled)

            viewModel.dispatchAction(Action.CityClicked(CityId("kutaisi")))
            this@runTest.runCurrent()
            assertNull(viewModel.container.stateFlow.value.selectedCityId)

            viewModel.dispatchAction(Action.CityClicked(batumi.id))
            this@runTest.runCurrent()
            assertEquals(batumi.id, viewModel.container.stateFlow.value.selectedCityId)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun absentKutaisiIsNotInventedFromAClientCatalog() = runTest {
        val repository = CatalogRepository(
            results = mutableListOf(TransitLoadResult.Data(listOf(tbilisi, batumi), TransitFreshness.Network)),
        )
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()

            assertEquals(listOf(tbilisi.id, batumi.id), viewModel.container.stateFlow.value.cities.map { it.id })
            assertFalse(viewModel.container.stateFlow.value.cities.any { it.id.value == "kutaisi" })
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun enabledKutaisiIsSelectableOnlyWhenTheRemoteSnapshotEnablesStops() = runTest {
        val enabledKutaisi = kutaisi(stopsEnabled = true)
        val repository = CatalogRepository(
            results = mutableListOf(TransitLoadResult.Data(listOf(enabledKutaisi), TransitFreshness.Network)),
        )
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.CityClicked(enabledKutaisi.id))
            this@runTest.runCurrent()

            assertEquals(enabledKutaisi.id, viewModel.container.stateFlow.value.selectedCityId)
            assertTrue(viewModel.container.stateFlow.value.canContinue)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun catalogFreshnessAndStaleRevalidationFailureAreRenderedFromTheRepositoryResult() = runTest {
        val staleFailure = TransitFailure.Transport("offline")
        val repository = CatalogRepository(
            results = mutableListOf(
                TransitLoadResult.Data(listOf(tbilisi), TransitFreshness.Network),
                TransitLoadResult.Data(listOf(tbilisi), TransitFreshness.CacheValid),
                TransitLoadResult.Data(
                listOf(tbilisi),
                TransitFreshness.StaleOffline,
                revalidationFailure = staleFailure,
                ),
            ),
        )
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(CityCatalogState.Populated(TransitFreshness.Network), viewModel.container.stateFlow.value.catalog)

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()
            assertEquals(CityCatalogState.Populated(TransitFreshness.CacheValid), viewModel.container.stateFlow.value.catalog)

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()
            assertEquals(
                CityCatalogState.Populated(TransitFreshness.StaleOffline, staleFailure),
                viewModel.container.stateFlow.value.catalog,
            )
            assertEquals(listOf(tbilisi.id), viewModel.container.stateFlow.value.cities.map { it.id })
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun emptyCatalogResultCannotBeUsedForConfirmation() = runTest {
        val repository = CatalogRepository(
            results = mutableListOf(TransitLoadResult.Empty(TransitFreshness.StaleOffline)),
        )
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())
        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(CityCatalogState.Empty(TransitFreshness.StaleOffline), viewModel.container.stateFlow.value.catalog)
            assertFalse(viewModel.container.stateFlow.value.canContinue)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun failureCatalogResultIsRetryableAndRetryUsesTheRepositoryAgain() = runTest {
        val repository = CatalogRepository(
            results = mutableListOf(
                TransitLoadResult.Failure(TransitFailure.Transport("offline")),
                TransitLoadResult.Data(listOf(tbilisi), TransitFreshness.Network),
            ),
        )
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())
        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(
                CityCatalogState.RetryableError(TransitFailure.Transport("offline")),
                viewModel.container.stateFlow.value.catalog,
            )

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()
            assertEquals(CityCatalogState.Populated(TransitFreshness.Network), viewModel.container.stateFlow.value.catalog)
            assertEquals(2, repository.refreshCalls)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun nonTransportRepositoryFailureStillKeepsTheCatalogRetryable() = runTest {
        val failure = TransitFailure.Configuration("invalid endpoint")
        val repository = CatalogRepository(results = mutableListOf(TransitLoadResult.Failure(failure)))
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())
        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(CityCatalogState.RetryableError(failure), viewModel.container.stateFlow.value.catalog)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun retryRequestsAreSingleFlightWhileARefreshIsInProgress() = runTest {
        lateinit var viewModel: CitySelectionViewModel
        val repository = CatalogRepository(
            results = mutableListOf(TransitLoadResult.Data(listOf(tbilisi), TransitFreshness.Network)),
            onRefresh = {
                viewModel.dispatchAction(Action.RetryClicked)
                viewModel.dispatchAction(Action.RetryClicked)
            },
        )
        viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()

            assertEquals(1, repository.refreshCalls)
            assertEquals(CityCatalogState.Populated(TransitFreshness.Network), viewModel.container.stateFlow.value.catalog)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cancelledCatalogRefreshReleasesRetryForTheNextSuccessfulRequest() = runTest {
        val repository = CatalogRepository(
            results = mutableListOf(TransitLoadResult.Data(listOf(tbilisi), TransitFreshness.Network)),
            cancelFirstRefresh = true,
        )
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(CityCatalogState.Loading, viewModel.container.stateFlow.value.catalog)

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()
            assertEquals(2, repository.refreshCalls)
            assertEquals(CityCatalogState.Populated(TransitFreshness.Network), viewModel.container.stateFlow.value.catalog)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun refreshRetainsTheVisibleSelectionWhenTheReplacementSnapshotStillEnablesIt() = runTest {
        val refreshedTbilisi = tbilisi.copy(name = "Refreshed Tbilisi")
        val repository = CatalogRepository(
            results = mutableListOf(
                TransitLoadResult.Data(listOf(tbilisi, batumi), TransitFreshness.Network),
                TransitLoadResult.Data(listOf(refreshedTbilisi, batumi), TransitFreshness.Network),
            ),
        )
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
            this@runTest.runCurrent()

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()

            assertEquals(tbilisi.id, viewModel.container.stateFlow.value.selectedCityId)
            assertTrue(viewModel.container.stateFlow.value.canContinue)
            assertEquals(
                refreshedTbilisi.name,
                viewModel.container.stateFlow.value.cities.single { it.id == tbilisi.id }.name,
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun refreshClearsTheVisibleSelectionWhenTheReplacementSnapshotRemovesOrDisablesIt() = runTest {
        val disabledTbilisi = tbilisi.copy(capabilities = tbilisi.capabilities.copy(stops = false))
        val scenarios = listOf(
            "removed" to listOf(batumi),
            "disabled" to listOf(disabledTbilisi, batumi),
        )

        scenarios.forEach { (name, replacementSnapshot) ->
            val repository = CatalogRepository(
                results = mutableListOf(
                    TransitLoadResult.Data(listOf(tbilisi, batumi), TransitFreshness.Network),
                    TransitLoadResult.Data(replacementSnapshot, TransitFreshness.Network),
                ),
            )
            val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), testLocationSession())

            viewModel.test(this) {
                runOnCreate()
                this@runTest.runCurrent()
                viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
                this@runTest.runCurrent()

                viewModel.dispatchAction(Action.RetryClicked)
                this@runTest.runCurrent()

                assertNull(viewModel.container.stateFlow.value.selectedCityId, name)
                assertFalse(viewModel.container.stateFlow.value.canContinue, name)
                cancelAndIgnoreRemainingItems()
            }
        }
    }

    @Test
    fun doubleContinuePersistsAndNavigatesExactlyOnce() = runTest {
        val persisted = mutableListOf<TransitCity>()
        val session = RuntimeTransitSession(
            selectedCityStore = object : com.denis.georgiatransit.shared.domain.repository.SelectedCityStore {
                override fun read() = null
                override fun save(city: TransitCity) {
                    persisted += city
                }
                override fun clear() = Unit
            },
        )
        val repository = CatalogRepository(
            results = mutableListOf(TransitLoadResult.Data(listOf(tbilisi), TransitFreshness.Network)),
        )
        val viewModel = CitySelectionViewModel(repository, session, testLocationSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
            this@runTest.runCurrent()

            viewModel.dispatchAction(Action.ContinueClicked)
            viewModel.dispatchAction(Action.ContinueClicked)
            this@runTest.runCurrent()

            assertEquals(listOf(tbilisi), persisted)
            assertEquals(tbilisi, session.selectedCity.value)
            assertEquals(NavigationEffect.OpenMap, awaitSideEffectIgnoringStates())
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cancelledPersistenceReleasesConfirmationForOneLaterSuccessfulAttempt() = runTest {
        val persisted = mutableListOf<TransitCity>()
        val store = object : SelectedCityStore {
            var saveCalls = 0

            override fun read() = null

            override fun save(city: TransitCity) {
                saveCalls += 1
                if (saveCalls == 1) throw CancellationException("write cancelled")
                persisted += city
            }

            override fun clear() = Unit
        }
        val repository = CatalogRepository(
            results = mutableListOf(TransitLoadResult.Data(listOf(tbilisi), TransitFreshness.Network)),
        )
        val viewModel = CitySelectionViewModel(
            repository = repository,
            session = RuntimeTransitSession(store),
            locationSession = testLocationSession(),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
            this@runTest.runCurrent()

            viewModel.dispatchAction(Action.ContinueClicked)
            this@runTest.runCurrent()
            assertFalse(viewModel.container.stateFlow.value.isConfirming)

            viewModel.dispatchAction(Action.ContinueClicked)
            this@runTest.runCurrent()
            assertEquals(listOf(tbilisi), persisted)
            assertEquals(2, store.saveCalls)
            assertEquals(NavigationEffect.OpenMap, awaitSideEffectIgnoringStates())
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueUpdatesSessionBeforeOpenMapEffectIsObserved() = runTest {
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession()
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val cities = repository.cities().map { city ->
            CityItemUiModel(
                id = city.id,
                name = city.name,
                isEnabled = city.capabilities.stops,
                isExperimental = city.capabilities.experimental,
            )
        }
        val viewModel = CitySelectionViewModel(repository, session, RuntimeLocationSession(scope = this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(cityState(cities))

            viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
            this@runTest.runCurrent()
            expectState(cityState(cities, selectedCityId = tbilisi.id))

            viewModel.dispatchAction(Action.ContinueClicked)
            this@runTest.runCurrent()

            expectState { copy(isConfirming = true) }
            expectSideEffect(NavigationEffect.OpenMap)
            assertEquals(tbilisi, session.selectedCity.value)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun grantingPermissionStartsOneShotRequestAndAcceptedFixEntersSharedSession() = runTest {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession()
        val locationSession = testLocationSession()
        val viewModel = CitySelectionViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(cityState(repository.cityItems()))

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()
            val command = awaitLocationCommand()
            assertTrue(command is LocationPlatformCommand.RequestLocation)
            assertEquals(command.id, locationSession.state.value.activeRequestId)
            assertTrue(locationSession.state.value.isLocating)

            val candidate = validCandidate()
            viewModel.dispatchAction(
                Action.LocationEventReceived(LocationPlatformEvent.FixReceived(command.id, candidate)),
            )
            this@runTest.runCurrent()

            val fix = requireNotNull(locationSession.state.value.fix)
            assertEquals(candidate.latitude, fix.point.latitude)
            assertEquals(candidate.longitude, fix.point.longitude)
            assertEquals(LocationPrecision.Precise, fix.precision)
            assertFalse(locationSession.state.value.isLocating)
            assertEquals(fix, viewModel.container.stateFlow.value.location.fix)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun denialDoesNotBlockManualCityContinue() = runTest {
        assertPermissionDoesNotBlockContinue(LocationPermissionState.Denied)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun settingsRequiredDoesNotBlockManualCityContinue() = runTest {
        assertPermissionDoesNotBlockContinue(LocationPermissionState.SettingsRequired)
    }

    private suspend fun TestScope.assertPermissionDoesNotBlockContinue(permission: LocationPermissionState) {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession()
        val locationSession = testLocationSession().also { it.updatePermission(permission) }
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val viewModel = CitySelectionViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@assertPermissionDoesNotBlockContinue.runCurrent()
            expectCatalogLoading(locationSession.state.value)
            expectState(cityState(repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
            this@assertPermissionDoesNotBlockContinue.runCurrent()
            expectState { copy(selectedCityId = tbilisi.id) }
            viewModel.dispatchAction(Action.ContinueClicked)
            this@assertPermissionDoesNotBlockContinue.runCurrent()

            assertEquals(NavigationEffect.OpenMap, awaitSideEffectIgnoringStates())
            assertEquals(tbilisi, transitSession.selectedCity.value)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun settingsRequiredLocationActionOpensAppSettings() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSession().also {
            it.updatePermission(LocationPermissionState.SettingsRequired)
        }
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectCatalogLoading(locationSession.state.value)
            expectState(cityState(repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationClicked)
            this@runTest.runCurrent()

            assertTrue(awaitLocationCommand() is LocationPlatformCommand.OpenAppSettings)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun resumePermissionEventDoesNotDuplicateRequestWhenFreshFixExists() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSessionWithFix()
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectCatalogLoading(locationSession.state.value)
            expectState(cityState(repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()

            assertTrue(locationSession.state.value.fix != null)
            assertNull(locationSession.state.value.activeRequestId)
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun explicitRefreshClearsFreshFixAndStartsNewAcquisition() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSessionWithFix()
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectCatalogLoading(locationSession.state.value)
            expectState(cityState(repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationClicked)
            this@runTest.runCurrent()
            val command = awaitLocationCommand()

            assertTrue(command is LocationPlatformCommand.RequestLocation)
            assertNull(locationSession.state.value.fix)
            assertTrue(locationSession.state.value.isLocating)
            assertEquals(command.id, locationSession.state.value.activeRequestId)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun correlatedFailureIsVisibleAndEndsLocatingState() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSession().also { it.updatePermission(PRECISE) }
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectCatalogLoading(locationSession.state.value)
            expectState(cityState(repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationClicked)
            this@runTest.runCurrent()
            val command = awaitLocationCommand()
            viewModel.dispatchAction(
                Action.LocationEventReceived(LocationPlatformEvent.Failed(command.id, LocationFailure.TimedOut)),
            )
            this@runTest.runCurrent()

            assertFalse(viewModel.container.stateFlow.value.location.isLocating)
            assertEquals(LocationFailure.TimedOut, viewModel.container.stateFlow.value.location.failure)
            assertNull(viewModel.container.stateFlow.value.location.activeRequestId)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun grantedResumeAfterFailureWaitsForExplicitRetry() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSession().also { session ->
            session.updatePermission(PRECISE)
            val failedRequest = session.nextCommandId()
            assertTrue(session.beginLocationRequest(failedRequest))
            session.fail(failedRequest, LocationFailure.TimedOut)
        }
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectCatalogLoading(locationSession.state.value)
            expectState(cityState(repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()

            assertEquals(LocationFailure.TimedOut, locationSession.state.value.failure)
            assertEquals(LocationFailure.TimedOut, viewModel.container.stateFlow.value.location.failure)
            assertNull(locationSession.state.value.activeRequestId)
            assertFalse(locationSession.state.value.isLocating)
            expectNoItems()

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()

            assertEquals(LocationFailure.TimedOut, locationSession.state.value.failure)
            assertEquals(LocationFailure.TimedOut, viewModel.container.stateFlow.value.location.failure)
            assertNull(locationSession.state.value.activeRequestId)
            assertFalse(locationSession.state.value.isLocating)
            expectNoItems()

            viewModel.dispatchAction(Action.LocationClicked)
            this@runTest.runCurrent()
            val retry = awaitLocationCommand()

            assertTrue(retry is LocationPlatformCommand.RequestLocation)
            assertEquals(retry.id, locationSession.state.value.activeRequestId)
            assertTrue(locationSession.state.value.isLocating)
            assertNull(locationSession.state.value.failure)
            assertNull(viewModel.container.stateFlow.value.location.failure)
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun TestScope.testLocationSession() = RuntimeLocationSession(
        scope = this,
        nowMillis = { NOW_MILLIS + testScheduler.currentTime },
    )

    private fun TestScope.testLocationSessionWithFix() = testLocationSession().also { session ->
        session.updatePermission(PRECISE)
        val requestId = session.nextCommandId()
        assertTrue(session.beginLocationRequest(requestId))
        session.accept(requestId, validCandidate())
    }

    private fun PreviewTransitRepository.cityItems() = cities().map { city ->
        CityItemUiModel(
            id = city.id,
            name = city.name,
            isEnabled = city.capabilities.stops,
            isExperimental = city.capabilities.experimental,
        )
    }

    private fun cityState(
        cities: List<CityItemUiModel>,
        selectedCityId: CityId? = null,
        location: com.denis.georgiatransit.shared.presentation.location.LocationState =
            com.denis.georgiatransit.shared.presentation.location.LocationState(),
    ) = ViewState(
        cities = cities,
        selectedCityId = selectedCityId,
        catalog = CityCatalogState.Populated(TransitFreshness.CacheValid),
        location = location,
    )

    private class CatalogRepository(
        private val results: MutableList<TransitLoadResult<List<TransitCity>>>,
        private val onRefresh: (() -> Unit)? = null,
        private val cancelFirstRefresh: Boolean = false,
    ) : TransitRepository {
        var refreshCalls = 0
            private set

        override fun cities(): List<TransitCity> = emptyList()

        override suspend fun refreshCityCapabilities(): TransitLoadResult<List<TransitCity>> {
            refreshCalls += 1
            if (cancelFirstRefresh && refreshCalls == 1) throw CancellationException("refresh cancelled")
            onRefresh?.invoke()
            return results.removeAt(0)
        }

        override fun routes(cityId: CityId): List<TransitRoute> = emptyList()
    }

    private suspend fun OrbitTestContext<ViewState, SideEffect, CitySelectionViewModel>.awaitLocationCommand():
        LocationPlatformCommand {
        val sideEffect = awaitSideEffectIgnoringStates()
        return (sideEffect as SideEffect.HandleLocation).command
    }

    private suspend fun OrbitTestContext<ViewState, SideEffect, CitySelectionViewModel>.expectCatalogLoading(
        location: com.denis.georgiatransit.shared.presentation.location.LocationState,
    ) {
        expectState(ViewState(location = location))
    }

    private suspend fun OrbitTestContext<ViewState, SideEffect, CitySelectionViewModel>.awaitSideEffectIgnoringStates():
        SideEffect {
        while (true) {
            when (val item = awaitItem()) {
                is Item.StateItem -> Unit
                is Item.SideEffectItem -> return item.value
            }
        }
    }

    private companion object {
        const val NOW_MILLIS = 1_800_000_000_000L
        val PRECISE = LocationPermissionState.Granted(LocationPrecision.Precise)
        val tbilisi = city("tbilisi", defaultZoom = 13.5)
        val batumi = city("batumi", defaultZoom = 12.5)

        fun kutaisi(stopsEnabled: Boolean) = city("kutaisi", stopsEnabled = stopsEnabled)

        fun city(
            id: String,
            stopsEnabled: Boolean = true,
            defaultZoom: Double = 13.0,
        ) = TransitCity(
            id = CityId(id),
            name = id.replaceFirstChar(Char::uppercase),
            center = GeoPoint(latitude = 41.0, longitude = 44.0),
            capabilities = CityCapabilities(
                stops = stopsEnabled,
                vehicles = true,
                arrivals = true,
                routeShapes = true,
                journeyPlanning = true,
            ),
            defaultZoom = defaultZoom,
        )

        fun validCandidate() = LocationFixCandidate(
            latitude = 41.7151,
            longitude = 44.8271,
            accuracyMeters = 20.0,
            capturedAtEpochMillis = NOW_MILLIS,
        )
    }
}
