package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.routes.CatalogState
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.EmptyState
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_empty
import georgiatransit.shared.generated.resources.routes_empty_search
import georgiatransit.shared.generated.resources.routes_error
import georgiatransit.shared.generated.resources.routes_loading
import georgiatransit.shared.generated.resources.routes_offline
import georgiatransit.shared.generated.resources.routes_offline_unavailable
import georgiatransit.shared.generated.resources.routes_retry
import georgiatransit.shared.generated.resources.routes_search_label
import georgiatransit.shared.generated.resources.routes_search_placeholder
import georgiatransit.shared.generated.resources.routes_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RouteCatalogSection(
    catalog: CatalogState,
    cityId: CityId?,
    searchQuery: String,
    routes: List<RouteItemUiModel>,
    visibleRoutes: List<RouteItemUiModel>,
    selectedIds: Set<RouteId>,
    isSelectionLimitReached: Boolean,
    onSearchChanged: (String) -> Unit,
    onRouteToggled: (RouteId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    when (catalog) {
        CatalogState.Loading -> LoadingState(
            message = stringResource(Res.string.routes_loading),
            modifier = modifier.testTag(AutomationId.RoutesLoading),
        )

        is CatalogState.Available -> RouteList(
            cityId = cityId,
            searchQuery = searchQuery,
            routes = routes,
            visibleRoutes = visibleRoutes,
            selectedIds = selectedIds,
            isSelectionLimitReached = isSelectionLimitReached,
            isStaleOffline = catalog.freshness == TransitFreshness.StaleOffline,
            onSearchChanged = onSearchChanged,
            onRouteToggled = onRouteToggled,
            modifier = modifier,
        )

        is CatalogState.Empty -> EmptyState(
            title = stringResource(Res.string.routes_empty),
            message = stringResource(Res.string.routes_offline)
                .takeIf { catalog.freshness == TransitFreshness.StaleOffline },
            modifier = modifier.testTag(AutomationId.RoutesEmpty),
        )

        is CatalogState.OfflineNoCache -> ErrorState(
            title = stringResource(Res.string.routes_offline_unavailable),
            retryLabel = stringResource(Res.string.routes_retry).takeIf { catalog.canRetry },
            onRetry = onRetry.takeIf { catalog.canRetry },
            retryModifier = Modifier.testTag(AutomationId.RoutesRetry),
            modifier = modifier.testTag(AutomationId.RoutesOffline),
        )

        CatalogState.Unavailable -> EmptyState(
            title = stringResource(Res.string.routes_unavailable),
            modifier = modifier.testTag(AutomationId.RoutesUnavailable),
        )

        is CatalogState.Error -> ErrorState(
            title = stringResource(Res.string.routes_error),
            retryLabel = stringResource(Res.string.routes_retry).takeIf { catalog.canRetry },
            onRetry = onRetry.takeIf { catalog.canRetry },
            retryModifier = Modifier.testTag(AutomationId.RoutesRetry),
            modifier = modifier.testTag(AutomationId.RoutesError),
        )
    }
}

@Composable
private fun RouteList(
    cityId: CityId?,
    searchQuery: String,
    routes: List<RouteItemUiModel>,
    visibleRoutes: List<RouteItemUiModel>,
    selectedIds: Set<RouteId>,
    isSelectionLimitReached: Boolean,
    isStaleOffline: Boolean,
    onSearchChanged: (String) -> Unit,
    onRouteToggled: (RouteId) -> Unit,
    modifier: Modifier,
) {
    // The city key resets the list only for another city; Navigation 3 keeps this saveable state
    // when the same route entry is recreated.
    val listState = rememberSaveable(cityId?.value, saver = LazyListState.Saver) { LazyListState() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
        if (isStaleOffline) {
            Text(
                text = stringResource(Res.string.routes_offline),
                modifier = Modifier.testTag(AutomationId.RoutesOffline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChanged,
            modifier = Modifier.fillMaxWidth().testTag(AutomationId.RoutesSearch),
            label = { Text(stringResource(Res.string.routes_search_label)) },
            placeholder = { Text(stringResource(Res.string.routes_search_placeholder)) },
            singleLine = true,
        )
        when {
            routes.isEmpty() -> EmptyState(
                title = stringResource(Res.string.routes_empty),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesEmpty),
            )

            visibleRoutes.isEmpty() -> EmptyState(
                title = stringResource(Res.string.routes_empty_search),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesEmpty),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesList),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                items(visibleRoutes, key = { it.id.value }) { route ->
                    RouteRow(
                        route = route,
                        selected = route.id in selectedIds,
                        selectionLimitReached = isSelectionLimitReached,
                        onClick = { onRouteToggled(route.id) },
                    )
                }
            }
        }
    }
}
