package com.denis.georgiatransit.shared.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.model.singleSelectableCity
import com.denis.georgiatransit.shared.domain.repository.JourneyPlanRepository
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.location.LocationFreshnessClock
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.SystemLocationFreshnessClock
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.Syntax

class MapViewModel(
    private val repository: TransitRepository,
    private val session: TransitSession,
    private val locationSession: LocationSession,
    private val realtimeClock: VehicleRealtimeClock = SystemVehicleRealtimeClock(),
    private val realtimeTickerPolicy: VehicleRealtimeTickerPolicy = DefaultVehicleRealtimeTickerPolicy,
    /** Entry-scoped via DI; its decoded shape cache deliberately cannot outlive this Map VM. */
    private val routeGeometryCoordinator: RouteGeometryCoordinator = RouteGeometryCoordinator(repository),
    /** Injected from a validated BFF endpoint; null retains the bundled/offline base layer. */
    private val bffStyleUrl: String? = null,
    journeyPlanRepository: JourneyPlanRepository = JourneyPlanRepository.Unconfigured,
    journeyPlanClock: LocationFreshnessClock = SystemLocationFreshnessClock,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    private var currentLocale = TransitLocale.English
    private val cameraController = MapCameraController()
    private val stopSelection = MapStopSelection(cameraController)
    private val locationActions = MapLocationActions(locationSession)
    private val nearbyStops = MapNearbyStops(repository, session)
    private val nearbyWorkflow = MapNearbyWorkflow(nearbyStops)
    private val lastViewport: MapViewport? get() = nearbyStops.viewport
    private val supplementalStops = MapSupplementalStops(repository)
    private val stopArrivals = StopArrivalsFeature(repository)
    private val stopTransitions = MapStopTransitions(nearbyStops, supplementalStops, stopSelection, cameraController, stopArrivals)
    private val stopArrivalsStop: TransitStop? get() = stopArrivals.stop
    private val sessionSelection = MapSessionSelection(repository)
    private val currentCity: TransitCity? get() = sessionSelection.city
    private val selectedRouteProjection: RouteSelectionProjection get() = sessionSelection.projection
    private val selectedCatalogRoutes: List<TransitRoute> get() = sessionSelection.selectedCatalogRoutes
    private val selectedVehicleRoutes: List<TransitRoute> get() = sessionSelection.vehicleRoutes
    private var realtimeVisibleAndStarted = false
    private var routeGeometrySnapshot = RouteGeometrySnapshot()
    private val vehicleFeature = MapVehicleFeature(repository, realtimeClock, realtimeTickerPolicy)
    private val vehiclePresentation get() = vehicleFeature.presentation
    private val renderProjector by lazy { MapRenderProjector(cameraController, stopSelection, vehiclePresentation, bffStyleUrl) }
    private val journeyPlanner = JourneyPlannerWorkflow(journeyPlanRepository, repository, journeyPlanClock)

    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(
            baseLayerState = if (bffStyleUrl == null) MapBaseLayerState.LocalPreview else MapBaseLayerState.BffStyle,
            canChangeCity = repository.cities().singleSelectableCity() == null,
        ),
        onCreate = {
            viewModelScope.launch {
                repository.observeCities().collect { cities ->
                    val canChangeCity = cities.singleSelectableCity() == null
                    intent { reduce { state.copy(canChangeCity = canChangeCity) } }.join()
                }
            }
            combine(session.selectedCity, session.selectedRouteIds, locationSession.state) { city, selectedIds, location ->
                Triple(city, selectedIds, location)
            }.collect { (city, selectedIds, location) ->
                val change = sessionSelection.update(city, selectedIds)
                if (change.resetStops) stopTransitions.resetForCityChange()
                if (city == null) cameraController.reset()
                val locationStopKey = reconcileUserLocationStopRequest(city, location.fix?.point)
                stopArrivals.updateCatalog(sessionSelection.routeCatalog)
                if (change.cityChanged || change.arrivalsCapabilityChanged) stopArrivals.unblock()
                if (change.resetVehicles) vehicleFeature.reset()
                if (change.resetRouteStops) resetSelectedRouteStopState()
                if (change.selectionOverlayChanged) nextStopSourceRevision()
                val routeNames = selectedRouteProjection.routes.map { route -> route.displayLabel }
                // Reconcile synchronously before creating a render state. This makes a city or
                // selection replacement fail closed instead of briefly rendering old geometry.
                if (change.resetGeometry) syncRouteGeometry()
                val renderState = city?.let { renderStateFor(it, location.fix, lastViewport?.zoom) }
                val plannerStep = journeyPlanner.onContext(state.journeyPlanner, plannerContext(location), change.cityChanged)
                val vehicleLayer = if (change.resetVehicles) {
                    vehiclePresentation.initialVehicleLayerState(city, selectedVehicleRoutes)
                } else vehiclePresentation.vehicleLayerState()
                reduce {
                    MapSessionStateReducer.reduce(state, MapSessionStateInput(
                        city, change, location, renderState, routeNames, routeGeometrySnapshot,
                        selectedStopUi(), nearbyStopItems(),
                        stopArrivals.reproject(state.stopArrivalsSheet, currentLocale, selectedRouteProjection),
                        vehicleLayer, vehiclePresentation.vehicleAccessibilityItems(), plannerStep.state,
                    ))
                }
                runPlannerStep(plannerStep)
                if (change.resetStops && city?.capabilities?.stops == true) {
                    acceptViewport(MapViewportPolicy.initialViewport(city), force = true)
                }
                startUserLocationStopLoad(locationStopKey)
                if (change.arrivalsCapabilityChanged && city?.capabilities?.arrivals != true) {
                    markStopArrivalsUnavailable()
                }
                startVehicleRealtimeIfEligible()
                startSelectedRouteStopLoadsIfEligible()
                startStopArrivalsPollingIfEligible()
            }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            Action.RoutesClicked -> intent {
                if (currentCity?.capabilities?.routes == true) {
                    postSideEffect(NavigationEffect.OpenRoutes)
                }
            }
            Action.ChangeCityClicked -> intent {
                if (state.canChangeCity) postSideEffect(NavigationEffect.OpenCitySelection)
            }
            Action.MyLocationClicked, Action.RefreshLocation, is Action.LocationEventReceived -> intent {
                locationActions.forAction(action, state.location.permission, realtimeVisibleAndStarted)
                    ?.let { postSideEffect(SideEffect.HandleLocation(it)) }
            }
            Action.RetryNearby -> retryNearby()
            is Action.RouteGeometryFocused -> routeGeometryCoordinator.focus(action.routeId)
            is Action.RetryRouteGeometry -> routeGeometryCoordinator.retry(viewModelScope, action.routeId)
            is Action.RemoveRouteGeometry -> sessionSelection.removeRoute(session, action.routeId)
            is Action.MapEventReceived -> onMapEvent(action.event)
            is Action.StopSelected -> selectStop(
                stopId = action.stopId,
                sourceRevision = action.sourceRevision,
                requireVisibleMarker = false,
            )
            Action.StopArrivalsDismissed -> dismissStopArrivals()
            is Action.MapViewportInsetsChanged -> onMapViewportInsetsChanged(action.insets)
            Action.RetryStopArrivals -> retryStopArrivals()
            is Action.LocaleChanged -> onLocaleChanged(action.locale)
            is Action.RealtimeVisibilityChanged -> onRealtimeVisibilityChanged(action.isVisibleAndStarted)
            is JourneyPlannerAction -> updatePlanner { planner, context -> journeyPlanner.onAction(planner, action, context) }
        }
    }
    /** Cancels non-cooperative polling/animation work before generation advances and markers clear. */
    private fun syncVehiclePresentationInputs() {
        vehicleFeature.updateInputs(currentCity, selectedVehicleRoutes, selectedRouteProjection,
            realtimeVisibleAndStarted, routeGeometrySnapshot)
    }
    private fun resetSelectedRouteStopState() {
        if (supplementalStops.resetSelected()) nextStopSourceRevision()
    }

    private fun renderStateFor(city: TransitCity, fix: UserLocationFix?, viewportZoom: Double?): MapRenderState {
        syncVehiclePresentationInputs()
        return renderProjector.project(city, fix, viewportZoom, stopProjector(), routeGeometrySnapshot)
    }

    private fun onRealtimeVisibilityChanged(isVisibleAndStarted: Boolean) {
        if (realtimeVisibleAndStarted == isVisibleAndStarted) return
        realtimeVisibleAndStarted = isVisibleAndStarted
        syncVehiclePresentationInputs()
        // A newly visible composition starts from an empty generation; a late response from the
        // previous entry/lifecycle can therefore never republish vehicle geometry.
        vehicleFeature.reset()
        syncRouteGeometry()
        publishVehicleState()
        updatePlanner(journeyPlanner::onVisibility)
        startVehicleRealtimeIfEligible()
        if (isVisibleAndStarted) {
            startSelectedRouteStopLoadsIfEligible()
            startStopArrivalsPollingIfEligible()
        } else {
            supplementalStops.pauseSelected()
            // Cancelling and advancing the generation makes a non-cooperative BFF client unable
            // to republish while the app is backgrounded. The selected marker stays selected.
            pauseStopArrivalsPolling()
        }
    }

    private fun syncRouteGeometry(): RouteGeometrySnapshot {
        val city = currentCity
        return routeGeometryCoordinator.update(
            scope = viewModelScope,
            cityId = city?.id,
            routeGeometryEnabled = city?.capabilities?.routeGeometry == true,
            orderedSelectedRoutes = selectedCatalogRoutes,
            selectedRouteProjection = selectedRouteProjection,
            isVisibleAndStarted = realtimeVisibleAndStarted,
            onSnapshot = ::onRouteGeometrySnapshot,
        ).also { routeGeometrySnapshot = it; syncVehiclePresentationInputs() }
    }

    private fun reconcileUserLocationStopRequest(city: TransitCity?, point: GeoPoint?): UserLocationStopKey? {
        val (key, changed) = supplementalStops.reconcileUser(city, point, currentLocale)
        if (changed) nextStopSourceRevision()
        return key
    }

    private fun publishSupplementalStopChange() {
        nextStopSourceRevision()
        intent {
            val city = currentCity ?: return@intent
            reduce {
                state.copy(
                    renderState = renderStateFor(city, state.location.fix, lastViewport?.zoom),
                    selectedStop = selectedStopUi(),
                    // List items carry the source revision; stale ones are rejected on tap.
                    nearbyStops = nearbyStopItems(),
                )
            }
        }
    }

    private fun startUserLocationStopLoad(key: UserLocationStopKey?) {
        supplementalStops.startUser(viewModelScope, key, ::publishSupplementalStopChange)
    }
    private fun startSelectedRouteStopLoadsIfEligible() {
        val city = currentCity ?: return
        if (!realtimeVisibleAndStarted || !city.capabilities.stops || !city.capabilities.routes) return
        val locale = currentLocale
        supplementalStops.startSelected(
            scope = viewModelScope,
            city = city,
            routes = selectedVehicleRoutes,
            locale = locale,
            isCurrent = { key -> realtimeVisibleAndStarted && currentCity?.id == city.id &&
                currentLocale == locale && key in selectedRouteStopKeys() },
            onChanged = ::publishSupplementalStopChange,
        )
    }
    private fun selectedRouteStopKeys(): List<SelectedRouteStopKey> = selectedVehicleRoutes.flatMap { route ->
        route.directions.map { direction -> SelectedRouteStopKey(route.id, direction.id) }
    }

    private fun onRouteGeometrySnapshot(snapshot: RouteGeometrySnapshot) {
        val geometryChanged = snapshot.polylineSourceRevision != routeGeometrySnapshot.polylineSourceRevision
        routeGeometrySnapshot = snapshot
        syncVehiclePresentationInputs()
        if (geometryChanged) vehiclePresentation.updateVehicleFrames(forceSourceCheck = true)
        intent {
            val city = currentCity
            // A completion can enqueue this intent immediately before a newer selection/city
            // collector runs. Render the latest synchronously reconciled snapshot, never the
            // callback's captured one, so queued work cannot revive old geometry or legends.
            val currentSnapshot = routeGeometrySnapshot
            reduce {
                state.copy(
                    renderState = city?.let { renderStateFor(it, state.location.fix, lastViewport?.zoom) },
                    routeGeometryLegends = currentSnapshot.legends,
                )
            }
        }
    }
    override fun onCleared() {
        supplementalStops.resetUser()
        supplementalStops.resetSelected()
        routeGeometryCoordinator.clear()
        journeyPlanner.cancel()
        super.onCleared()
    }

    private fun startVehicleRealtimeIfEligible() {
        syncVehiclePresentationInputs()
        vehicleFeature.start(viewModelScope) { key ->
            if (key == null) publishVehicleState()
            else publishVehicleStateIfCurrent(key.generation, key.cityId)
        }
    }
    private fun publishVehicleState() = publishVehicleStateIfCurrent(null, null)

    /** Recheck generation inside the scheduled intent before publishing a late frame. */
    private fun publishVehicleStateIfCurrent(generation: Long?, cityId: CityId?) = intent {
        if (generation != null && cityId != null && !vehicleFeature.isCurrent(generation, cityId)) return@intent
        val city = currentCity
        reduce {
            state.copy(
                renderState = city?.let { renderStateFor(it, state.location.fix, lastViewport?.zoom) },
                routeGeometryLegends = routeGeometrySnapshot.legends,
                vehicleLayerState = vehiclePresentation.vehicleLayerState(),
                vehicleRoutes = vehiclePresentation.vehicleAccessibilityItems(),
            )
        }
    }

    private fun onMapEvent(event: MapPlatformEvent) {
        when (event) {
            is MapPlatformEvent.ViewportSettled -> acceptViewport(event.viewport)
            is MapPlatformEvent.StopTapped -> selectStop(
                stopId = event.stopId,
                sourceRevision = event.sourceRevision,
                requireVisibleMarker = true,
            )
            is MapPlatformEvent.VehicleTapped -> vehicleFeature.acceptTap(event.vehicleId, event.sourceRevision)
            is MapPlatformEvent.MapLongPressed ->
                updatePlanner { planner, context -> journeyPlanner.onLongPress(planner, event.point, context) }
        }
    }

    private fun plannerContext(location: LocationState) =
        journeyPlanner.context(currentCity, location, currentLocale, realtimeVisibleAndStarted)

    /** A null step leaves state untouched, e.g. a stale plan outcome rejected inside the intent. */
    private fun updatePlanner(transform: (JourneyPlannerUi, JourneyPlannerContext) -> JourneyPlannerStep?) = intent {
        val step = transform(state.journeyPlanner, plannerContext(state.location)) ?: return@intent
        reduce { state.copy(journeyPlanner = step.state) }
        runPlannerStep(step)
    }

    private suspend fun Syntax<ViewState, SideEffect>.runPlannerStep(step: JourneyPlannerStep) {
        locationActions.forPlanner(step.location, state.location.permission)?.let { postSideEffect(SideEffect.HandleLocation(it)) }
        journeyPlanner.execute(viewModelScope, step, plannerContext(state.location), onOutcome = { outcome ->
            updatePlanner { planner, context ->
                journeyPlanner.onOutcome(planner, outcome, context).takeIf { journeyPlanner.isCurrent(outcome, currentCity) }
            }
        }) { updatePlanner(journeyPlanner::onRefreshDue) }
    }

    private fun onLocaleChanged(locale: TransitLocale) = intent {
        if (locale == currentLocale) return@intent
        currentLocale = locale
        resetSelectedRouteStopState()
        val locationStopKey = reconcileUserLocationStopRequest(currentCity, state.location.fix?.point)
        nextStopSourceRevision()
        val city = currentCity
        val localizedStopArrivals = stopArrivals.restartForLocale(state.stopArrivalsSheet, city, locale, selectedRouteProjection)
        reduce {
            state.copy(
                renderState = city?.let { renderStateFor(it, state.location.fix, lastViewport?.zoom) },
                selectedStop = selectedStopUi(),
                nearbyStops = nearbyStopItems(),
                stopArrivalsSheet = localizedStopArrivals,
            )
        }
        if (city?.capabilities?.stops == true) {
            acceptViewport(lastViewport ?: MapViewportPolicy.initialViewport(city), force = true)
        }
        startUserLocationStopLoad(locationStopKey)
        startSelectedRouteStopLoadsIfEligible()
        startStopArrivalsPollingIfEligible()
    }

    private fun retryNearby() {
        val city = currentCity?.takeIf { nearbyStops.canRetry(it, container.stateFlow.value.contentState) } ?: return
        acceptViewport(lastViewport ?: MapViewportPolicy.initialViewport(city), force = true)
    }

    private fun acceptViewport(candidate: MapViewport, force: Boolean = false) {
        val city = currentCity ?: return
        val request = nearbyWorkflow.start(
            viewModelScope, city, candidate, currentLocale, force,
            { currentCity }, { currentLocale }, ::onNearbyEvent,
        )
        if (request != null) nextStopSourceRevision()
    }

    private suspend fun onNearbyEvent(event: MapNearbyEvent) {
        val request = event.request
        if (event is MapNearbyEvent.Cleared) {
            intent {
                if (!nearbyStops.isCurrent(request, currentCity, currentLocale)) return@intent
                stopTransitions.clearForRecovery()
                reduce {
                    state.copy(
                        renderState = renderStateFor(request.city, state.location.fix, request.viewport.zoom),
                        contentState = MapContentState.Loading,
                        selectedStop = null, nearbyStops = emptyList(), stopArrivalsSheet = null,
                    )
                }
            }.join()
            return
        }
        intent {
            if (!nearbyStops.isCurrent(request, currentCity, currentLocale)) return@intent
            val outcome = (event as? MapNearbyEvent.Result)?.let { nearbyWorkflow.accept(it.result, request.city) }
            if (outcome?.clearDerived == true) stopTransitions.clearForRecovery()
            else if (outcome?.advanceRevision == true) nextStopSourceRevision()
            reduce {
                state.copy(
                    renderState = renderStateFor(request.city, state.location.fix, request.viewport.zoom),
                    contentState = outcome?.contentState ?: MapContentState.Loading,
                    selectedStop = selectedStopUi(), nearbyStops = nearbyStopItems(),
                    stopArrivalsSheet = if (stopArrivalsStop == null) null else state.stopArrivalsSheet,
                )
            }
        }.join()
    }
    private fun selectStop(
        stopId: StopId,
        sourceRevision: Long,
        requireVisibleMarker: Boolean,
    ) = intent {
        val city = currentCity ?: return@intent
        if (requireVisibleMarker) stopSelection.groupTap(stopId, sourceRevision, state.renderState)?.let { command ->
            reduce { state.copy(renderState = state.renderState?.copy(camera = command)) }
            return@intent
        }
        val stop = stopSnapshot(stopId)
        if (!stopSelection.select(stop, sourceRevision, requireVisibleMarker,
                state.renderState?.stops, city, lastViewport?.zoom)) return@intent
        stopArrivals.open(checkNotNull(stop), city)
        reduce {
            state.copy(
                renderState = renderStateFor(city, state.location.fix, lastViewport?.zoom),
                selectedStop = selectedStopUi(),
                nearbyStops = nearbyStopItems(),
                stopArrivalsSheet = stopArrivals.initialSheet(currentCity, currentLocale, selectedRouteProjection),
            )
        }
        startStopArrivalsPollingIfEligible()
    }
    /** Every sheet dismissal atomically invalidates arrivals and clears its marker selection. */
    private fun dismissStopArrivals() = intent {
        if (stopSelection.selectedStopId == null && stopArrivalsStop == null && state.stopArrivalsSheet == null) return@intent
        val retainedZoom = cameraController.command?.zoom ?: lastViewport?.zoom
        stopArrivals.reset()
        stopSelection.dismiss()
        val city = currentCity
        reduce {
            state.copy(
                renderState = city?.let { renderStateFor(it, state.location.fix, retainedZoom) },
                selectedStop = null,
                nearbyStops = nearbyStopItems(),
                stopArrivalsSheet = null,
            )
        }
    }

    private fun onMapViewportInsetsChanged(insets: MapViewportInsets) = intent {
        val selected = stopSelection.selectedStopId?.let(::stopSnapshot) ?: return@intent
        if (state.stopArrivalsSheet == null) return@intent
        val city = currentCity ?: return@intent
        if (!stopSelection.updateInsets(insets, selected, city, lastViewport?.zoom)) return@intent
        reduce { state.copy(renderState = renderStateFor(city, state.location.fix, lastViewport?.zoom)) }
    }

    private fun retryStopArrivals() = updateStopArrivalsSheet(
        { sheet -> stopArrivals.retry(sheet, currentCity) }, ::startStopArrivalsPollingIfEligible,
    )

    private fun markStopArrivalsUnavailable() {
        if (stopArrivalsStop == null) return
        stopArrivals.invalidate()
        updateStopArrivalsSheet(stopArrivals::unavailable)
    }
    private fun pauseStopArrivalsPolling() {
        if (stopArrivalsStop == null) return
        stopArrivals.invalidate()
        updateStopArrivalsSheet(stopArrivals::pause)
    }

    /** Sheet transitions apply only to the sheet still open when the intent runs. */
    private fun updateStopArrivalsSheet(
        transform: (StopArrivalsSheetUi) -> StopArrivalsSheetUi?, afterUpdate: () -> Unit = {},
    ) = intent {
        val updated = state.stopArrivalsSheet?.let(transform) ?: return@intent
        reduce { state.copy(stopArrivalsSheet = updated) }
        afterUpdate()
    }
    private fun arrivalsContext() = StopArrivalsContext(
        currentCity, currentLocale, selectedRouteProjection,
        stopSelection.selectedStopId, realtimeVisibleAndStarted,
    )

    private fun startStopArrivalsPollingIfEligible() {
        stopArrivals.start(
            viewModelScope, currentCity, currentLocale,
            stopSelection.selectedStopId, realtimeVisibleAndStarted, ::arrivalsContext,
        ) { transform -> updateStopArrivalsSheet(transform).join() }
    }

    private fun stopProjector() = MapStopsProjector(
        rawStops = nearbyStops.stops,
        userLocationStops = supplementalStops.userStops,
        selectedRouteStops = supplementalStops.selectedStops.values.flatten(),
        selectedStopId = stopSelection.selectedStopId,
        selectedSnapshot = stopArrivalsStop,
        selection = selectedRouteProjection,
        locale = currentLocale,
        sourceRevision = stopSelection.sourceRevision,
    )
    private fun selectedStopUi(): SelectedStopUi? = stopProjector().selectedStopUi()
    private fun stopSnapshot(stopId: StopId): TransitStop? = stopProjector().stopSnapshot(stopId)
    private fun nearbyStopItems(): List<NearbyStopUi> = stopProjector().nearbyStopItems()
    private fun nextStopSourceRevision(): Long = stopSelection.nextSourceRevision()
}
