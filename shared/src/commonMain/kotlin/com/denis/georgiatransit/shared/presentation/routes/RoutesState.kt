package com.denis.georgiatransit.shared.presentation.routes

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.RouteId

@Immutable
data class ViewState(
    val cityName: String = "",
    val routes: List<RouteItemUiModel> = emptyList(),
    val selectedIds: Set<RouteId> = emptySet(),
)

@Immutable
data class RouteItemUiModel(
    val id: RouteId,
    val shortName: String,
    val name: String,
    val colorArgb: Long,
)

sealed interface Action {
    data class RouteToggled(val routeId: RouteId) : Action
    data object ConfirmClicked : Action
}

sealed interface SideEffect

sealed interface NavigationEffect : SideEffect {
    data object BackToMap : NavigationEffect
}

