package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class SelectedRouteStopKey(val routeId: RouteId, val directionId: DirectionId)

internal data class UserLocationStopKey(
    val cityId: CityId,
    val point: GeoPoint,
    val locale: TransitLocale,
)

/** Fetches stop sources independent of viewport paging; jobs never own screen UI state. */
internal class MapSupplementalStops(private val repository: TransitRepository) {
    private var userKey: UserLocationStopKey? = null
    private var userJob: Job? = null
    private var userGeneration = 0L
    private var userCompleted = false
    var userStops: List<TransitStop> = emptyList()
        private set
    private var selectedGeneration = 0L
    private val selectedJobs = mutableMapOf<SelectedRouteStopKey, Job>()
    private val completedSelectedKeys = mutableSetOf<SelectedRouteStopKey>()
    val selectedStops = mutableMapOf<SelectedRouteStopKey, List<TransitStop>>()

    fun resetUser(): Boolean {
        userJob?.cancel()
        userJob = null
        userGeneration++
        userKey = null
        userCompleted = false
        val changed = userStops.isNotEmpty()
        userStops = emptyList()
        return changed
    }

    fun reconcileUser(city: TransitCity?, point: GeoPoint?, locale: TransitLocale): Pair<UserLocationStopKey?, Boolean> {
        val key = city?.takeIf { it.capabilities.stops }?.let { selectedCity ->
            point?.takeIf(GeoPoint::isMapCoordinate)?.let { validPoint ->
                UserLocationStopKey(
                    cityId = selectedCity.id,
                    point = GeoPoint(
                        latitude = with(MapViewportPolicy) { validPoint.latitude.roundTo(4) },
                        longitude = with(MapViewportPolicy) { validPoint.longitude.roundTo(4) },
                    ),
                    locale = locale,
                )
            }
        }
        if (key == userKey) return key to false
        val changed = resetUser()
        userKey = key
        return key to changed
    }

    fun startUser(scope: CoroutineScope, key: UserLocationStopKey?, onChanged: () -> Unit) {
        if (key == null || key != userKey || userJob != null || userCompleted) return
        val generation = userGeneration
        userJob = scope.launch {
            val result = try {
                repository.nearbyStops(key.cityId, key.point, radiusMeters = 1_000, limit = 100, locale = key.locale)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                TransitLoadResult.Failure(TransitFailure.Transport("User-location stops request failed"))
            }
            if (generation == userGeneration) userJob = null
            if (generation != userGeneration || key != userKey) return@launch
            userCompleted = true
            val stops = when (result) {
                is TransitLoadResult.Data -> result.value
                is TransitLoadResult.Empty, is TransitLoadResult.Failure -> emptyList()
            }.asSequence()
                .filter { it.id.value.isNotBlank() && it.position.isMapCoordinate() }
                .distinctBy(TransitStop::id)
                .take(100)
                .toList()
            if (stops != userStops) {
                userStops = stops
                onChanged()
            }
        }
    }

    fun resetSelected(): Boolean {
        selectedJobs.values.forEach(Job::cancel)
        selectedJobs.clear()
        selectedGeneration++
        val changed = selectedStops.isNotEmpty()
        selectedStops.clear()
        completedSelectedKeys.clear()
        return changed
    }

    fun pauseSelected() {
        if (selectedJobs.isEmpty()) return
        selectedJobs.values.forEach(Job::cancel)
        selectedJobs.clear()
        selectedGeneration++
    }

    fun startSelected(
        scope: CoroutineScope,
        city: TransitCity,
        routes: List<TransitRoute>,
        locale: TransitLocale,
        isCurrent: (SelectedRouteStopKey) -> Boolean,
        onChanged: () -> Unit,
    ) {
        val generation = selectedGeneration
        routes.flatMap { route -> route.directions.map { SelectedRouteStopKey(route.id, it.id) } }.forEach { key ->
            if (key in completedSelectedKeys || key in selectedJobs) return@forEach
            selectedJobs[key] = scope.launch {
                val result = try {
                    repository.directionStops(city.id, key.routeId, key.directionId, locale)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    TransitLoadResult.Failure(TransitFailure.Transport("Selected route stops request failed"))
                }
                if (generation == selectedGeneration) selectedJobs.remove(key)
                if (generation != selectedGeneration || !isCurrent(key)) return@launch
                completedSelectedKeys += key
                val stops = when (result) {
                    is TransitLoadResult.Data -> result.value
                    is TransitLoadResult.Empty, is TransitLoadResult.Failure -> emptyList()
                }.asSequence()
                    .filter { it.id.value.isNotBlank() && it.position.isMapCoordinate() && key.routeId in it.routeIds }
                    .distinctBy(TransitStop::id)
                    .toList()
                if (selectedStops[key] != stops) {
                    selectedStops[key] = stops
                    onChanged()
                }
            }
        }
    }
}
