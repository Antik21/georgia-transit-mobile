package com.denis.georgiatransit.shared.presentation.routes

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness

@Immutable
data class ViewState(
    val cityName: String = "",
    val routes: List<RouteItemUiModel> = emptyList(),
    val selectedIds: Set<RouteId> = emptySet(),
    val catalog: CatalogState = CatalogState.Loading,
    val cityId: CityId? = null,
    val visibleRoutes: List<RouteItemUiModel> = routes,
    val searchQuery: String = "",
    val isConfirming: Boolean = false,
) {
    val isSelectionLimitReached: Boolean
        get() = selectedIds.size >= RouteSelectionPolicy.MaximumSelectedRoutes

    val canConfirm: Boolean
        get() = !isConfirming && cityId != null && catalog is CatalogState.Available
}

/** Transport freshness stays explicit rather than being inferred from a non-empty route list. */
@Immutable
sealed interface CatalogState {
    data object Loading : CatalogState
    data class Available(val freshness: TransitFreshness) : CatalogState
    data class Empty(val freshness: TransitFreshness) : CatalogState
    /** There is no last-known-good catalog to show while the device is offline. */
    data class OfflineNoCache(val failure: TransitFailure, val canRetry: Boolean) : CatalogState
    /** A missing selection or disabled city capability must never trigger the route endpoint. */
    data object Unavailable : CatalogState
    data class Error(val failure: TransitFailure, val canRetry: Boolean) : CatalogState
}

@Immutable
data class RouteItemUiModel(
    val id: RouteId,
    val shortName: String,
    val name: String,
    val colorArgb: Long,
    /** A localized public headsign; provider identifiers never cross into the presentation model. */
    val direction: String = "",
    val textColorArgb: Long = 0xFFFFFFFF,
    val mode: TransitMode = TransitMode.Bus,
)

sealed interface Action {
    data class RouteToggled(val routeId: RouteId) : Action
    data class SearchChanged(val query: String) : Action
    data class LocaleChanged(val languageTag: String) : Action
    data object RetryClicked : Action
    /** Cancels the entry-local draft without changing [TransitSession]'s committed selection. */
    data object CancelClicked : Action
    data object ConfirmClicked : Action
}

sealed interface SideEffect

sealed interface NavigationEffect : SideEffect {
    /** Emitted only after the full replacement selection is committed to [TransitSession]. */
    data object Confirmed : NavigationEffect

    /** Explicitly discards the entry-local draft. */
    data object Dismissed : NavigationEffect
}
