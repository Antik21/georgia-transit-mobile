package com.denis.georgiatransit.shared.presentation.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.routes.sections.RouteCatalogSection
import com.denis.georgiatransit.shared.presentation.routes.sections.RoutesHeaderSection
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_confirm_action
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun RoutesScreen(viewModel: RoutesViewModel, handleNavigation: suspend (NavigationEffect) -> Unit) {
    val state by viewModel.collectAsState()
    val language = Locale.current.language
    LaunchedEffect(language) {
        viewModel.dispatchAction(Action.LocaleChanged(language))
    }
    viewModel.collectSideEffect { effect -> handleNavigation(effect as NavigationEffect) }
    Content(state = state, onAction = viewModel::dispatchAction)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(AutomationId.RoutesScreen)
            .safeDrawingPadding()
            .padding(TransitSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
    ) {
        RoutesHeaderSection(
            cityName = state.cityName,
            selectedCount = state.selectedIds.size,
            isSelectionLimitReached = state.isSelectionLimitReached,
            onCancel = { onAction(Action.CancelClicked) },
        )
        RouteCatalogSection(
            catalog = state.catalog,
            cityId = state.cityId,
            searchQuery = state.searchQuery,
            routes = state.routes,
            visibleRoutes = state.visibleRoutes,
            selectedIds = state.selectedIds,
            isSelectionLimitReached = state.isSelectionLimitReached,
            onSearchChanged = { onAction(Action.SearchChanged(it)) },
            onRouteToggled = { onAction(Action.RouteToggled(it)) },
            onRetry = { onAction(Action.RetryClicked) },
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onAction(Action.ConfirmClicked) },
            enabled = state.canConfirm,
            modifier = Modifier.fillMaxWidth().testTag(AutomationId.RoutesConfirm),
        ) { Text(stringResource(Res.string.routes_confirm_action)) }
    }
}

@Preview
@Composable
private fun Preview() {
    val id = RouteId("preview:301")
    GeorgiaTransitTheme {
        Content(
            ViewState(
                cityName = "Tbilisi",
                routes = listOf(
                    RouteItemUiModel(
                        id = id,
                        shortName = "301",
                        name = "Station Square — Varketili",
                        colorArgb = 0xFF2A9D8F,
                        textColorArgb = 0xFFFFFFFF,
                        mode = TransitMode.Bus,
                    ),
                ),
                visibleRoutes = listOf(
                    RouteItemUiModel(
                        id = id,
                        shortName = "301",
                        name = "Station Square — Varketili",
                        colorArgb = 0xFF2A9D8F,
                        textColorArgb = 0xFFFFFFFF,
                        mode = TransitMode.Bus,
                    ),
                ),
                selectedIds = setOf(id),
                catalog = CatalogState.Available(TransitFreshness.Network),
            ),
            onAction = {},
        )
    }
}
