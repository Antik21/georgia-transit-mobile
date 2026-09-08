package com.denis.georgiatransit.shared.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
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
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import kotlin.math.ceil
import kotlin.math.round

class MapViewModel(
    private val repository: TransitRepository,
    private val session: TransitSession,
    private val locationSession: LocationSession,
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

                val routeNames = city?.let { selectedCity ->
                    repository.routes(selectedCity.id).filter { it.id in selectedIds }.map { it.shortName }
                }.orEmpty()
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
                    )
                }
                if ((cityChanged || stopsCapabilityChanged) && city?.capabilities?.stops == true) {
                    acceptViewport(initialViewport(city), force = true)
                }
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
            is Action.LocaleChanged -> onLocaleChanged(action.locale)
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
            userLocation = fix,
        )
    }

    private fun nextCameraCommand(center: GeoPoint, zoom: Double): MapCameraCommand =
        MapCameraCommand(center = center, zoom = zoom, revision = ++nextCameraRevision)

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
        reduce {
            state.copy(
                renderState = city?.let { renderStateFor(it, state.location.fix, lastViewport?.zoom) },
                selectedStop = selectedStopUi(),
                nearbyStops = nearbyStopItems(),
            )
        }
        if (city?.capabilities?.stops == true) {
            acceptViewport(lastViewport ?: initialViewport(city), force = true)
        }
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
                rawStops = result.value.take(NEARBY_STOP_LIMIT)
                if (rawStops.none { it.id == selectedStopId }) selectedStopId = null
            }
            is TransitLoadResult.Empty -> if (result.freshness != TransitFreshness.StaleOffline) {
                rawStops = emptyList()
                selectedStopId = null
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
    }

    private fun selectStop(stopId: StopId, requireVisibleMarker: Boolean) = intent {
        if (rawStops.none { it.id == stopId }) return@intent
        if (requireVisibleMarker && state.renderState?.stops?.none { it.id == stopId } != false) return@intent
        selectedStopId = stopId
        val city = currentCity ?: return@intent
        reduce {
            state.copy(
                renderState = renderStateFor(city, state.location.fix, lastViewport?.zoom),
                selectedStop = selectedStopUi(),
                nearbyStops = nearbyStopItems(),
            )
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
    }
}
