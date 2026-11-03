package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class VehiclePollKey(val generation: Long, val cityId: CityId)

internal sealed interface VehiclePollEvent {
    val key: VehiclePollKey
    data class Loading(override val key: VehiclePollKey, val routeId: RouteId) : VehiclePollEvent
    data class Result(
        override val key: VehiclePollKey,
        val routeId: RouteId,
        val page: TransitLoadResult<VehiclePage>,
    ) : VehiclePollEvent
    data class Frame(override val key: VehiclePollKey) : VehiclePollEvent
}

/** Owns route request locks, route polling and the frame ticker for one Map entry. */
internal class MapVehiclePoller(
    private val repository: TransitRepository,
    private val tickerPolicy: VehicleRealtimeTickerPolicy,
) {
    private val locks = mutableMapOf<Pair<CityId, RouteId>, Mutex>()
    private var routeJobs: List<Job> = emptyList()
    private var frameJob: Job? = null
    var generation = 0L
        private set

    fun reset() {
        routeJobs.forEach(Job::cancel)
        routeJobs = emptyList()
        frameJob?.cancel()
        frameJob = null
        generation++
    }

    fun start(
        scope: CoroutineScope,
        cityId: CityId,
        routes: List<TransitRoute>,
        canContinue: (VehiclePollKey) -> Boolean,
        routeActive: (RouteId) -> Boolean,
        frameDelayMillis: () -> Long,
        onEvent: suspend (VehiclePollEvent) -> Unit,
    ) {
        if (routeJobs.isNotEmpty()) return
        val key = VehiclePollKey(generation, cityId)
        routeJobs = routes.map { route ->
            scope.launch {
                val routeId = route.id
                while (canContinue(key) && routeActive(routeId)) {
                    onEvent(VehiclePollEvent.Loading(key, routeId))
                    val lock = locks.getOrPut(cityId to routeId, ::Mutex)
                    val result = lock.withLock {
                        if (!canContinue(key) || !routeActive(routeId)) return@withLock null
                        try {
                            repository.vehicles(cityId, routeId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            TransitLoadResult.Failure(TransitFailure.Transport("Vehicle positions request failed"))
                        }
                    } ?: return@launch
                    if (!canContinue(key) || !routeActive(routeId)) return@launch
                    onEvent(VehiclePollEvent.Result(key, routeId, result))
                    delay(tickerPolicy.pollIntervalMillis)
                }
            }
        }
        frameJob = scope.launch {
            while (canContinue(key)) {
                onEvent(VehiclePollEvent.Frame(key))
                delay(frameDelayMillis())
            }
        }
    }
}
