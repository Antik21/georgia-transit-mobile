package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal sealed interface MapNearbyEvent {
    val request: NearbyRequest
    data class Loading(override val request: NearbyRequest) : MapNearbyEvent
    data class Cleared(override val request: NearbyRequest) : MapNearbyEvent
    data class Result(
        override val request: NearbyRequest,
        val result: TransitLoadResult<List<TransitStop>>,
    ) : MapNearbyEvent
}

/** Adapts viewport loading and recovery into one ordered stream of typed outcomes. */
internal class MapNearbyWorkflow(private val nearby: MapNearbyStops) {
    fun start(
        scope: CoroutineScope,
        city: TransitCity,
        viewport: MapViewport,
        locale: TransitLocale,
        force: Boolean,
        currentCity: () -> TransitCity?,
        currentLocale: () -> TransitLocale,
        onEvent: suspend (MapNearbyEvent) -> Unit,
    ): NearbyRequest? = nearby.start(
        scope, city, viewport, locale, force, currentCity, currentLocale,
        onLoading = { request -> scope.launch { onEvent(MapNearbyEvent.Loading(request)) } },
        clearForRecovery = { request ->
            onEvent(MapNearbyEvent.Cleared(request))
            nearby.isCurrent(request, currentCity(), currentLocale())
        },
        onResult = { request, result -> scope.launch { onEvent(MapNearbyEvent.Result(request, result)) } },
    )

    /** Apply only after the VM validates the request inside its serialized intent. */
    fun accept(result: TransitLoadResult<List<TransitStop>>, city: TransitCity): NearbyOutcome {
        val clearDerived = nearby.accept(result, city.id)
        val advanceRevision = !clearDerived && (result is TransitLoadResult.Data ||
            result is TransitLoadResult.Empty && result.freshness != TransitFreshness.StaleOffline)
        return NearbyOutcome(nearby.contentState(result), clearDerived, advanceRevision)
    }
}

internal data class NearbyOutcome(
    val contentState: MapContentState,
    val clearDerived: Boolean,
    val advanceRevision: Boolean,
)
