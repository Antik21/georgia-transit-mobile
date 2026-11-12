package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection
import kotlinx.coroutines.CoroutineScope

/** Coordinates the selected-stop snapshot, polling generation, and sheet transitions. */
internal class StopArrivalsFeature(private val repository: TransitRepository) {
    private val presentation = StopArrivalsPresentation()
    private val poller = StopArrivalsPoller(repository)
    val stop: TransitStop? get() = presentation.stop

    fun reset() {
        poller.invalidate()
        presentation.reset()
    }

    fun open(stop: TransitStop, city: TransitCity) {
        reset()
        presentation.open(stop, cityRoutes(city))
    }

    fun invalidate() = poller.invalidate()
    fun updateCatalog(routes: List<TransitRoute>) = presentation.updateCatalog(routes)
    fun unblock() = presentation.unblock()
    fun isBlocked(city: TransitCity?): Boolean = city?.id?.let(presentation::isBlocked) == true
    fun initialSheet(city: TransitCity?, locale: TransitLocale, selection: RouteSelectionProjection) =
        presentation.initialSheet(city, locale, selection)
    fun reproject(sheet: StopArrivalsSheetUi?, locale: TransitLocale, selection: RouteSelectionProjection) =
        presentation.reproject(sheet, locale, selection)

    fun restartForLocale(
        sheet: StopArrivalsSheetUi?, city: TransitCity?, locale: TransitLocale,
        selection: RouteSelectionProjection,
    ): StopArrivalsSheetUi? {
        if (stop == null || city == null) return null
        invalidate()
        return presentation.restartForLocale(sheet, city, locale, selection, cityRoutes(city))
    }

    fun unavailable(sheet: StopArrivalsSheetUi): StopArrivalsSheetUi {
        invalidate()
        presentation.clearPage()
        return sheet.copy(
            rows = emptyList(), state = StopArrivalsSheetState.Unavailable,
            pageSource = null, isStale = false, isRefreshing = false,
        )
    }

    fun pause(sheet: StopArrivalsSheetUi): StopArrivalsSheetUi =
        sheet.copy(isRefreshing = false)

    fun retry(sheet: StopArrivalsSheetUi, city: TransitCity?): StopArrivalsSheetUi? {
        if (sheet.state !is StopArrivalsSheetState.Offline &&
            sheet.state !is StopArrivalsSheetState.UpstreamError) return null
        if (city?.capabilities?.arrivals != true || isBlocked(city)) {
            return sheet.copy(state = StopArrivalsSheetState.Unavailable, isRefreshing = false)
        }
        invalidate()
        return sheet.copy(isRefreshing = true)
    }

    fun isCurrent(
        request: StopArrivalsRequest, city: TransitCity?, locale: TransitLocale,
        selectedStopId: com.denis.georgiatransit.shared.domain.model.StopId?, visible: Boolean,
    ): Boolean = request.generation == poller.generation && visible &&
        city?.id == request.cityId && city.capabilities.arrivals &&
        !presentation.isBlocked(request.cityId) && selectedStopId == request.stopId &&
        stop?.id == request.stopId && locale == request.locale

    fun start(
        scope: CoroutineScope, city: TransitCity?, locale: TransitLocale,
        selectedStopId: com.denis.georgiatransit.shared.domain.model.StopId?,
        visible: Boolean,
        current: () -> StopArrivalsContext,
        onSheet: suspend ((StopArrivalsSheetUi) -> StopArrivalsSheetUi?) -> Unit,
    ) {
        val selectedStop = stop ?: return
        if (city == null || !visible || !city.capabilities.arrivals || isBlocked(city) ||
            selectedStopId != selectedStop.id) return
        poller.start(
            scope = scope, cityId = city.id, stopId = selectedStop.id, locale = locale,
            canContinue = { request -> current().let {
                isCurrent(request, it.city, it.locale, it.selectedStopId, it.visible)
            } },
            shouldRefreshRoutes = { request -> current().let {
                isCurrent(request, it.city, it.locale, it.selectedStopId, it.visible) &&
                    it.city?.capabilities?.routes == true &&
                    stop?.let { active -> presentation.routeDetails(active.routeIds, it.locale, it.selection)
                        .hasUnavailableDetails } == true
            } },
            onEvent = { event ->
                val request = event.request
                val isCurrentNow = { current().let { isCurrent(request, it.city, it.locale, it.selectedStopId, it.visible) } }
                // The transform runs inside the VM's scheduled intent, so currency is checked again there.
                val publish: suspend ((StopArrivalsSheetUi) -> StopArrivalsSheetUi) -> Unit = { transform ->
                    onSheet { sheet -> if (isCurrentNow()) transform(sheet) else null }
                }
                if (isCurrentNow()) {
                    when (event) {
                        is StopArrivalsEvent.Loading -> publish { sheet ->
                            sheet.copy(
                                state = if (sheet.rows.isEmpty()) StopArrivalsSheetState.Loading else sheet.state,
                                isRefreshing = true,
                            )
                        }
                        is StopArrivalsEvent.Arrivals -> publish { sheet ->
                            val (updated, blocked) = presentation.apply(
                                sheet, event.result, request.cityId, current().locale, current().selection,
                            )
                            if (blocked) invalidate()
                            updated
                        }
                        is StopArrivalsEvent.Routes -> publish { sheet ->
                            presentation.updateCatalog(event.routes)
                            presentation.reproject(sheet, current().locale, current().selection) ?: sheet
                        }
                    }
                }
            },
        )
    }

    private fun cityRoutes(city: TransitCity) = repository.routes(city.id).filter { it.cityId == city.id }
}

internal data class StopArrivalsContext(
    val city: TransitCity?,
    val locale: TransitLocale,
    val selection: RouteSelectionProjection,
    val selectedStopId: com.denis.georgiatransit.shared.domain.model.StopId?,
    val visible: Boolean,
)
