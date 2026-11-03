package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
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

internal data class StopArrivalsRequest(
    val generation: Long,
    val cityId: CityId,
    val stopId: StopId,
    val locale: TransitLocale,
)

internal sealed interface StopArrivalsEvent {
    val request: StopArrivalsRequest
    data class Loading(override val request: StopArrivalsRequest) : StopArrivalsEvent
    data class Arrivals(
        override val request: StopArrivalsRequest,
        val result: TransitLoadResult<ArrivalPage>,
    ) : StopArrivalsEvent
    data class Routes(
        override val request: StopArrivalsRequest,
        val routes: List<TransitRoute>,
    ) : StopArrivalsEvent
}

/** Owns the sheet's sequential request loop and independent route-label refresh. */
internal class StopArrivalsPoller(private val repository: TransitRepository) {
    private val arrivalsLock = Mutex()
    private val routesLock = Mutex()
    private var arrivalsJob: Job? = null
    private var routesJob: Job? = null
    var generation = 0L
        private set

    fun invalidate() {
        arrivalsJob?.cancel()
        arrivalsJob = null
        routesJob?.cancel()
        routesJob = null
        generation++
    }

    fun start(
        scope: CoroutineScope,
        cityId: CityId,
        stopId: StopId,
        locale: TransitLocale,
        canContinue: (StopArrivalsRequest) -> Boolean,
        shouldRefreshRoutes: (StopArrivalsRequest) -> Boolean,
        onEvent: suspend (StopArrivalsEvent) -> Unit,
    ) {
        if (arrivalsJob != null) return
        val request = StopArrivalsRequest(generation, cityId, stopId, locale)
        arrivalsJob = scope.launch {
            while (canContinue(request)) {
                if (shouldRefreshRoutes(request) && routesJob?.isActive != true) {
                    routesJob = scope.launch {
                        val routes = routesLock.withLock {
                            if (!canContinue(request)) return@withLock null
                            try {
                                repository.refreshRoutes(RouteListRequest(cityId, locale))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                TransitLoadResult.Failure(TransitFailure.Transport("Route catalogue request failed"))
                            }
                        }
                        if (canContinue(request) && routes is TransitLoadResult.Data) {
                            onEvent(StopArrivalsEvent.Routes(request, routes.value.filter { it.cityId == cityId }))
                        }
                    }
                }
                onEvent(StopArrivalsEvent.Loading(request))
                val result = arrivalsLock.withLock {
                    if (!canContinue(request)) return@withLock null
                    try {
                        repository.arrivals(cityId, stopId, limit = 100, locale = locale)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        TransitLoadResult.Failure(TransitFailure.Transport("Stop arrivals request failed"))
                    }
                } ?: return@launch
                if (!canContinue(request)) return@launch
                onEvent(StopArrivalsEvent.Arrivals(request, result))
                delay(20_000L)
            }
        }
    }
}
