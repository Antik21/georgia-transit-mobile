package com.denis.georgiatransit.shared.presentation.map

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
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Stop-arrivals coverage deliberately uses normalized, handwritten domain values. It proves the
 * presentation boundary rejects bad data even when a future repository fake bypasses wire mapper
 * validation, and exercises both supported Georgian cities without provider contracts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopArrivalsMapViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun runTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block(this)
    }

    @Test
    fun tbilisiSelectionRendersSafeHeaderRouteLabelsAndEveryNormalizedSourceVariant() = runTest {
        val fixture = fixture(cityId = "tbilisi")
        fixture.arrivalResults += data(
            page(
                fixture.stopA,
                listOf(
                    arrival(fixture.stopA, fixture.routeA, 0, ArrivalSource.OfficialRealtime),
                    arrival(fixture.stopA, fixture.routeB, 3, ArrivalSource.AggregatorRealtime),
                    arrival(fixture.stopA, fixture.routeA, null, ArrivalSource.Schedule),
                    arrival(fixture.stopA, fixture.routeB, 8, ArrivalSource.ClientEstimate),
                ),
                source = ArrivalSource.OfficialRealtime,
            ),
        )

        withOpenedSheet(fixture) { viewModel, _ ->
            val sheet = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertEquals("Tbilisi Central", sheet.stopName)
            assertEquals("TB-001", sheet.stopCode)
            assertEquals(listOf("10", "37"), sheet.passingRouteShortNames)
            assertIs<StopArrivalsSheetState.Ready>(sheet.state)
            assertEquals(ArrivalSourceUi.OfficialRealtime, sheet.pageSource)
            assertEquals(
                listOf(
                    StopArrivalTimeUi.Arriving,
                    StopArrivalTimeUi.Minutes(3),
                    StopArrivalTimeUi.Minutes(8),
                    StopArrivalTimeUi.Unavailable,
                ),
                sheet.rows.map(StopArrivalRowUi::time),
            )
            assertEquals(
                listOf(
                    ArrivalSourceUi.OfficialRealtime,
                    ArrivalSourceUi.AggregatorRealtime,
                    ArrivalSourceUi.Approximate,
                    ArrivalSourceUi.Schedule,
                ),
                sheet.rows.map(StopArrivalRowUi::source),
            )
            assertTrue(sheet.rows.all { it.headsign == "Airport" })
            assertFalse(
                sheet.passingRouteShortNames.orEmpty().joinToString().contains("tbilisi:fixture:route"),
                "Opaque route IDs must not reach display text.",
            )
        }
    }

    @Test
    fun batumiDomainFakeDropsCancelledNegativeAndWrongStopRowsAsPartialData() = runTest {
        val fixture = fixture(cityId = "batumi")
        fixture.arrivalResults += data(
            page(
                fixture.stopA,
                listOf(
                    arrival(fixture.stopA, fixture.routeA, 5, ArrivalSource.Schedule),
                    arrival(fixture.stopA, fixture.routeA, 6, ArrivalSource.Schedule, cancelled = true),
                    arrival(fixture.stopB, fixture.routeA, 7, ArrivalSource.Schedule),
                    // Wire mapper rejects this. A domain fake may still construct it, so the UI
                    // boundary must drop it rather than render a nonsensical negative countdown.
                    arrival(fixture.stopA, fixture.routeB, -1, ArrivalSource.ClientEstimate),
                ),
                source = ArrivalSource.Schedule,
            ),
        )

        withOpenedSheet(fixture) { viewModel, _ ->
            val sheet = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertEquals("Batumi Central", sheet.stopName)
            assertIs<StopArrivalsSheetState.PartialData>(sheet.state)
            assertEquals(1, sheet.rows.size)
            assertEquals("10", sheet.rows.single().routeShortName)
            assertEquals(StopArrivalTimeUi.Minutes(5), sheet.rows.single().time)
        }
    }

    @Test
    fun zeroNormalizedArrivalsRendersNoArrivals() = runTest {
        val empty = fixture().also { it.arrivalResults += data(page(it.stopA, emptyList(), ArrivalSource.Schedule)) }
        withOpenedSheet(empty) { viewModel, _ ->
            assertIs<StopArrivalsSheetState.NoArrivals>(requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet).state)
        }
    }

    @Test
    fun staleOfflineArrivalsKeepRowsAndAreMarkedOffline() = runTest {
        val offline = fixture().also {
            it.arrivalResults += TransitLoadResult.Data(
                page(it.stopA, listOf(arrival(it.stopA, it.routeA, 4, ArrivalSource.Schedule)), ArrivalSource.Schedule),
                TransitFreshness.StaleOffline,
            )
        }
        withOpenedSheet(offline) { viewModel, _ ->
            val sheet = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertIs<StopArrivalsSheetState.Offline>(sheet.state)
            assertTrue(sheet.isStale)
            assertEquals(1, sheet.rows.size)
        }
    }

    @Test
    fun upstreamFailureRendersRetryableUpstreamState() = runTest {
        val upstream = fixture().also {
            it.arrivalResults += TransitLoadResult.Failure(TransitFailure.UpstreamUnavailable("maintenance", null, null))
        }
        withOpenedSheet(upstream) { viewModel, _ ->
            assertIs<StopArrivalsSheetState.UpstreamError>(requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet).state)
        }
    }

    @Test
    fun arrivalsCapabilityDisabledRendersUnavailableWithoutNetworkRequest() = runTest {
        val unavailable = fixture(arrivalsEnabled = false)
        withOpenedSheet(unavailable) { viewModel, _ ->
            assertIs<StopArrivalsSheetState.Unavailable>(requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet).state)
            assertEquals(0, unavailable.arrivalsRequests)
        }
    }

    @Test
    fun refreshFailurePreservesPreviousRowsAsStaleAndRetryRecovers() = runTest {
        val fixture = fixture()
        fixture.arrivalResults += data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 2, ArrivalSource.OfficialRealtime))))
        fixture.arrivalResults += TransitLoadResult.Failure(TransitFailure.UpstreamTimeout("slow", null))
        fixture.arrivalResults += data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeB, 1, ArrivalSource.AggregatorRealtime))))

        withOpenedSheet(fixture) { viewModel, _ ->
            advanceTimeBy(20_000)
            runCurrent()
            val failed = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertIs<StopArrivalsSheetState.UpstreamError>(failed.state)
            assertTrue(failed.isStale)
            assertEquals(listOf("10"), failed.rows.mapNotNull(StopArrivalRowUi::routeShortName))

            viewModel.dispatchAction(Action.RetryStopArrivals)
            runCurrent()
            val recovered = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertIs<StopArrivalsSheetState.Ready>(recovered.state)
            assertFalse(recovered.isStale)
            assertEquals(listOf("37"), recovered.rows.mapNotNull(StopArrivalRowUi::routeShortName))
            assertEquals(3, fixture.arrivalsRequests)
        }
    }

    @Test
    fun selectionFocusAndDismissalUpdateMarkerSheetAndCameraAtomicallyAndIdempotently() = runTest {
        val fixture = fixture()
        val session = RuntimeTransitSession().also { it.selectCity(fixture.city) }
        val viewModel = MapViewModel(fixture, session, RuntimeLocationSession(scope = this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceTimeBy(350)
            this@runTest.runCurrent()
            val beforeSelection = requireNotNull(viewModel.container.stateFlow.value.renderState)

            viewModel.selectCurrentStop(fixture.stopA.id)
            this@runTest.runCurrent()
            val selected = viewModel.container.stateFlow.value
            val selectedRender = requireNotNull(selected.renderState)
            assertEquals(fixture.stopA.id, selected.selectedStop?.id)
            assertEquals(fixture.stopA.id, selected.stopArrivalsSheet?.stopId)
            assertEquals(listOf(fixture.stopA.id), selected.nearbyStops.filter { it.isSelected }.map { it.id })
            assertEquals(listOf(fixture.stopA.id), selectedRender.stops.filter { it.isSelected }.map { it.id })
            assertEquals(fixture.stopA.position, selectedRender.camera.center)
            assertEquals(MapViewportInsets.StopArrivalsSheet, selectedRender.camera.viewportInsets)
            assertEquals(beforeSelection.camera.revision + 1, selectedRender.camera.revision)

            viewModel.dispatchAction(Action.MapViewportInsetsChanged(MapViewportInsets.StopArrivalsSheet))
            this@runTest.runCurrent()
            assertEquals(selectedRender.camera, requireNotNull(viewModel.container.stateFlow.value.renderState).camera)

            viewModel.dispatchAction(Action.StopArrivalsDismissed)
            this@runTest.runCurrent()
            val dismissed = viewModel.container.stateFlow.value
            val dismissedRender = requireNotNull(dismissed.renderState)
            assertNull(dismissed.selectedStop)
            assertNull(dismissed.stopArrivalsSheet)
            assertTrue(dismissed.nearbyStops.none { it.isSelected })
            assertTrue(dismissedRender.stops.none { it.isSelected })
            assertEquals(MapViewportInsets.None, dismissedRender.camera.viewportInsets)
            assertEquals(selectedRender.camera.revision + 1, dismissedRender.camera.revision)

            viewModel.dispatchAction(Action.StopArrivalsDismissed)
            this@runTest.runCurrent()
            assertEquals(dismissed, viewModel.container.stateFlow.value)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun committedRouteReplacementReprojectsOpenSheetAndNearbyMetadataWithoutStartingMoreIo() = runTest {
        val fixture = fixture()
        fixture.arrivalResults += data(
            page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeB, 2, ArrivalSource.Schedule))),
        )

        withOpenedSheet(fixture, initialSelection = setOf(fixture.routeB.id)) { viewModel, session ->
            val before = viewModel.container.stateFlow.value
            val beforeSheet = requireNotNull(before.stopArrivalsSheet)
            val selectedBadge = beforeSheet.passingRoutes.single { it.routeId == fixture.routeB.id }
            assertTrue(selectedBadge.isSelected)
            assertEquals(0xFF0057B8, selectedBadge.backgroundArgb)
            assertEquals(StopRouteHighlightStyle.SingleRoute, before.nearbyStops.single { it.id == fixture.stopA.id }.routeHighlight.style)
            val nearbyRequests = fixture.nearbyRequests
            val arrivalsRequests = fixture.arrivalsRequests
            val routeRefreshRequests = fixture.routeRefreshRequests

            assertTrue(session.selectRoutes(fixture.city.id, emptySet()))
            runCurrent()

            val after = viewModel.container.stateFlow.value
            val afterSheet = requireNotNull(after.stopArrivalsSheet)
            val removedBadge = afterSheet.passingRoutes.single { it.routeId == fixture.routeB.id }
            assertEquals(fixture.stopA.id, afterSheet.stopId)
            assertEquals(listOf(fixture.routeA.id, fixture.routeB.id), afterSheet.passingRoutes.map(StopRouteBadgeUi::routeId))
            assertFalse(removedBadge.isSelected, "The previously selected badge must lose committed-selection styling.")
            assertNotEquals(
                selectedBadge.backgroundArgb,
                removedBadge.backgroundArgb,
                "The sheet must fall back to its passing-route style once the committed selection removes B.",
            )
            assertTrue(after.nearbyStops.all { it.routeHighlight.style == StopRouteHighlightStyle.None })
            assertEquals(nearbyRequests, fixture.nearbyRequests)
            assertEquals(arrivalsRequests, fixture.arrivalsRequests)
            assertEquals(routeRefreshRequests, fixture.routeRefreshRequests)
        }
    }

    @Test
    fun rapidSelectionKeepsOnlyBAndLateAResultCannotReviveA() = runTest {
        val fixture = fixture()
        val lateA = CompletableDeferred<TransitLoadResult<ArrivalPage>>()
        fixture.arrivalResponder = { withContext(NonCancellable) { lateA.await() } }
        val session = RuntimeTransitSession().also { it.selectCity(fixture.city) }
        val viewModel = MapViewModel(fixture, session, RuntimeLocationSession(scope = this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceTimeBy(350)
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            viewModel.selectCurrentStop(fixture.stopA.id)
            this@runTest.runCurrent()
            assertEquals(1, fixture.arrivalsRequests)
            val cameraAfterA = requireNotNull(viewModel.container.stateFlow.value.renderState).camera

            fixture.arrivalResponder = null
            fixture.arrivalResults += data(
                page(fixture.stopB, listOf(arrival(fixture.stopB, fixture.routeB, 1, ArrivalSource.Schedule))),
            )
            viewModel.selectCurrentStop(fixture.stopB.id)
            this@runTest.runCurrent()
            val selectedB = viewModel.container.stateFlow.value
            assertEquals(fixture.stopB.id, selectedB.selectedStop?.id)
            assertEquals(fixture.stopB.id, selectedB.stopArrivalsSheet?.stopId)
            assertEquals(listOf(fixture.stopB.id), selectedB.renderState?.stops.orEmpty().filter { it.isSelected }.map { it.id })
            assertEquals(fixture.stopB.position, selectedB.renderState?.camera?.center)
            assertEquals(cameraAfterA.revision + 1, selectedB.renderState?.camera?.revision)

            lateA.complete(
                data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 1, ArrivalSource.OfficialRealtime)))),
            )
            this@runTest.runCurrent()
            val afterLateA = viewModel.container.stateFlow.value
            assertEquals(fixture.stopB.id, afterLateA.selectedStop?.id)
            assertEquals(fixture.stopB.id, afterLateA.stopArrivalsSheet?.stopId)
            assertEquals(selectedB.renderState?.camera, afterLateA.renderState?.camera)
            assertTrue(afterLateA.stopArrivalsSheet?.rows.orEmpty().none { it.source == ArrivalSourceUi.OfficialRealtime })

            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@runTest.runCurrent()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun lateResponseAfterDismissCannotPublish() = runTest {
        assertLateResponseIsIgnored("dismiss") { viewModel, fixture, session ->
            viewModel.dispatchAction(Action.StopArrivalsDismissed)
            runCurrent()
            assertNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
        }
    }

    @Test
    fun lateResponseAfterDifferentStopCannotPublish() = runTest {
        assertLateResponseIsIgnored("different stop") { viewModel, fixture, session ->
            // Only the old request is intentionally non-cooperative. The replacement request
            // completes normally so test teardown cannot retain a hidden perpetual poll.
            fixture.arrivalResponder = null
            fixture.arrivalResults += data(page(fixture.stopB, listOf(arrival(fixture.stopB, fixture.routeB, 1, ArrivalSource.Schedule))))
            viewModel.selectCurrentStop(fixture.stopB.id)
            runCurrent()
            assertEquals(fixture.stopB.id, viewModel.container.stateFlow.value.stopArrivalsSheet?.stopId)
        }
    }

    @Test
    fun lateResponseAfterCityChangeCannotPublish() = runTest {
        assertLateResponseIsIgnored("city") { viewModel, fixture, session ->
            session.selectCity(fixture.city.copy(id = CityId("batumi"), name = "Batumi"))
            runCurrent()
            assertNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
        }
    }

    @Test
    fun lateResponseAfterLocaleChangeCannotPublish() = runTest {
        assertLateResponseIsIgnored("locale") { viewModel, fixture, session ->
            fixture.arrivalResponder = null
            fixture.arrivalResults += data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 1, ArrivalSource.Schedule))))
            viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.Georgian))
            runCurrent()
            assertEquals("თბილისის ცენტრი", viewModel.container.stateFlow.value.stopArrivalsSheet?.stopName)
        }
    }

    @Test
    fun lateResponseAfterBackgroundCannotPublish() = runTest {
        assertLateResponseIsIgnored("background") { viewModel, fixture, session ->
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            runCurrent()
            assertTrue(viewModel.container.stateFlow.value.stopArrivalsSheet?.isRefreshing == false)
        }
    }

    @Test
    fun pollingIsVisibleOnlySequentialAndUsesTwentySecondCadence() = runTest {
        val fixture = fixture()
        fixture.arrivalResults += data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 1, ArrivalSource.Schedule))))
        fixture.arrivalResults += data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 2, ArrivalSource.Schedule))))

        withOpenedSheet(fixture) { viewModel, _ ->
            assertEquals(1, fixture.arrivalsRequests)
            assertEquals(1, fixture.maxConcurrentArrivalRequests)
            val cameraAfterOpen = requireNotNull(viewModel.container.stateFlow.value.renderState).camera
            advanceTimeBy(19_999)
            runCurrent()
            assertEquals(1, fixture.arrivalsRequests)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, fixture.arrivalsRequests)
            assertEquals(1, fixture.maxConcurrentArrivalRequests)
            assertEquals(cameraAfterOpen, requireNotNull(viewModel.container.stateFlow.value.renderState).camera)

            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            runCurrent()
            advanceTimeBy(40_000)
            runCurrent()
            assertEquals(2, fixture.arrivalsRequests)
            assertEquals(cameraAfterOpen, requireNotNull(viewModel.container.stateFlow.value.renderState).camera)
        }
    }

    @Test
    fun capabilityKillSwitchStaysClosedAcrossUnrelatedChangesAndLocaleUntilArrivalsActuallyChange() = runTest {
        val fixture = fixture()
        fixture.arrivalResults += TransitLoadResult.Failure(TransitFailure.CapabilityUnavailable("disabled", null))
        fixture.arrivalResults += data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 1, ArrivalSource.Schedule))))

        withOpenedSheet(fixture) { viewModel, session ->
            assertIs<StopArrivalsSheetState.Unavailable>(requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet).state)
            assertEquals(1, fixture.arrivalsRequests)

            session.selectCity(fixture.city.copy(capabilities = fixture.city.capabilities.copy(vehicles = true)))
            runCurrent()
            viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.Russian))
            viewModel.dispatchAction(Action.MapEventReceived(MapPlatformEvent.ViewportSettled(viewport(fixture.city))))
            advanceTimeBy(350)
            runCurrent()
            assertIs<StopArrivalsSheetState.Unavailable>(requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet).state)
            assertEquals(1, fixture.arrivalsRequests)

            session.selectCity(fixture.city.copy(capabilities = fixture.city.capabilities.copy(arrivals = false)))
            runCurrent()
            session.selectCity(fixture.city.copy(capabilities = fixture.city.capabilities.copy(arrivals = true)))
            runCurrent()
            assertEquals(2, fixture.arrivalsRequests)
            assertIs<StopArrivalsSheetState.Ready>(requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet).state)
        }
    }

    @Test
    fun viewportEmptyDoesNotCloseTheIndependentSheetAndRouteCatalogueRetryIsBounded() = runTest {
        val fixture = fixture(initialRoutes = emptyList())
        fixture.routeResults += TransitLoadResult.Failure(TransitFailure.Transport("catalogue offline"))
        fixture.routeResults += TransitLoadResult.Data(listOf(fixture.routeA, fixture.routeB), TransitFreshness.Network)
        fixture.arrivalResults += data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 2, ArrivalSource.Schedule))))
        fixture.arrivalResults += data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 1, ArrivalSource.Schedule))))

        withOpenedSheet(fixture) { viewModel, _ ->
            val first = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertIs<StopArrivalsSheetState.PartialData>(first.state)
            assertTrue(first.hasUnavailableRouteDetails)
            assertEquals(1, fixture.routeRefreshRequests)

            fixture.nearbyResults += TransitLoadResult.Empty(TransitFreshness.Network)
            viewModel.dispatchAction(Action.MapEventReceived(MapPlatformEvent.ViewportSettled(viewport(fixture.city, longitudeOffset = 0.1))))
            advanceTimeBy(350)
            runCurrent()
            assertEquals(fixture.stopA.id, viewModel.container.stateFlow.value.stopArrivalsSheet?.stopId)
            assertTrue(viewModel.container.stateFlow.value.nearbyStops.isEmpty())
            val retainedMarker = requireNotNull(viewModel.container.stateFlow.value.renderState).stops.single()
            assertEquals(fixture.stopA.id, retainedMarker.id)
            assertTrue(retainedMarker.isSelected)

            advanceTimeBy(20_000)
            runCurrent()
            val recovered = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertEquals(listOf("10", "37"), recovered.passingRouteShortNames)
            assertFalse(recovered.hasUnavailableRouteDetails)
            assertEquals(2, fixture.routeRefreshRequests)
            assertEquals(1, fixture.maxConcurrentRouteRefreshes)

            viewModel.dispatchAction(Action.StopArrivalsDismissed)
            runCurrent()
            assertTrue(requireNotNull(viewModel.container.stateFlow.value.renderState).stops.isEmpty())
        }
    }

    private suspend fun TestScope.withOpenedSheet(
        fixture: ArrivalsFixtureRepository,
        initialSelection: Set<RouteId> = emptySet(),
        assertion: suspend TestScope.(MapViewModel, RuntimeTransitSession) -> Unit,
    ) {
        val session = RuntimeTransitSession().also {
            it.selectCity(fixture.city)
            if (initialSelection.isNotEmpty()) assertTrue(it.selectRoutes(fixture.city.id, initialSelection))
        }
        val viewModel = MapViewModel(fixture, session, RuntimeLocationSession(scope = this))
        viewModel.test(this) {
            runOnCreate()
            this@withOpenedSheet.advanceTimeBy(350)
            this@withOpenedSheet.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            viewModel.selectCurrentStop(fixture.stopA.id)
            this@withOpenedSheet.runCurrent()
            assertion(viewModel, session)
            // MapViewModel intentionally owns a perpetual 20s visible polling loop. Explicitly
            // background it before the test scheduler performs its runTest cleanup.
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@withOpenedSheet.runCurrent()
            cancelAndIgnoreRemainingItems()
        }
    }

    private suspend fun TestScope.assertLateResponseIsIgnored(
        description: String,
        abandon: suspend TestScope.(MapViewModel, ArrivalsFixtureRepository, RuntimeTransitSession) -> Unit,
    ) {
        val fixture = fixture()
        val late = CompletableDeferred<TransitLoadResult<ArrivalPage>>()
        fixture.arrivalResponder = { withContext(NonCancellable) { late.await() } }
        val session = RuntimeTransitSession().also { it.selectCity(fixture.city) }
        val viewModel = MapViewModel(fixture, session, RuntimeLocationSession(scope = this))
        viewModel.test(this) {
            runOnCreate()
            this@assertLateResponseIsIgnored.advanceTimeBy(350)
            this@assertLateResponseIsIgnored.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            viewModel.selectCurrentStop(fixture.stopA.id)
            this@assertLateResponseIsIgnored.runCurrent()
            assertEquals(1, fixture.arrivalsRequests, "The $description case must begin an owned request.")

            abandon(viewModel, fixture, session)
            late.complete(data(page(fixture.stopA, listOf(arrival(fixture.stopA, fixture.routeA, 1, ArrivalSource.OfficialRealtime)))))
            this@assertLateResponseIsIgnored.runCurrent()
            val sheet = viewModel.container.stateFlow.value.stopArrivalsSheet
            assertFalse(
                sheet?.rows?.any { it.source == ArrivalSourceUi.OfficialRealtime } == true && sheet.stopId == fixture.stopA.id,
                "Late $description response must not publish into a newer sheet generation.",
            )
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@assertLateResponseIsIgnored.runCurrent()
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun fixture(
        cityId: String = "tbilisi",
        arrivalsEnabled: Boolean = true,
        initialRoutes: List<TransitRoute>? = null,
    ): ArrivalsFixtureRepository {
        val city = TransitCity(
            id = CityId(cityId),
            name = cityId.replaceFirstChar(Char::uppercase),
            center = GeoPoint(41.7151, 44.8271),
            defaultZoom = 13.0,
            capabilities = CityCapabilities(
                stops = true,
                vehicles = false,
                arrivals = arrivalsEnabled,
                routeShapes = false,
                journeyPlanning = false,
                routes = true,
            ),
        )
        val routeA = route(city.id, "route:10", "10")
        val routeB = route(city.id, "route:37", "37")
        val stopA = stop(city.id, "stop:central", "TB-001", listOf(routeA.id, routeB.id))
        val stopB = stop(
            city.id,
            "stop:harbor",
            "TB-002",
            listOf(routeB.id),
            position = GeoPoint(41.7251, 44.8371),
        )
        return ArrivalsFixtureRepository(city, stopA, stopB, routeA, routeB, initialRoutes ?: listOf(routeA, routeB))
    }

    private fun MapViewModel.selectCurrentStop(stopId: StopId) {
        val sourceRevision = requireNotNull(container.stateFlow.value.renderState).stopSourceRevision
        dispatchAction(Action.StopSelected(stopId, sourceRevision))
    }

    private fun route(cityId: CityId, localId: String, shortName: String) = TransitRoute(
        id = RouteId("${cityId.value}:fixture:$localId"),
        cityId = cityId,
        shortName = shortName,
        name = "Route $shortName",
        colorArgb = 0xFF0057B8,
    )

    private fun stop(
        cityId: CityId,
        localId: String,
        code: String,
        routeIds: List<RouteId>,
        position: GeoPoint = GeoPoint(41.7151, 44.8271),
    ) = TransitStop(
        id = StopId("${cityId.value}:fixture:$localId"),
        providerId = ProviderId(localId),
        code = code,
        name = LocalizedText(ru = "Центр ${cityId.value}", en = "${cityId.value.replaceFirstChar(Char::uppercase)} Central", ka = "თბილისის ცენტრი"),
        position = position,
        routeIds = routeIds,
        mode = TransitMode.Bus,
    )

    private fun arrival(
        stop: TransitStop,
        route: TransitRoute,
        minutes: Int?,
        source: ArrivalSource,
        cancelled: Boolean = false,
    ) = TransitArrival(
        stopId = stop.id,
        routeId = route.id,
        tripId = null,
        headsign = LocalizedText(ru = "Аэропорт", en = "Airport", ka = "აეროპორტი"),
        scheduledAt = null,
        expectedAt = null,
        expectedInMinutes = minutes,
        realtime = source == ArrivalSource.OfficialRealtime || source == ArrivalSource.AggregatorRealtime,
        cancelled = cancelled,
        source = source,
    )

    private fun page(stop: TransitStop, items: List<TransitArrival>, source: ArrivalSource = ArrivalSource.Schedule) =
        ArrivalPage(items, source, Instant.parse("2030-01-01T00:00:00Z"), stale = false)

    private fun data(page: ArrivalPage) = TransitLoadResult.Data(page, TransitFreshness.Network)

    private fun viewport(city: TransitCity, longitudeOffset: Double = 0.0) = MapViewport(
        center = city.center.copy(longitude = city.center.longitude + longitudeOffset),
        radiusMeters = 7_500,
        zoom = city.defaultZoom,
    )

    private class ArrivalsFixtureRepository(
        val city: TransitCity,
        val stopA: TransitStop,
        val stopB: TransitStop,
        val routeA: TransitRoute,
        val routeB: TransitRoute,
        initialRoutes: List<TransitRoute>,
    ) : TransitRepository {
        private var routesSnapshot = initialRoutes
        val arrivalResults = mutableListOf<TransitLoadResult<ArrivalPage>>()
        val routeResults = mutableListOf<TransitLoadResult<List<TransitRoute>>>()
        val nearbyResults = mutableListOf<TransitLoadResult<List<TransitStop>>>()
        var arrivalResponder: (suspend () -> TransitLoadResult<ArrivalPage>)? = null
        var arrivalsRequests = 0
        var nearbyRequests = 0
        var concurrentArrivalRequests = 0
        var maxConcurrentArrivalRequests = 0
        var routeRefreshRequests = 0
        var concurrentRouteRefreshes = 0
        var maxConcurrentRouteRefreshes = 0

        override fun cities(): List<TransitCity> = listOf(city)

        override fun routes(cityId: CityId): List<TransitRoute> = routesSnapshot

        override suspend fun nearbyStops(
            cityId: CityId,
            center: GeoPoint,
            radiusMeters: Int,
            limit: Int,
            locale: TransitLocale,
        ): TransitLoadResult<List<TransitStop>> {
            nearbyRequests++
            return nearbyResults.removeFirstOrNull()
                ?: TransitLoadResult.Data(listOf(stopA, stopB), TransitFreshness.Network)
        }

        override suspend fun arrivals(
            cityId: CityId,
            stopId: StopId,
            limit: Int,
            locale: TransitLocale,
        ): TransitLoadResult<ArrivalPage> {
            arrivalsRequests++
            concurrentArrivalRequests++
            maxConcurrentArrivalRequests = maxOf(maxConcurrentArrivalRequests, concurrentArrivalRequests)
            return try {
                arrivalResponder?.invoke() ?: arrivalResults.removeFirstOrNull()
                ?: TransitLoadResult.Data(
                    ArrivalPage(emptyList(), ArrivalSource.Schedule, Instant.parse("2030-01-01T00:00:00Z"), stale = false),
                    TransitFreshness.Network,
                )
            } finally {
                concurrentArrivalRequests--
            }
        }

        override suspend fun refreshRoutes(request: RouteListRequest): TransitLoadResult<List<TransitRoute>> {
            routeRefreshRequests++
            concurrentRouteRefreshes++
            maxConcurrentRouteRefreshes = maxOf(maxConcurrentRouteRefreshes, concurrentRouteRefreshes)
            return try {
                routeResults.removeFirstOrNull()?.also { result ->
                    if (result is TransitLoadResult.Data) routesSnapshot = result.value
                } ?: TransitLoadResult.Data(routesSnapshot, TransitFreshness.Network)
            } finally {
                concurrentRouteRefreshes--
            }
        }
    }
}
