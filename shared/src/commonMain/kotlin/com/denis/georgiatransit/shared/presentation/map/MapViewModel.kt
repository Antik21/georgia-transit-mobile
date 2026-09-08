package com.denis.georgiatransit.shared.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.canUseLastKnownGood
import com.denis.georgiatransit.shared.presentation.location.LocationCommandId
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.round

class MapViewModel(
    private val repository: TransitRepository,
    private val session: TransitSession,
    private val locationSession: LocationSession,
    private val realtimeClock: VehicleRealtimeClock = SystemVehicleRealtimeClock(),
    private val realtimeTickerPolicy: VehicleRealtimeTickerPolicy = DefaultVehicleRealtimeTickerPolicy,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    private var lastCityId: CityId? = null
    private var currentCity: TransitCity? = null
    private var currentLocale = TransitLocale.English
    private var lastAcceptedFix: UserLocationFix? = null
    private var cameraCommand: MapCameraCommand? = null
    private var nextCameraRevision = 0L
    private var lastViewport: MapViewport? = null
    private var rawStops: List<TransitStop> = emptyList()
    private var selectedStopId: StopId? = null
    private var nearbyJob: Job? = null
    private var nearbyGeneration = 0L
    private var capabilityBlockedCityId: CityId? = null
    private var stopArrivalsJob: Job? = null
    private var stopArrivalsGeneration = 0L
    private var stopArrivalsStop: TransitStop? = null
    private var stopArrivalsRouteLabels: Map<RouteId, String> = emptyMap()
    private var stopArrivalsPage: ArrivalPage? = null
    private var stopArrivalsFreshness: TransitFreshness? = null
    /** A server kill switch is fail-closed until the selected city's arrivals capability changes. */
    private var arrivalsCapabilityBlockedCityId: CityId? = null
    private val stopArrivalsRequestLock = Mutex()
    private val stopArrivalsRouteRequestLock = Mutex()
    private var stopArrivalsRouteRefreshJob: Job? = null
    private var selectedVehicleRoutes: List<TransitRoute> = emptyList()
    private var lastSelectedRouteIds: Set<RouteId> = emptySet()
    private var realtimeVisibleAndStarted = false
    private var vehicleGeneration = 0L
    private var vehiclePollJobs: List<Job> = emptyList()
    private var vehicleFrameJob: Job? = null
    private var vehicleRealtimeState = VehicleRealtimeState()
    private var vehicleMarkers: PersistentList<MapVehicleMarker> = persistentListOf()
    private var vehicleFrameRevision = 0L
    private var vehicleBadgeRevision = 0L
    private val vehicleRequestLocks = mutableMapOf<VehicleRequestKey, Mutex>()

    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            combine(session.selectedCity, session.selectedRouteIds, locationSession.state) { city, selectedIds, location ->
                Triple(city, selectedIds, location)
            }.collect { (city, selectedIds, location) ->
                val previousCity = currentCity
                val cityChanged = city?.id != previousCity?.id
                val stopsCapabilityChanged = !cityChanged &&
                    city?.capabilities?.stops != previousCity?.capabilities?.stops
                if (cityChanged || stopsCapabilityChanged) resetNearbyState()
                currentCity = city
                if (city == null) resetCameraState()

                val selectedRoutes = city?.let { selectedCity ->
                    repository.routes(selectedCity.id).filter { it.id in selectedIds }
                }.orEmpty()
                val vehicleCapabilityChanged = !cityChanged &&
                    city?.capabilities?.vehiclePositions != previousCity?.capabilities?.vehiclePositions
                val arrivalsCapabilityChanged = !cityChanged &&
                    city?.capabilities?.arrivals != previousCity?.capabilities?.arrivals
                if (cityChanged || arrivalsCapabilityChanged) arrivalsCapabilityBlockedCityId = null
                val vehicleSelectionChanged = selectedIds != lastSelectedRouteIds
                if (cityChanged || vehicleCapabilityChanged || vehicleSelectionChanged) {
                    resetVehicleRealtimeState()
                }
                selectedVehicleRoutes = selectedRoutes
                lastSelectedRouteIds = selectedIds.toSet()
                val routeNames = selectedRoutes.map(TransitRoute::shortName)
                val renderState = city?.let { renderStateFor(it, location.fix, lastViewport?.zoom) }
                reduce {
                    state.copy(
                        cityName = city?.name.orEmpty(),
                        renderState = renderState,
                        contentState = if (cityChanged || stopsCapabilityChanged) {
                            initialContentState(city)
                        } else {
                            state.contentState
                        },
                        selectedRouteNames = routeNames,
                        attribution = city?.attribution.orEmpty(),
                        location = location,
                        selectedStop = selectedStopUi(),
                        nearbyStops = nearbyStopItems(),
                        stopArrivalsSheet = if (cityChanged || stopsCapabilityChanged) {
                            null
                        } else {
                            state.stopArrivalsSheet
                        },
                        vehicleLayerState = if (cityChanged || vehicleCapabilityChanged || vehicleSelectionChanged) {
                            initialVehicleLayerState(city, selectedRoutes)
                        } else {
                            vehicleLayerState()
                        },
                        vehicleRoutes = vehicleAccessibilityItems(),
                    )
                }
                if ((cityChanged || stopsCapabilityChanged) && city?.capabilities?.stops == true) {
                    acceptViewport(initialViewport(city), force = true)
                }
                if (arrivalsCapabilityChanged && city?.capabilities?.arrivals != true) {
                    markStopArrivalsUnavailable()
                }
                startVehicleRealtimeIfEligible()
                startStopArrivalsPollingIfEligible()
            }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            Action.RoutesClicked -> intent { postSideEffect(NavigationEffect.OpenRoutes) }
            Action.ChangeCityClicked -> intent { postSideEffect(NavigationEffect.OpenCitySelection) }
            Action.MyLocationClicked -> onMyLocationClicked()
            Action.RetryNearby -> retryNearby()
            is Action.LocationEventReceived -> onLocationEvent(action.event)
            is Action.MapEventReceived -> onMapEvent(action.event)
            is Action.StopSelected -> selectStop(action.stopId, requireVisibleMarker = false)
            Action.StopArrivalsDismissed -> dismissStopArrivals()
            Action.RetryStopArrivals -> retryStopArrivals()
            is Action.LocaleChanged -> onLocaleChanged(action.locale)
            is Action.RealtimeVisibilityChanged -> onRealtimeVisibilityChanged(action.isVisibleAndStarted)
        }
    }

    /** Clears every stop-derived value and invalidates any non-cooperative in-flight result. */
    private fun resetNearbyState() {
        nearbyJob?.cancel()
        nearbyJob = null
        nearbyGeneration++
        lastViewport = null
        rawStops = emptyList()
        selectedStopId = null
        capabilityBlockedCityId = null
        resetStopArrivalsState()
    }

    /** Cancels non-cooperative polling/animation work before generation advances and markers clear. */
    private fun resetVehicleRealtimeState() {
        vehiclePollJobs.forEach(Job::cancel)
        vehiclePollJobs = emptyList()
        vehicleFrameJob?.cancel()
        vehicleFrameJob = null
        vehicleGeneration++
        vehicleRealtimeState = VehicleRealtimeState()
        val vehicleSourceChanged = vehicleMarkers.isNotEmpty()
        vehicleMarkers = persistentListOf()
        if (vehicleSourceChanged) nextVehicleFrameRevision()
        if (vehicleSourceChanged) nextVehicleBadgeRevision()
    }

    private fun resetCameraState() {
        lastCityId = null
        cameraCommand = null
        lastAcceptedFix = null
    }

    private fun initialContentState(city: TransitCity?): MapContentState = when {
        city == null || !city.capabilities.stops -> MapContentState.Unavailable
        else -> MapContentState.Loading
    }

    private fun renderStateFor(city: TransitCity, fix: UserLocationFix?, viewportZoom: Double?): MapRenderState {
        val isCityChange = lastCityId != null && lastCityId != city.id
        if (lastCityId != city.id || cameraCommand == null) {
            lastCityId = city.id
            cameraCommand = nextCameraCommand(center = city.center, zoom = city.defaultZoom)
            lastAcceptedFix = if (isCityChange) fix else null
        }
        if (fix == null) {
            lastAcceptedFix = null
        } else if (fix != lastAcceptedFix) {
            lastAcceptedFix = fix
            cameraCommand = nextCameraCommand(center = fix.point, zoom = USER_LOCATION_ZOOM)
        }
        val camera = checkNotNull(cameraCommand)
        val markers = rawStops.map { stop ->
            MapStopMarker(
                id = stop.id,
                position = stop.position,
                accessibilityLabel = stop.displayName(currentLocale),
                isSelected = stop.id == selectedStopId,
            )
        }
        val clustered = clusterStops(markers, viewportZoom ?: camera.zoom)
        return MapRenderState(
            camera = camera,
            stops = clustered.stops,
            stopClusters = clustered.clusters,
            vehicles = vehicleMarkers,
            userLocation = fix,
            vehicleSourceRevision = vehicleFrameRevision,
            vehicleBadgeRevision = vehicleBadgeRevision,
        )
    }

    private fun nextCameraCommand(center: GeoPoint, zoom: Double): MapCameraCommand =
        MapCameraCommand(center = center, zoom = zoom, revision = ++nextCameraRevision)

    private fun onRealtimeVisibilityChanged(isVisibleAndStarted: Boolean) {
        if (realtimeVisibleAndStarted == isVisibleAndStarted) return
        realtimeVisibleAndStarted = isVisibleAndStarted
        // A newly visible composition starts from an empty generation; a late response from the
        // previous entry/lifecycle can therefore never republish vehicle geometry.
        resetVehicleRealtimeState()
        publishVehicleState()
        startVehicleRealtimeIfEligible()
        if (isVisibleAndStarted) {
            startStopArrivalsPollingIfEligible()
        } else {
            // Cancelling and advancing the generation makes a non-cooperative BFF client unable
            // to republish while the app is backgrounded. The selected marker stays selected.
            pauseStopArrivalsPolling()
        }
    }

    private fun startVehicleRealtimeIfEligible() {
        val city = currentCity ?: return
        if (!canPollVehicles(city) || vehiclePollJobs.isNotEmpty()) return
        val generation = vehicleGeneration
        val routes = selectedVehicleRoutes
        vehiclePollJobs = routes.map { route ->
            viewModelScope.launch { pollVehicleRoute(generation, city.id, route.id) }
        }
        vehicleFrameJob = viewModelScope.launch {
            while (isCurrentVehicleSession(generation, city.id)) {
                val update = updateVehicleFrames()
                // The loop condition guards before the work; the checked publisher guards again
                // inside Orbit after it has been scheduled, so a cancelled lifecycle generation
                // cannot expose an interpolation frame or expired track late.
                if (update.shouldPublish && isCurrentVehicleSession(generation, city.id)) {
                    publishVehicleStateIfCurrent(generation, city.id)
                }
                delay(vehicleFrameDelayMillis())
            }
        }
    }

    /** One sequential loop per selected route makes overlap impossible without adding retries. */
    private suspend fun pollVehicleRoute(generation: Long, cityId: CityId, routeId: RouteId) {
        while (isCurrentVehicleSession(generation, cityId) && routeId !in vehicleRealtimeState.pausedRoutes) {
            vehicleRealtimeState = VehicleRealtimeReducer.markLoading(vehicleRealtimeState, routeId)
            publishVehicleState()
            val result = requestVehicles(generation, cityId, routeId) ?: return
            if (!isCurrentVehicleSession(generation, cityId) || routeId !in selectedVehicleRouteIds()) return
            applyVehicleResult(routeId, result)
            delay(realtimeTickerPolicy.pollIntervalMillis)
        }
    }

    private suspend fun requestVehicles(
        generation: Long,
        cityId: CityId,
        routeId: RouteId,
    ): TransitLoadResult<com.denis.georgiatransit.shared.domain.model.VehiclePage>? {
        val requestKey = VehicleRequestKey(cityId, routeId)
        val mutex = vehicleRequestLocks.getOrPut(requestKey, ::Mutex)
        return mutex.withLock {
            if (!isCurrentVehicleSession(generation, cityId) || routeId !in selectedVehicleRouteIds()) return@withLock null
            try {
                repository.vehicles(cityId = cityId, routeId = routeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                TransitLoadResult.Failure(TransitFailure.Transport("Vehicle positions request failed"))
            }
        }
    }

    private fun applyVehicleResult(
        routeId: RouteId,
        result: TransitLoadResult<com.denis.georgiatransit.shared.domain.model.VehiclePage>,
    ) {
        when (result) {
            is TransitLoadResult.Data -> when (
                val reduction = VehicleRealtimeReducer.acceptPage(
                    state = vehicleRealtimeState,
                    requestedRouteId = routeId,
                    page = result.value,
                    freshness = result.freshness,
                    wallReceivedAt = realtimeClock.wallNow(),
                    monotonicNowMillis = realtimeClock.monotonicNowMillis(),
                )
            ) {
                is VehiclePageReduction.Applied -> vehicleRealtimeState = reduction.state
                is VehiclePageReduction.IgnoredOlder -> vehicleRealtimeState = reduction.state
                is VehiclePageReduction.Rejected -> vehicleRealtimeState = reduction.state
            }
            is TransitLoadResult.Empty -> {
                vehicleRealtimeState = VehicleRealtimeReducer.acceptEmpty(
                    state = vehicleRealtimeState,
                    routeId = routeId,
                    freshness = result.freshness,
                )
            }
            is TransitLoadResult.Failure -> {
                vehicleRealtimeState = VehicleRealtimeReducer.handleFailure(
                    state = vehicleRealtimeState,
                    routeId = routeId,
                    failure = result.error,
                )
            }
        }
        updateVehicleFrames(forceSourceCheck = true)
        publishVehicleState()
    }

    private fun updateVehicleFrames(forceSourceCheck: Boolean = false): VehicleFrameUpdate {
        val wallNow = realtimeClock.wallNow()
        val monotonicNow = realtimeClock.monotonicNowMillis()
        val unexpired = VehicleRealtimeReducer.expire(vehicleRealtimeState, wallNow)
        val tracksChanged = unexpired != vehicleRealtimeState
        vehicleRealtimeState = unexpired
        val routes = selectedVehicleRoutes.associateBy(TransitRoute::id)
        val visibleTracks = vehicleRealtimeState.tracks.asSequence()
            .filter { it.routeId in routes }
            .sortedWith(compareBy<VehicleTrack> { it.routeId.value }.thenBy { it.id.value })
            .take(MAX_COMMON_VEHICLE_MARKERS)
            .toList()
        val publishedMarkersById = vehicleMarkers.associateBy(MapVehicleMarker::id)
        val pendingTerminalTrackIds = visibleTracks
            .asSequence()
            .filter { track -> track.needsTerminalFrame(monotonicNow, publishedMarkersById[track.id]) }
            .take(MAX_SMOOTHLY_ANIMATED_VEHICLES)
            .mapTo(mutableSetOf(), VehicleTrack::id)
        val animatedTrackIds = pendingTerminalTrackIds.toMutableSet()
        visibleTracks.asSequence()
            .filter { track -> track.isInterpolationActiveAt(monotonicNow) }
            // Keep common source updates bounded. At high density every remaining marker is
            // published once per vehicle snapshot at its target position, rather than at 20 fps.
            .map(VehicleTrack::id)
            .filter { it !in animatedTrackIds }
            .take((MAX_SMOOTHLY_ANIMATED_VEHICLES - animatedTrackIds.size).coerceAtLeast(0))
            .forEach(animatedTrackIds::add)
        val hasFrameToRender = animatedTrackIds.isNotEmpty()
        if (!forceSourceCheck && !tracksChanged && !hasFrameToRender) {
            return VehicleFrameUpdate(accessibilityChanged = false, renderedSourceChanged = false)
        }
        val reusesStaticMetadata = !forceSourceCheck && !tracksChanged && vehicleMarkers.matches(visibleTracks)
        val nextMarkers = if (reusesStaticMetadata) {
            val tracksById = visibleTracks.associateBy(VehicleTrack::id)
            vehicleMarkers.map { marker ->
                val track = tracksById[marker.id]
                if (track != null && marker.id in animatedTrackIds) {
                    val nextPosition = VehicleRealtimeReducer.frame(track, monotonicNow)
                    if (nextPosition == marker.position) marker else marker.copy(position = nextPosition)
                } else {
                    marker
                }
            }.toPersistentList()
        } else {
            visibleTracks.asSequence()
                .mapNotNull { track ->
                    routes[track.routeId]?.let { route ->
                        track.toMarker(
                            route = route,
                            monotonicNowMillis = monotonicNow,
                            frameRevision = 0L,
                            interpolate = track.id in animatedTrackIds,
                        )
                    }
                }
                .toList()
                .toPersistentList()
        }
        if (nextMarkers.hasSameRenderedVehicleSourceAs(vehicleMarkers)) {
            return VehicleFrameUpdate(accessibilityChanged = tracksChanged, renderedSourceChanged = false)
        }
        val revision = nextVehicleFrameRevision()
        if (!nextMarkers.hasSameBadgeStylesAs(vehicleMarkers)) nextVehicleBadgeRevision()
        vehicleMarkers = if (reusesStaticMetadata) {
            nextMarkers.mapIndexed { index, marker ->
                if (marker === vehicleMarkers[index]) marker else marker.copy(frameRevision = revision)
            }.toPersistentList()
        } else {
            nextMarkers.map { it.copy(frameRevision = revision) }.toPersistentList()
        }
        return VehicleFrameUpdate(accessibilityChanged = tracksChanged, renderedSourceChanged = true)
    }

    private fun VehicleTrack.toMarker(
        route: TransitRoute,
        monotonicNowMillis: Long,
        frameRevision: Long,
        interpolate: Boolean,
    ): MapVehicleMarker =
        MapVehicleMarker(
            id = id,
            routeId = routeId,
            directionId = directionId,
            position = if (interpolate) VehicleRealtimeReducer.frame(this, monotonicNowMillis) else to,
            routeColorArgb = route.colorArgb,
            bearingDegrees = bearingDegrees,
            positionKind = positionKind,
            freshness = freshness,
            routeLabel = route.shortName.sanitizedRouteBadgeLabel(),
            routeTextColorArgb = route.contrastSafeTextColor(),
            isStale = isStale,
            frameRevision = frameRevision,
        )

    /**
     * Typical maps animate at 20 fps. 251--1000 visible markers use 10 fps while only the first
     * 250 stable vehicle IDs move between snapshots, protecting the grouped GeoJSON source from
     * replacing a high-density collection every 50 ms.
     */
    private fun vehicleFrameDelayMillis(): Long = when {
        vehicleRealtimeState.tracks.size > MAX_SMOOTHLY_ANIMATED_VEHICLES ->
            maxOf(realtimeTickerPolicy.frameIntervalMillis, HIGH_DENSITY_FRAME_INTERVAL_MILLIS)

        else -> realtimeTickerPolicy.frameIntervalMillis
    }

    private fun VehicleTrack.isInterpolationActiveAt(monotonicNowMillis: Long): Boolean =
        interpolationStartedAtMonotonicMillis?.let { started ->
            monotonicNowMillis - started in 0L..INTERPOLATION_ACTIVE_WINDOW_MILLIS
        } == true

    /**
     * Tickers can wake after the one-second interpolation interval. The endpoint still has to be
     * emitted once whenever the source last published an earlier coordinate; [frame] clamps it.
     */
    private fun VehicleTrack.needsTerminalFrame(
        monotonicNowMillis: Long,
        publishedMarker: MapVehicleMarker?,
    ): Boolean = interpolationStartedAtMonotonicMillis?.let { started ->
        monotonicNowMillis - started > INTERPOLATION_ACTIVE_WINDOW_MILLIS &&
            publishedMarker?.position != to
    } == true

    /** The fast frame path can retain immutable badge/style objects for every non-moving marker. */
    private fun List<MapVehicleMarker>.matches(tracks: List<VehicleTrack>): Boolean =
        size == tracks.size && zip(tracks).all { (marker, track) ->
            marker.id == track.id && marker.routeId == track.routeId && marker.directionId == track.directionId
        }

    private fun publishVehicleState() = intent {
        val city = currentCity
        reduce {
            state.copy(
                renderState = city?.let { renderStateFor(it, state.location.fix, lastViewport?.zoom) },
                vehicleLayerState = vehicleLayerState(),
                vehicleRoutes = vehicleAccessibilityItems(),
            )
        }
    }

    /**
     * The second session check runs in Orbit's scheduled intent, not merely in the ticker's
     * coroutine, which makes late non-cooperative frame publication fail closed.
     */
    private fun publishVehicleStateIfCurrent(generation: Long, cityId: CityId) = intent {
        if (!isCurrentVehicleSession(generation, cityId)) return@intent
        val city = currentCity ?: return@intent
        reduce {
            state.copy(
                renderState = renderStateFor(city, state.location.fix, lastViewport?.zoom),
                vehicleLayerState = vehicleLayerState(),
                vehicleRoutes = vehicleAccessibilityItems(),
            )
        }
    }

    private fun vehicleAccessibilityItems(): List<VehicleRouteAccessibilityUi> = selectedVehicleRoutes
        .sortedBy(TransitRoute::shortName)
        .map { route ->
            VehicleRouteAccessibilityUi(
                routeId = route.id,
                routeLabel = route.shortName.sanitizedRouteBadgeLabel(),
                vehicleCount = vehicleRealtimeState.tracks.count { it.routeId == route.id },
                layerState = vehicleLayerStateFor(route.id),
            )
        }

    private fun initialVehicleLayerState(city: TransitCity?, routes: List<TransitRoute>): VehicleLayerState = when {
        city == null || routes.isEmpty() || !realtimeVisibleAndStarted -> VehicleLayerState.Hidden
        !city.capabilities.vehiclePositions -> VehicleLayerState.Unavailable
        else -> VehicleLayerState.Loading
    }

    private fun vehicleLayerState(): VehicleLayerState {
        val city = currentCity
        if (city == null || selectedVehicleRoutes.isEmpty() || !realtimeVisibleAndStarted) return VehicleLayerState.Hidden
        if (!city.capabilities.vehiclePositions) return VehicleLayerState.Unavailable
        val phases = selectedVehicleRoutes.map { vehicleRealtimeState.routePhases[it.id] ?: VehicleRoutePhase.Loading }
        return when (phases.distinct()) {
            listOf(VehicleRoutePhase.Loading) -> VehicleLayerState.Loading
            listOf(VehicleRoutePhase.Live) -> VehicleLayerState.Live
            listOf(VehicleRoutePhase.Stale) -> VehicleLayerState.Stale
            listOf(VehicleRoutePhase.Retryable) -> VehicleLayerState.Retryable
            listOf(VehicleRoutePhase.Unavailable) -> VehicleLayerState.Unavailable
            else -> VehicleLayerState.Mixed
        }
    }

    private fun vehicleLayerStateFor(routeId: RouteId): VehicleLayerState {
        if (!realtimeVisibleAndStarted) return VehicleLayerState.Hidden
        if (currentCity?.capabilities?.vehiclePositions != true) return VehicleLayerState.Unavailable
        return when (vehicleRealtimeState.routePhases[routeId] ?: VehicleRoutePhase.Loading) {
            VehicleRoutePhase.Loading -> VehicleLayerState.Loading
            VehicleRoutePhase.Live -> VehicleLayerState.Live
            VehicleRoutePhase.Stale -> VehicleLayerState.Stale
            VehicleRoutePhase.Retryable -> VehicleLayerState.Retryable
            VehicleRoutePhase.Unavailable -> VehicleLayerState.Unavailable
        }
    }

    private fun canPollVehicles(city: TransitCity): Boolean = realtimeVisibleAndStarted &&
        city.capabilities.vehiclePositions && selectedVehicleRoutes.isNotEmpty()

    private fun isCurrentVehicleSession(generation: Long, cityId: CityId): Boolean =
        generation == vehicleGeneration && realtimeVisibleAndStarted && currentCity?.id == cityId &&
            currentCity?.capabilities?.vehiclePositions == true && selectedVehicleRoutes.isNotEmpty()

    private fun selectedVehicleRouteIds(): Set<RouteId> = selectedVehicleRoutes.mapTo(mutableSetOf(), TransitRoute::id)

    private fun nextVehicleFrameRevision(): Long {
        vehicleFrameRevision = if (vehicleFrameRevision == Long.MAX_VALUE) 1L else vehicleFrameRevision + 1L
        return vehicleFrameRevision
    }

    private fun nextVehicleBadgeRevision(): Long {
        vehicleBadgeRevision = if (vehicleBadgeRevision == Long.MAX_VALUE) 1L else vehicleBadgeRevision + 1L
        return vehicleBadgeRevision
    }

    private fun onMapEvent(event: MapPlatformEvent) {
        when (event) {
            is MapPlatformEvent.ViewportSettled -> acceptViewport(event.viewport)
            is MapPlatformEvent.StopTapped -> selectStop(event.stopId, requireVisibleMarker = true)
        }
    }

    private fun onLocaleChanged(locale: TransitLocale) = intent {
        if (locale == currentLocale) return@intent
        currentLocale = locale
        val city = currentCity
        val localizedStopArrivals = restartStopArrivalsForLocale(state.stopArrivalsSheet)
        reduce {
            state.copy(
                renderState = city?.let { renderStateFor(it, state.location.fix, lastViewport?.zoom) },
                selectedStop = selectedStopUi(),
                nearbyStops = nearbyStopItems(),
                stopArrivalsSheet = localizedStopArrivals,
            )
        }
        if (city?.capabilities?.stops == true) {
            acceptViewport(lastViewport ?: initialViewport(city), force = true)
        }
        startStopArrivalsPollingIfEligible()
    }

    private fun retryNearby() {
        val city = currentCity ?: return
        if (
            !city.capabilities.stops || capabilityBlockedCityId == city.id ||
            container.stateFlow.value.contentState !is MapContentState.RetryableError
        ) return
        acceptViewport(lastViewport ?: initialViewport(city), force = true)
    }

    private fun acceptViewport(candidate: MapViewport, force: Boolean = false) {
        val city = currentCity ?: return
        if (
            !city.capabilities.stops || capabilityBlockedCityId == city.id ||
            !candidate.center.isMapCoordinate() || !candidate.zoom.isFinite()
        ) return
        if (candidate.radiusMeters <= 0) return
        val viewport = candidate.normalized()
        if (!force && lastViewport == viewport) return
        lastViewport = viewport
        val locale = currentLocale
        val generation = ++nearbyGeneration
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            intent {
                if (!isCurrentRequest(generation, city.id, locale)) return@intent
                reduce {
                    state.copy(
                        renderState = renderStateFor(city, state.location.fix, viewport.zoom),
                        contentState = MapContentState.Loading,
                    )
                }
            }
            delay(VIEWPORT_DEBOUNCE_MILLIS)
            if (!isCurrentRequest(generation, city.id, locale)) return@launch
            val firstResult = requestNearby(city.id, viewport, locale)
            val result = recoverInvalidatedQueryOnce(firstResult, city, viewport, locale, generation)
            intent {
                // Capability is deliberately checked again: a kill switch can change while an
                // upstream implementation ignores cancellation.
                if (!isCurrentRequest(generation, city.id, locale)) return@intent
                applyNearbyResult(result)
                reduce {
                    state.copy(
                        renderState = renderStateFor(city, state.location.fix, viewport.zoom),
                        contentState = result.contentState(),
                        selectedStop = selectedStopUi(),
                        nearbyStops = nearbyStopItems(),
                        stopArrivalsSheet = if (stopArrivalsStop == null) null else state.stopArrivalsSheet,
                    )
                }
            }
        }
    }

    private suspend fun requestNearby(
        cityId: CityId,
        viewport: MapViewport,
        locale: TransitLocale,
    ): TransitLoadResult<List<TransitStop>> = try {
        repository.nearbyStops(
            cityId = cityId,
            center = viewport.center,
            radiusMeters = viewport.radiusMeters,
            limit = NEARBY_STOP_LIMIT,
            locale = locale,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        TransitLoadResult.Failure(TransitFailure.Transport("Nearby stops request failed"))
    }

    private suspend fun recoverInvalidatedQueryOnce(
        firstResult: TransitLoadResult<List<TransitStop>>,
        city: TransitCity,
        viewport: MapViewport,
        locale: TransitLocale,
        generation: Long,
    ): TransitLoadResult<List<TransitStop>> {
        val failure = (firstResult as? TransitLoadResult.Failure)?.error ?: return firstResult
        return when (failure) {
            is TransitFailure.CityNotFound -> recoverCityNotFound(city, viewport, locale, generation, failure)
            is TransitFailure.ProviderIdChanged -> {
                if (!clearStopsForRecovery(city, viewport, locale, generation)) return firstResult
                requestNearby(city.id, viewport, locale)
            }
            else -> firstResult
        }
    }

    private suspend fun recoverCityNotFound(
        city: TransitCity,
        viewport: MapViewport,
        locale: TransitLocale,
        generation: Long,
        originalFailure: TransitFailure.CityNotFound,
    ): TransitLoadResult<List<TransitStop>> {
        if (!clearStopsForRecovery(city, viewport, locale, generation)) {
            return TransitLoadResult.Failure(originalFailure)
        }
        val catalogResult = try {
            repository.revalidateCityCapabilities()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            TransitLoadResult.Failure(TransitFailure.Transport("City catalog revalidation failed"))
        }
        if (!isCurrentRequest(generation, city.id, locale)) return TransitLoadResult.Failure(originalFailure)

        val refreshedCities = when (catalogResult) {
            is TransitLoadResult.Data -> {
                if (catalogResult.freshness == TransitFreshness.StaleOffline) {
                    return TransitLoadResult.Failure(
                        catalogResult.revalidationFailure
                            ?: TransitFailure.Transport("City catalog revalidation failed"),
                    )
                }
                catalogResult.value
            }
            is TransitLoadResult.Empty -> {
                if (catalogResult.freshness == TransitFreshness.StaleOffline) {
                    return TransitLoadResult.Failure(
                        catalogResult.revalidationFailure
                            ?: TransitFailure.Transport("City catalog revalidation failed"),
                    )
                }
                emptyList()
            }
            is TransitLoadResult.Failure -> return TransitLoadResult.Failure(catalogResult.error)
        }
        val refreshedCity = refreshedCities.firstOrNull { it.id == city.id }
        if (refreshedCity == null) {
            session.clearSelectedCity()
            return TransitLoadResult.Failure(originalFailure)
        }
        if (refreshedCity != currentCity) session.selectCity(refreshedCity)
        if (!refreshedCity.capabilities.stops) {
            return TransitLoadResult.Failure(
                TransitFailure.CapabilityUnavailable(
                    message = "Nearby stops are disabled for the selected city",
                    requestId = originalFailure.requestId,
                ),
            )
        }
        if (!isCurrentRequest(generation, city.id, locale)) return TransitLoadResult.Failure(originalFailure)
        return requestNearby(city.id, viewport, locale)
    }

    private suspend fun clearStopsForRecovery(
        city: TransitCity,
        viewport: MapViewport,
        locale: TransitLocale,
        generation: Long,
    ): Boolean {
        if (!isCurrentRequest(generation, city.id, locale)) return false
        intent {
            if (!isCurrentRequest(generation, city.id, locale)) return@intent
            clearStopDerivedState()
            reduce {
                state.copy(
                    renderState = renderStateFor(city, state.location.fix, viewport.zoom),
                    contentState = MapContentState.Loading,
                    selectedStop = null,
                    nearbyStops = emptyList(),
                    stopArrivalsSheet = null,
                )
            }
        }.join()
        return isCurrentRequest(generation, city.id, locale)
    }

    private fun isCurrentRequest(generation: Long, cityId: CityId, locale: TransitLocale): Boolean =
        generation == nearbyGeneration && currentCity?.id == cityId &&
            currentCity?.capabilities?.stops == true && capabilityBlockedCityId != cityId && currentLocale == locale

    private fun applyNearbyResult(result: TransitLoadResult<List<TransitStop>>) {
        when (result) {
            is TransitLoadResult.Data -> {
                // A viewport page describes only that viewport. Its absence of a selected stop is
                // not an authoritative deletion, so the independent sheet snapshot remains open.
                rawStops = result.value.take(NEARBY_STOP_LIMIT)
            }
            is TransitLoadResult.Empty -> if (result.freshness != TransitFreshness.StaleOffline) {
                rawStops = emptyList()
            }
            is TransitLoadResult.Failure -> when (result.error) {
                is TransitFailure.CapabilityUnavailable -> {
                    // A server-side kill switch remains closed until the session publishes an
                    // actual city/capability change; panning cannot bypass it.
                    nearbyGeneration++
                    capabilityBlockedCityId = currentCity?.id
                    clearStopDerivedState()
                }
                is TransitFailure.CityNotFound,
                is TransitFailure.ProviderIdChanged,
                -> clearStopDerivedState()
                else -> Unit
            }
        }
    }

    private fun TransitLoadResult<List<TransitStop>>.contentState(): MapContentState = when (this) {
        is TransitLoadResult.Data -> if (freshness == TransitFreshness.StaleOffline) {
            MapContentState.Offline(isStale = true)
        } else {
            MapContentState.Ready
        }
        is TransitLoadResult.Empty -> if (freshness == TransitFreshness.StaleOffline) {
            MapContentState.Offline(isStale = true)
        } else {
            MapContentState.Empty
        }
        is TransitLoadResult.Failure -> if (error.canUseLastKnownGood) {
            MapContentState.RetryableError
        } else {
            MapContentState.Unavailable
        }
    }

    private fun clearStopDerivedState() {
        rawStops = emptyList()
        selectedStopId = null
        resetStopArrivalsState()
    }

    private fun selectStop(stopId: StopId, requireVisibleMarker: Boolean) = intent {
        val stop = rawStops.firstOrNull { it.id == stopId } ?: return@intent
        if (requireVisibleMarker && state.renderState?.stops?.none { it.id == stopId } != false) return@intent
        selectedStopId = stopId
        val city = currentCity ?: return@intent
        openStopArrivals(stop, city)
        reduce {
            state.copy(
                renderState = renderStateFor(city, state.location.fix, lastViewport?.zoom),
                selectedStop = selectedStopUi(),
                nearbyStops = nearbyStopItems(),
                stopArrivalsSheet = stopArrivalsSheetUi(),
            )
        }
        startStopArrivalsPollingIfEligible()
    }

    /** Opens from the nearby-stop snapshot so later viewport results cannot rewrite the header. */
    private fun openStopArrivals(stop: TransitStop, city: TransitCity) {
        resetStopArrivalsState()
        stopArrivalsStop = stop
        stopArrivalsRouteLabels = routeLabelsFor(city.id, repository.routes(city.id))
    }

    private fun stopArrivalsSheetUi(): StopArrivalsSheetUi? {
        val stop = stopArrivalsStop ?: return null
        val routeDetails = routeDetailsFor(stop.routeIds)
        return StopArrivalsSheetUi(
            stopId = stop.id,
            stopName = stop.name.forLocale(currentLocale).trim(),
            stopCode = stop.code.trim().ifBlank { null },
            passingRouteShortNames = routeDetails.shortNames,
            hasUnavailableRouteDetails = routeDetails.hasUnavailableDetails,
            state = if (currentCity?.capabilities?.arrivals == true && !isStopArrivalsCapabilityBlocked()) {
                StopArrivalsSheetState.Loading
            } else {
                StopArrivalsSheetState.Unavailable
            },
        )
    }

    /** Dismissal owns only arrivals. Map-marker selection intentionally remains untouched. */
    private fun dismissStopArrivals() = intent {
        resetStopArrivalsState()
        reduce { state.copy(stopArrivalsSheet = null) }
    }

    private fun retryStopArrivals() = intent {
        val sheet = state.stopArrivalsSheet ?: return@intent
        if (sheet.state !is StopArrivalsSheetState.Offline && sheet.state !is StopArrivalsSheetState.UpstreamError) {
            return@intent
        }
        val city = currentCity
        if (city?.capabilities?.arrivals != true || isStopArrivalsCapabilityBlocked()) {
            reduce { state.copy(stopArrivalsSheet = sheet.copy(state = StopArrivalsSheetState.Unavailable, isRefreshing = false)) }
            return@intent
        }
        invalidateStopArrivalsRequest()
        reduce { state.copy(stopArrivalsSheet = sheet.copy(isRefreshing = true)) }
        startStopArrivalsPollingIfEligible()
    }

    private fun markStopArrivalsUnavailable() {
        if (stopArrivalsStop == null) return
        invalidateStopArrivalsRequest()
        stopArrivalsPage = null
        stopArrivalsFreshness = null
        intent {
            val sheet = state.stopArrivalsSheet ?: return@intent
            reduce {
                state.copy(
                    stopArrivalsSheet = sheet.copy(
                        rows = emptyList(),
                        state = StopArrivalsSheetState.Unavailable,
                        pageSource = null,
                        isStale = false,
                        isRefreshing = false,
                    ),
                )
            }
        }
    }

    /** Backgrounding cancels the active request as well as the ticker without closing the sheet. */
    private fun pauseStopArrivalsPolling() {
        if (stopArrivalsStop == null) return
        invalidateStopArrivalsRequest()
        intent {
            val sheet = state.stopArrivalsSheet ?: return@intent
            reduce { state.copy(stopArrivalsSheet = sheet.copy(isRefreshing = false)) }
        }
    }

    private fun resetStopArrivalsState() {
        stopArrivalsJob?.cancel()
        stopArrivalsJob = null
        stopArrivalsRouteRefreshJob?.cancel()
        stopArrivalsRouteRefreshJob = null
        stopArrivalsGeneration++
        stopArrivalsStop = null
        stopArrivalsRouteLabels = emptyMap()
        stopArrivalsPage = null
        stopArrivalsFreshness = null
    }

    /** Keeps the selected-stop snapshot and previous rows, but makes every old response stale. */
    private fun invalidateStopArrivalsRequest() {
        stopArrivalsJob?.cancel()
        stopArrivalsJob = null
        stopArrivalsRouteRefreshJob?.cancel()
        stopArrivalsRouteRefreshJob = null
        stopArrivalsGeneration++
    }

    /** One sequential loop plus a mutex makes every stop-arrivals request non-overlapping. */
    private fun startStopArrivalsPollingIfEligible() {
        val city = currentCity ?: return
        val stop = stopArrivalsStop ?: return
        if (!canPollStopArrivals(city, stop) || stopArrivalsJob != null) return
        val generation = stopArrivalsGeneration
        val locale = currentLocale
        stopArrivalsJob = viewModelScope.launch {
            while (isCurrentStopArrivalsSession(generation, city.id, stop.id, locale)) {
                refreshStopArrivalsRouteLabelsIfNeeded(generation, city.id, stop.id, locale)
                beginStopArrivalsLoad(generation, city.id, stop.id, locale).join()
                val result = requestStopArrivals(generation, city.id, stop.id, locale) ?: return@launch
                if (!isCurrentStopArrivalsSession(generation, city.id, stop.id, locale)) return@launch
                applyStopArrivalsResult(generation, city.id, stop.id, locale, result).join()
                delay(STOP_ARRIVALS_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun canPollStopArrivals(city: TransitCity, stop: TransitStop): Boolean =
        realtimeVisibleAndStarted && city.capabilities.arrivals && !isStopArrivalsCapabilityBlocked(city.id) &&
            selectedStopId == stop.id

    private fun isCurrentStopArrivalsSession(
        generation: Long,
        cityId: CityId,
        stopId: StopId,
        locale: TransitLocale,
    ): Boolean = generation == stopArrivalsGeneration && realtimeVisibleAndStarted &&
        currentCity?.id == cityId && currentCity?.capabilities?.arrivals == true &&
        !isStopArrivalsCapabilityBlocked(cityId) && selectedStopId == stopId &&
            stopArrivalsStop?.id == stopId && currentLocale == locale

    private fun isStopArrivalsCapabilityBlocked(): Boolean =
        currentCity?.id?.let { it == arrivalsCapabilityBlockedCityId } == true

    private fun isStopArrivalsCapabilityBlocked(cityId: CityId): Boolean =
        arrivalsCapabilityBlockedCityId == cityId

    private fun beginStopArrivalsLoad(
        generation: Long,
        cityId: CityId,
        stopId: StopId,
        locale: TransitLocale,
    ) = intent {
        if (!isCurrentStopArrivalsSession(generation, cityId, stopId, locale)) return@intent
        val sheet = state.stopArrivalsSheet ?: return@intent
        reduce {
            state.copy(
                stopArrivalsSheet = sheet.copy(
                    state = if (sheet.rows.isEmpty()) StopArrivalsSheetState.Loading else sheet.state,
                    isRefreshing = true,
                ),
            )
        }
    }

    private suspend fun requestStopArrivals(
        generation: Long,
        cityId: CityId,
        stopId: StopId,
        locale: TransitLocale,
    ): TransitLoadResult<ArrivalPage>? = stopArrivalsRequestLock.withLock {
        if (!isCurrentStopArrivalsSession(generation, cityId, stopId, locale)) return@withLock null
        try {
            repository.arrivals(cityId = cityId, stopId = stopId, limit = STOP_ARRIVALS_LIMIT, locale = locale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            TransitLoadResult.Failure(TransitFailure.Transport("Stop arrivals request failed"))
        }
    }

    private fun applyStopArrivalsResult(
        generation: Long,
        cityId: CityId,
        stopId: StopId,
        locale: TransitLocale,
        result: TransitLoadResult<ArrivalPage>,
    ) = intent {
        if (!isCurrentStopArrivalsSession(generation, cityId, stopId, locale)) return@intent
        val sheet = state.stopArrivalsSheet ?: return@intent
        val updated = when (result) {
            is TransitLoadResult.Data -> {
                stopArrivalsPage = result.value
                stopArrivalsFreshness = result.freshness
                sheet.withArrivalPage(result.value, result.freshness)
            }
            is TransitLoadResult.Empty -> {
                stopArrivalsPage = null
                stopArrivalsFreshness = result.freshness
                sheet.copy(
                    rows = emptyList(),
                    state = if (result.freshness == TransitFreshness.StaleOffline) {
                        StopArrivalsSheetState.Offline
                    } else {
                        StopArrivalsSheetState.NoArrivals
                    },
                    pageSource = null,
                    isStale = result.freshness == TransitFreshness.StaleOffline,
                    isRefreshing = false,
                )
            }
            is TransitLoadResult.Failure -> if (result.error is TransitFailure.CapabilityUnavailable) {
                // The BFF kill switch wins over all cached/snapshotted arrival content. Advancing
                // the generation also makes a late, non-cooperative result fail closed.
                arrivalsCapabilityBlockedCityId = cityId
                stopArrivalsGeneration++
                stopArrivalsJob = null
                stopArrivalsRouteRefreshJob?.cancel()
                stopArrivalsRouteRefreshJob = null
                stopArrivalsPage = null
                stopArrivalsFreshness = null
                sheet.copy(
                    rows = emptyList(),
                    state = StopArrivalsSheetState.Unavailable,
                    pageSource = null,
                    isStale = false,
                    isRefreshing = false,
                )
            } else {
                sheet.copy(
                    state = result.error.stopArrivalsState(),
                    // An error never turns an already-rendered response into current data.
                    isStale = sheet.isStale || sheet.rows.isNotEmpty(),
                    isRefreshing = false,
                )
            }
        }
        reduce { state.copy(stopArrivalsSheet = updated) }
    }

    /** A failed catalogue request is retried on the next 20-second sheet cycle, never concurrently. */
    private fun refreshStopArrivalsRouteLabelsIfNeeded(
        generation: Long,
        cityId: CityId,
        stopId: StopId,
        locale: TransitLocale,
    ) {
        val city = currentCity ?: return
        val stop = stopArrivalsStop ?: return
        if (
            !isCurrentStopArrivalsSession(generation, cityId, stopId, locale) || city.id != cityId ||
            !city.capabilities.routes || !routeDetailsFor(stop.routeIds).hasUnavailableDetails ||
            stopArrivalsRouteRefreshJob?.isActive == true
        ) return
        stopArrivalsRouteRefreshJob = viewModelScope.launch {
            refreshStopArrivalsRouteLabels(generation, cityId, stopId, locale)
        }
    }

    private suspend fun refreshStopArrivalsRouteLabels(
        generation: Long,
        cityId: CityId,
        stopId: StopId,
        locale: TransitLocale,
    ) {
        val result = stopArrivalsRouteRequestLock.withLock {
            if (!isCurrentStopArrivalsSession(generation, cityId, stopId, locale)) return@withLock null
            try {
                repository.refreshRoutes(RouteListRequest(cityId = cityId, locale = locale))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                TransitLoadResult.Failure(TransitFailure.Transport("Route catalogue request failed"))
            }
        } ?: return
        if (!isCurrentStopArrivalsSession(generation, cityId, stopId, locale)) return
        if (result is TransitLoadResult.Data) {
            stopArrivalsRouteLabels = routeLabelsFor(cityId, result.value)
            intent {
                if (!isCurrentStopArrivalsSession(generation, cityId, stopId, locale)) return@intent
                val sheet = state.stopArrivalsSheet ?: return@intent
                reduce { state.copy(stopArrivalsSheet = sheet.withCurrentArrivalPage()) }
            }.join()
        }
    }

    private fun StopArrivalsSheetUi.withCurrentArrivalPage(): StopArrivalsSheetUi {
        val page = stopArrivalsPage ?: return copy(
            passingRouteShortNames = routeDetailsFor(stopArrivalsStop?.routeIds.orEmpty()).shortNames,
            hasUnavailableRouteDetails = routeDetailsFor(stopArrivalsStop?.routeIds.orEmpty()).hasUnavailableDetails,
        )
        val freshness = stopArrivalsFreshness ?: TransitFreshness.Network
        val updated = withArrivalPage(page, freshness)
        // Resolving a catalogue label does not erase a separately observed arrivals failure.
        return if (state is StopArrivalsSheetState.Offline || state is StopArrivalsSheetState.UpstreamError ||
            state is StopArrivalsSheetState.Unavailable
        ) {
            updated.copy(state = state, isStale = isStale, isRefreshing = isRefreshing)
        } else {
            updated.copy(isRefreshing = isRefreshing)
        }
    }

    private fun StopArrivalsSheetUi.withArrivalPage(
        page: ArrivalPage,
        freshness: TransitFreshness,
    ): StopArrivalsSheetUi {
        val stop = stopArrivalsStop ?: return this
        val routeDetails = routeDetailsFor(stop.routeIds)
        val pageDetails = page.toUiDetails(stop.id)
        val completeDetails = pageDetails.copy(
            hasUnavailableRouteDetails = pageDetails.hasUnavailableRouteDetails || routeDetails.hasUnavailableDetails,
        )
        return copy(
            passingRouteShortNames = routeDetails.shortNames,
            hasUnavailableRouteDetails = completeDetails.hasUnavailableRouteDetails,
            rows = completeDetails.rows,
            state = page.stopArrivalsState(completeDetails, freshness),
            pageSource = page.source.toUiSource(),
            isStale = page.stale || freshness == TransitFreshness.StaleOffline,
            isRefreshing = false,
        )
    }

    /**
     * Primary ordering is earliest known ETA. Equal ETAs retain BFF response order, which is the
     * only provider-semantic tie-breaker available and prevents a synthetic ID sort from jittering.
     */
    private fun ArrivalPage.toUiDetails(selectedStopId: StopId): ArrivalUiDetails {
        var rejectedRows = false
        val indexedRows = items.mapIndexedNotNull { index, arrival ->
            if (arrival.stopId != selectedStopId || arrival.cancelled || arrival.expectedInMinutes?.let { it < 0 } == true) {
                rejectedRows = true
                null
            } else {
                val routeShortName = stopArrivalsRouteLabels[arrival.routeId]
                val headsign = arrival.headsign.forLocale(currentLocale).trim()
                if (routeShortName == null || headsign.isBlank()) rejectedRows = true
                IndexedArrivalRow(
                    index = index,
                    row = StopArrivalRowUi(
                        routeShortName = routeShortName,
                        headsign = headsign,
                        time = arrival.expectedInMinutes.toUiTime(),
                        source = arrival.source.toUiSource(),
                    ),
                    hasUnavailableRouteDetails = routeShortName == null,
                )
            }
        }
        val ordered = indexedRows.sortedWith(
            compareBy<IndexedArrivalRow> { it.row.time.sortOrder() }.thenBy(IndexedArrivalRow::index),
        )
        return ArrivalUiDetails(
            rows = ordered.map(IndexedArrivalRow::row),
            hasUnavailableRouteDetails = ordered.any(IndexedArrivalRow::hasUnavailableRouteDetails),
            hasRejectedRows = rejectedRows,
        )
    }

    private fun ArrivalPage.stopArrivalsState(
        details: ArrivalUiDetails,
        freshness: TransitFreshness,
    ): StopArrivalsSheetState = when {
        freshness == TransitFreshness.StaleOffline -> StopArrivalsSheetState.Offline
        items.isEmpty() -> StopArrivalsSheetState.NoArrivals
        details.hasRejectedRows || details.hasUnavailableRouteDetails -> StopArrivalsSheetState.PartialData
        else -> StopArrivalsSheetState.Ready
    }

    private fun Int?.toUiTime(): StopArrivalTimeUi = when {
        this == null -> StopArrivalTimeUi.Unavailable
        this == 0 -> StopArrivalTimeUi.Arriving
        else -> StopArrivalTimeUi.Minutes(this)
    }

    private fun StopArrivalTimeUi.sortOrder(): Int = when (this) {
        StopArrivalTimeUi.Arriving -> 0
        is StopArrivalTimeUi.Minutes -> value
        StopArrivalTimeUi.Unavailable -> Int.MAX_VALUE
    }

    private fun ArrivalSource.toUiSource(): ArrivalSourceUi = when (this) {
        ArrivalSource.OfficialRealtime -> ArrivalSourceUi.OfficialRealtime
        ArrivalSource.AggregatorRealtime -> ArrivalSourceUi.AggregatorRealtime
        ArrivalSource.Schedule -> ArrivalSourceUi.Schedule
        ArrivalSource.ClientEstimate -> ArrivalSourceUi.Approximate
    }

    private fun TransitFailure.stopArrivalsState(): StopArrivalsSheetState = when (this) {
        is TransitFailure.Transport,
        is TransitFailure.Timeout,
        -> StopArrivalsSheetState.Offline

        is TransitFailure.UpstreamUnavailable,
        is TransitFailure.UpstreamTimeout,
        is TransitFailure.UpstreamBadResponse,
        is TransitFailure.RateLimited,
        is TransitFailure.Internal,
        -> StopArrivalsSheetState.UpstreamError

        else -> StopArrivalsSheetState.Unavailable
    }

    private fun routeLabelsFor(cityId: CityId, routes: List<TransitRoute>): Map<RouteId, String> = routes
        .asSequence()
        .filter { it.cityId == cityId }
        .mapNotNull { route -> route.shortName.trim().ifBlank { null }?.let { route.id to it } }
        .toMap()

    private fun routeDetailsFor(routeIds: List<RouteId>): RouteDetails {
        val shortNames = routeIds.mapNotNull(stopArrivalsRouteLabels::get).distinct()
        return RouteDetails(
            shortNames = shortNames,
            hasUnavailableDetails = routeIds.any { it !in stopArrivalsRouteLabels },
        )
    }

    /** Invalidates old locale-specific labels/headsings but keeps immutable normalized page data. */
    private fun restartStopArrivalsForLocale(current: StopArrivalsSheetUi?): StopArrivalsSheetUi? {
        val stop = stopArrivalsStop ?: return null
        val city = currentCity ?: return null
        invalidateStopArrivalsRequest()
        stopArrivalsRouteLabels = routeLabelsFor(city.id, repository.routes(city.id))
        val replacement = current ?: stopArrivalsSheetUi() ?: return null
        val routeDetails = routeDetailsFor(stop.routeIds)
        val localizedHeader = replacement.copy(
            stopName = stop.name.forLocale(currentLocale).trim(),
            stopCode = stop.code.trim().ifBlank { null },
            passingRouteShortNames = routeDetails.shortNames,
            hasUnavailableRouteDetails = routeDetails.hasUnavailableDetails,
        )
        if (!city.capabilities.arrivals || isStopArrivalsCapabilityBlocked(city.id)) {
            // Locale is presentation-only; it cannot reopen a capability-disabled sheet or reuse
            // its normalized page as if arrivals remained available.
            stopArrivalsPage = null
            stopArrivalsFreshness = null
            return localizedHeader.copy(
                rows = emptyList(),
                state = StopArrivalsSheetState.Unavailable,
                pageSource = null,
                isStale = false,
                isRefreshing = false,
            )
        }
        val page = stopArrivalsPage
        return if (page == null) {
            localizedHeader.copy(
                state = StopArrivalsSheetState.Loading,
                isRefreshing = false,
            )
        } else {
            localizedHeader.withArrivalPage(page, stopArrivalsFreshness ?: TransitFreshness.Network)
        }
    }

    private fun selectedStopUi(): SelectedStopUi? {
        val selected = selectedStopId ?: return null
        val stop = rawStops.firstOrNull { it.id == selected } ?: return null
        return SelectedStopUi(selected, stop.displayName(currentLocale))
    }

    private fun nearbyStopItems(): List<NearbyStopUi> = rawStops.map { stop ->
        NearbyStopUi(
            id = stop.id,
            name = stop.displayName(currentLocale),
            isSelected = stop.id == selectedStopId,
        )
    }

    private fun TransitStop.displayName(locale: TransitLocale): String =
        name.forLocale(locale).ifBlank { code.ifBlank { id.value } }

    private fun onMyLocationClicked() = intent {
        val command = when (val permission = state.location.permission) {
            is LocationPermissionState.Granted -> {
                beginLocationRequest()?.let { postSideEffect(SideEffect.HandleLocation(it)) }
                return@intent
            }
            LocationPermissionState.NotDetermined,
            LocationPermissionState.Denied,
            -> LocationPlatformCommand.RequestPermission(nextCommandId())
            LocationPermissionState.SettingsRequired -> LocationPlatformCommand.OpenAppSettings(nextCommandId())
            LocationPermissionState.ServicesDisabled -> LocationPlatformCommand.OpenLocationSettings(nextCommandId())
            LocationPermissionState.Restricted -> return@intent
            is LocationPermissionState.Unavailable -> {
                if (!permission.canRetry) return@intent
                LocationPlatformCommand.QueryPermission(nextCommandId())
            }
            is LocationPermissionState.Error -> {
                if (!permission.canRetry) return@intent
                LocationPlatformCommand.QueryPermission(nextCommandId())
            }
        }
        postSideEffect(SideEffect.HandleLocation(command))
    }

    private fun onLocationEvent(event: LocationPlatformEvent) = intent {
        when (event) {
            is LocationPlatformEvent.PermissionChanged -> {
                locationSession.updatePermission(event.permission)
                val location = locationSession.state.value
                if (
                    event.permission is LocationPermissionState.Granted && location.failure == null &&
                    location.fix == null && location.activeRequestId == null
                ) {
                    beginLocationRequest()?.let { postSideEffect(SideEffect.HandleLocation(it)) }
                }
            }
            is LocationPlatformEvent.FixReceived -> locationSession.accept(event.requestId, event.candidate)
            is LocationPlatformEvent.Failed -> locationSession.fail(event.requestId, event.failure)
            is LocationPlatformEvent.Cancelled -> locationSession.cancelRequest(event.requestId)
        }
    }

    private fun beginLocationRequest(): LocationPlatformCommand.RequestLocation? {
        val requestId = nextCommandId()
        return LocationPlatformCommand.RequestLocation(requestId).takeIf { locationSession.beginLocationRequest(requestId) }
    }

    private fun nextCommandId(): LocationCommandId = locationSession.nextCommandId()

    private fun initialViewport(city: TransitCity): MapViewport = MapViewport(
        center = city.center,
        radiusMeters = initialRadiusMeters(city.defaultZoom),
        zoom = city.defaultZoom,
    )

    private fun MapViewport.normalized(): MapViewport = copy(
        center = GeoPoint(
            latitude = center.latitude.roundTo(VIEWPORT_COORDINATE_DECIMALS),
            longitude = center.longitude.roundTo(VIEWPORT_COORDINATE_DECIMALS),
        ),
        radiusMeters = (ceil(radiusMeters / VIEWPORT_RADIUS_STEP_METERS.toDouble()).toInt() *
            VIEWPORT_RADIUS_STEP_METERS).coerceIn(MIN_NEARBY_RADIUS_METERS, MAX_NEARBY_RADIUS_METERS),
        zoom = zoom.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM).roundTo(VIEWPORT_ZOOM_DECIMALS),
    )

    private fun initialRadiusMeters(zoom: Double): Int = when {
        zoom >= 16.0 -> 1_500
        zoom >= 14.0 -> 3_000
        zoom >= 12.0 -> 7_500
        zoom >= 10.0 -> 20_000
        else -> MAX_NEARBY_RADIUS_METERS
    }

    private fun Double.roundTo(decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return round(this * factor) / factor
    }

    private companion object {
        const val USER_LOCATION_ZOOM = 15.0
        const val VIEWPORT_DEBOUNCE_MILLIS = 350L
        const val NEARBY_STOP_LIMIT = 100
        const val MIN_NEARBY_RADIUS_METERS = 1
        const val MAX_NEARBY_RADIUS_METERS = 50_000
        const val MIN_MAP_ZOOM = 0.0
        const val MAX_MAP_ZOOM = 22.0
        const val VIEWPORT_COORDINATE_DECIMALS = 5
        const val VIEWPORT_ZOOM_DECIMALS = 2
        const val VIEWPORT_RADIUS_STEP_METERS = 25
        /** Must match the common reducer cap so adapters never receive a larger transient page. */
        const val MAX_COMMON_VEHICLE_MARKERS = 1_000
        const val MAX_SMOOTHLY_ANIMATED_VEHICLES = 250
        const val HIGH_DENSITY_FRAME_INTERVAL_MILLIS = 100L // 10 fps maximum above the smooth cap.
        const val INTERPOLATION_ACTIVE_WINDOW_MILLIS = 1_000L
        const val STOP_ARRIVALS_LIMIT = 40
        const val STOP_ARRIVALS_POLL_INTERVAL_MILLIS = 20_000L
    }
}

/** Badge labels are local display text only; cap them before they cross the native image boundary. */
private fun String.sanitizedRouteBadgeLabel(): String = asSequence()
    .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
    .joinToString(separator = "")
    .trim()
    .replace(Regex("\\s+"), " ")
    .take(MAX_ROUTE_BADGE_LABEL_LENGTH)
    .ifBlank { "?" }

private fun TransitRoute.contrastSafeTextColor(): Long {
    val backgroundLuminance = colorArgb.relativeLuminance()
    val suppliedContrast = contrastRatio(backgroundLuminance, textColorArgb.relativeLuminance())
    if (suppliedContrast >= MIN_BADGE_TEXT_CONTRAST) return textColorArgb or OPAQUE_ALPHA_MASK
    val blackContrast = contrastRatio(backgroundLuminance, 0.0)
    val whiteContrast = contrastRatio(backgroundLuminance, 1.0)
    return if (blackContrast >= whiteContrast) OPAQUE_BLACK else OPAQUE_WHITE
}

private fun Long.relativeLuminance(): Double {
    fun channel(shift: Int): Double {
        val value = ((this shr shift) and 0xFF).toDouble() / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

private fun contrastRatio(first: Double, second: Double): Double =
    (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)

/** Matches exactly the vehicle properties consumed by the native grouped GeoJSON sources. */
private fun List<MapVehicleMarker>.hasSameRenderedVehicleSourceAs(other: List<MapVehicleMarker>): Boolean =
    size == other.size && zip(other).all { (next, current) ->
        next.id == current.id && next.position == current.position && next.bearingDegrees == current.bearingDegrees &&
            next.positionKind == current.positionKind && next.routeLabel == current.routeLabel &&
            next.routeColorArgb == current.routeColorArgb && next.routeTextColorArgb == current.routeTextColorArgb &&
            next.isStale == current.isStale
    }

/** Badge bitmap keys deliberately exclude geometry/frame revisions so animation never touches them. */
private fun List<MapVehicleMarker>.hasSameBadgeStylesAs(other: List<MapVehicleMarker>): Boolean =
    asSequence()
        .map { marker -> VehicleBadgeRenderStyle(marker.routeLabel, marker.routeColorArgb, marker.routeTextColorArgb, marker.isStale) }
        .toSet() == other.asSequence()
        .map { marker -> VehicleBadgeRenderStyle(marker.routeLabel, marker.routeColorArgb, marker.routeTextColorArgb, marker.isStale) }
        .toSet()

private data class VehicleBadgeRenderStyle(
    val label: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val stale: Boolean,
)

private const val MAX_ROUTE_BADGE_LABEL_LENGTH = 8
private const val MIN_BADGE_TEXT_CONTRAST = 4.5
private const val OPAQUE_ALPHA_MASK = 0xFF000000L
private const val OPAQUE_BLACK = 0xFF000000L
private const val OPAQUE_WHITE = 0xFFFFFFFFL

private data class VehicleRequestKey(val cityId: CityId, val routeId: RouteId)

/** Ticker publication is driven by observable vehicle output, never by an otherwise idle frame. */
private data class VehicleFrameUpdate(
    val accessibilityChanged: Boolean,
    val renderedSourceChanged: Boolean,
) {
    val shouldPublish: Boolean get() = accessibilityChanged || renderedSourceChanged
}

private data class RouteDetails(
    val shortNames: List<String>,
    val hasUnavailableDetails: Boolean,
)

private data class IndexedArrivalRow(
    val index: Int,
    val row: StopArrivalRowUi,
    val hasUnavailableRouteDetails: Boolean,
)

private data class ArrivalUiDetails(
    val rows: List<StopArrivalRowUi>,
    val hasUnavailableRouteDetails: Boolean,
    val hasRejectedRows: Boolean,
)
