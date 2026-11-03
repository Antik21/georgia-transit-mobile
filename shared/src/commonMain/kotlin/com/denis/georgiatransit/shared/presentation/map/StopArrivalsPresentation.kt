package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection

/** Entry-local source snapshot for the open arrivals sheet, separate from its polling jobs. */
internal class StopArrivalsPresentation {
    var stop: TransitStop? = null
        private set
    private var catalogRoutes: List<TransitRoute> = emptyList()
    private var page: ArrivalPage? = null
    private var freshness: TransitFreshness? = null
    private var blockedCityId: CityId? = null

    fun reset() {
        stop = null
        catalogRoutes = emptyList()
        clearPage()
    }

    fun open(selectedStop: TransitStop, routes: List<TransitRoute>) {
        reset()
        stop = selectedStop
        catalogRoutes = routes
    }

    fun updateCatalog(routes: List<TransitRoute>) {
        catalogRoutes = routes
    }

    fun clearPage() {
        page = null
        freshness = null
    }

    fun unblock() {
        blockedCityId = null
    }

    fun isBlocked(cityId: CityId): Boolean = blockedCityId == cityId

    private fun mapper(locale: TransitLocale, selection: RouteSelectionProjection) = StopArrivalsUiMapper(
        stop = stop,
        locale = locale,
        catalogRoutes = catalogRoutes,
        selection = selection,
        page = page,
        freshness = freshness,
    )

    fun routeDetails(routeIds: List<RouteId>, locale: TransitLocale, selection: RouteSelectionProjection): RouteDetails =
        mapper(locale, selection).routeDetailsFor(routeIds)

    fun initialSheet(city: TransitCity?, locale: TransitLocale, selection: RouteSelectionProjection): StopArrivalsSheetUi? {
        val selectedStop = stop ?: return null
        val details = routeDetails(selectedStop.routeIds, locale, selection)
        return StopArrivalsSheetUi(
            stopId = selectedStop.id,
            stopName = selectedStop.name.forLocale(locale).trim(),
            stopCode = selectedStop.code.trim().ifBlank { null },
            passingRoutes = details.badges,
            passingRouteShortNames = details.shortNames,
            hasUnavailableRouteDetails = details.hasUnavailableDetails,
            state = if (city?.capabilities?.arrivals == true && !isBlocked(city.id)) {
                StopArrivalsSheetState.Loading
            } else {
                StopArrivalsSheetState.Unavailable
            },
        )
    }

    fun reproject(sheet: StopArrivalsSheetUi?, locale: TransitLocale, selection: RouteSelectionProjection): StopArrivalsSheetUi? =
        sheet?.let { mapper(locale, selection).withCurrentArrivalPage(it) }

    /** Returns the new sheet and whether the BFF kill switch closed this city. */
    fun apply(
        sheet: StopArrivalsSheetUi,
        result: TransitLoadResult<ArrivalPage>,
        cityId: CityId,
        locale: TransitLocale,
        selection: RouteSelectionProjection,
    ): Pair<StopArrivalsSheetUi, Boolean> = when (result) {
        is TransitLoadResult.Data -> {
            page = result.value
            freshness = result.freshness
            mapper(locale, selection).withArrivalPage(sheet, result.value, result.freshness) to false
        }
        is TransitLoadResult.Empty -> {
            page = null
            freshness = result.freshness
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
            ) to false
        }
        is TransitLoadResult.Failure -> if (result.error is TransitFailure.CapabilityUnavailable) {
            blockedCityId = cityId
            clearPage()
            sheet.copy(
                rows = emptyList(),
                state = StopArrivalsSheetState.Unavailable,
                pageSource = null,
                isStale = false,
                isRefreshing = false,
            ) to true
        } else {
            sheet.copy(
                state = StopArrivalsUiMapper.failureState(result.error),
                isStale = sheet.isStale || sheet.rows.isNotEmpty(),
                isRefreshing = false,
            ) to false
        }
    }

    fun restartForLocale(
        current: StopArrivalsSheetUi?,
        city: TransitCity,
        locale: TransitLocale,
        selection: RouteSelectionProjection,
        routes: List<TransitRoute>,
    ): StopArrivalsSheetUi? {
        val selectedStop = stop ?: return null
        catalogRoutes = routes
        val replacement = current ?: initialSheet(city, locale, selection) ?: return null
        val details = routeDetails(selectedStop.routeIds, locale, selection)
        val localized = replacement.copy(
            stopName = selectedStop.name.forLocale(locale).trim(),
            stopCode = selectedStop.code.trim().ifBlank { null },
            passingRoutes = details.badges,
            passingRouteShortNames = details.shortNames,
            hasUnavailableRouteDetails = details.hasUnavailableDetails,
        )
        if (!city.capabilities.arrivals || isBlocked(city.id)) {
            clearPage()
            return localized.copy(
                rows = emptyList(),
                state = StopArrivalsSheetState.Unavailable,
                pageSource = null,
                isStale = false,
                isRefreshing = false,
            )
        }
        val currentPage = page ?: return localized.copy(
            state = StopArrivalsSheetState.Loading,
            isRefreshing = false,
        )
        return mapper(locale, selection).withArrivalPage(
            localized, currentPage, freshness ?: TransitFreshness.Network,
        )
    }
}
