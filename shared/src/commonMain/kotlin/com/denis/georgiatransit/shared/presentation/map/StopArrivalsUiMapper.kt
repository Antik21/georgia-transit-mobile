package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjector

/** Pure, immutable projection of normalized arrival pages and route styles to sheet UI. */
internal class StopArrivalsUiMapper(
    private val stop: TransitStop?,
    private val locale: TransitLocale,
    private val catalogRoutes: List<TransitRoute>,
    private val selection: RouteSelectionProjection,
    private val page: ArrivalPage?,
    private val freshness: TransitFreshness?,
) {
    fun withCurrentArrivalPage(sheet: StopArrivalsSheetUi): StopArrivalsSheetUi = with(sheet) {
        val page = page ?: return@with copy(
            passingRoutes = routeDetailsFor(stop?.routeIds.orEmpty()).badges,
            passingRouteShortNames = routeDetailsFor(stop?.routeIds.orEmpty()).shortNames,
            hasUnavailableRouteDetails = routeDetailsFor(stop?.routeIds.orEmpty()).hasUnavailableDetails,
        )
        val freshness = freshness ?: TransitFreshness.Network
        val updated = withArrivalPage(sheet, page, freshness)
        // Resolving a catalogue label does not erase a separately observed arrivals failure.
        return@with if (state is StopArrivalsSheetState.Offline || state is StopArrivalsSheetState.UpstreamError ||
            state is StopArrivalsSheetState.Unavailable
        ) {
            updated.copy(state = state, isStale = isStale, isRefreshing = isRefreshing)
        } else {
            updated.copy(isRefreshing = isRefreshing)
        }
    }

    fun withArrivalPage(
        sheet: StopArrivalsSheetUi,
        page: ArrivalPage,
        freshness: TransitFreshness,
    ): StopArrivalsSheetUi = with(sheet) {
        val stop = stop ?: return@with sheet
        val routeDetails = routeDetailsFor(stop.routeIds)
        val pageDetails = page.toUiDetails(stop.id)
        val completeDetails = pageDetails.copy(
            hasUnavailableRouteDetails = pageDetails.hasUnavailableRouteDetails || routeDetails.hasUnavailableDetails,
        )
        return@with copy(
            passingRoutes = routeDetails.badges,
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
                val routeBadge = routeDetailsFor(listOf(arrival.routeId)).badges.singleOrNull()
                val routeShortName = routeBadge?.routeLabel
                val headsign = arrival.headsign.forLocale(locale).trim()
                if (routeShortName == null || headsign.isBlank()) rejectedRows = true
                IndexedArrivalRow(
                    index = index,
                    row = StopArrivalRowUi(
                        routeShortName = routeShortName,
                        headsign = headsign,
                        time = arrival.expectedInMinutes.toUiTime(),
                        source = arrival.source.toUiSource(),
                        routeId = arrival.routeId,
                        routeBadge = routeBadge,
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

    fun routeDetailsFor(routeIds: List<RouteId>): RouteDetails {
        val requestedIds = routeIds.toSet()
        val projectedPassingRoutes = RouteSelectionProjector.resolve(catalogRoutes, requestedIds)
        val selectedStyles = selection.byId
        val badges = projectedPassingRoutes.routes.map { passingRoute ->
            val selected = selectedStyles[passingRoute.routeId]
            val styledRoute = selected ?: passingRoute
            StopRouteBadgeUi(
                routeId = passingRoute.routeId,
                routeLabel = styledRoute.displayLabel,
                backgroundArgb = styledRoute.backgroundArgb,
                textArgb = styledRoute.textArgb,
                colorAvailability = styledRoute.colorAvailability,
                isSelected = selected != null,
            )
        }
        val knownIds = catalogRoutes.mapTo(mutableSetOf(), TransitRoute::id)
        return RouteDetails(
            badges = badges,
            shortNames = badges.map(StopRouteBadgeUi::routeLabel),
            hasUnavailableDetails = routeIds.any { it !in knownIds } || badges.size != requestedIds.size,
        )
    }

    companion object {
        fun failureState(failure: TransitFailure): StopArrivalsSheetState = when (failure) {
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

    }
}

internal data class RouteDetails(
    val badges: List<StopRouteBadgeUi>,
    val shortNames: List<String>,
    val hasUnavailableDetails: Boolean,
)

internal data class IndexedArrivalRow(
    val index: Int,
    val row: StopArrivalRowUi,
    val hasUnavailableRouteDetails: Boolean,
)

internal data class ArrivalUiDetails(
    val rows: List<StopArrivalRowUi>,
    val hasUnavailableRouteDetails: Boolean,
    val hasRejectedRows: Boolean,
)
