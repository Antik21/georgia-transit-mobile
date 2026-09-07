package com.denis.georgiatransit.shared.presentation.routes

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness

@Immutable
data class ViewState(
    val cityName: String = "",
    val routes: List<RouteItemUiModel> = emptyList(),
    val selectedIds: Set<RouteId> = emptySet(),
    val catalog: CatalogState = CatalogState.Loading,
)

/** Transport freshness stays explicit rather than being inferred from a non-empty route list. */
@Immutable
sealed interface CatalogState {
    data object Loading : CatalogState
    data class Available(val freshness: TransitFreshness) : CatalogState
    data class Empty(val freshness: TransitFreshness) : CatalogState
    data class Error(val failure: TransitFailure, val canRetry: Boolean) : CatalogState
}

@Immutable
data class RouteItemUiModel(
    val id: RouteId,
    val shortName: String,
    val name: String,
    val colorArgb: Long,
)

sealed interface Action {
    data class RouteToggled(val routeId: RouteId) : Action
    data object RetryClicked : Action
    data object BackClicked : Action
    data object ConfirmClicked : Action
}

sealed interface SideEffect

sealed interface NavigationEffect : SideEffect {
    data object BackToMap : NavigationEffect
}
