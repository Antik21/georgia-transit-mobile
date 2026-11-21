package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.routes.CatalogState
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.EmptyState
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.route_clear
import georgiatransit.shared.generated.resources.route_search
import georgiatransit.shared.generated.resources.routes_clear_search
import georgiatransit.shared.generated.resources.routes_empty
import georgiatransit.shared.generated.resources.routes_empty_search
import georgiatransit.shared.generated.resources.routes_error
import georgiatransit.shared.generated.resources.routes_loading
import georgiatransit.shared.generated.resources.routes_offline
import georgiatransit.shared.generated.resources.routes_retry
import georgiatransit.shared.generated.resources.routes_search_label
import georgiatransit.shared.generated.resources.routes_search_placeholder
import georgiatransit.shared.generated.resources.routes_unavailable
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RouteCatalogSection(
    catalog: CatalogState,
    cityId: CityId?,
    searchQuery: String,
    routes: List<RouteItemUiModel>,
    visibleRoutes: List<RouteItemUiModel>,
    recentRoutes: List<RouteItemUiModel>,
    selectedIds: Set<RouteId>,
    isSelectionLimitReached: Boolean,
    onSearchChanged: (String) -> Unit,
    onRouteToggled: (RouteId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
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
            recentRoutes = recentRoutes,
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
            title = stringResource(Res.string.routes_error),
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
    recentRoutes: List<RouteItemUiModel>,
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
    val focusManager = LocalFocusManager.current
    var selectedSheetOpen by rememberSaveable(cityId?.value) { mutableStateOf(false) }
    val canShowSelected = isSelectionLimitReached && searchQuery.isNotBlank() && visibleRoutes.none { it.id in selectedIds }
    val showSelected: (() -> Unit)? = if (canShowSelected) {
        {
            focusManager.clearFocus()
            selectedSheetOpen = true
        }
    } else null
    BoxWithConstraints(modifier = modifier) {
        val scrollSelectionLimitNotice = maxHeight < MinimumHeightForPinnedSelectionLimit
        val scrollSelectedAction = scrollSelectionLimitNotice && canShowSelected
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
            if (isStaleOffline) {
                Text(
                    text = stringResource(Res.string.routes_offline),
                    modifier = Modifier.testTag(AutomationId.RoutesOffline),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            RouteSearchField(searchQuery, onSearchChanged)
            if (isSelectionLimitReached && !scrollSelectionLimitNotice) {
                RoutesSelectionLimitNotice(onShowSelected = showSelected)
            }
            when {
                routes.isEmpty() -> EmptyState(
                    title = stringResource(Res.string.routes_empty),
                    modifier = Modifier.weight(1f).testTag(AutomationId.RoutesEmpty),
                )

                visibleRoutes.isEmpty() && !scrollSelectedAction -> EmptyState(
                    title = stringResource(Res.string.routes_empty_search),
                    modifier = Modifier.weight(1f).testTag(AutomationId.RoutesEmpty),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).testTag(AutomationId.RoutesList),
                    verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                ) {
                    if (scrollSelectedAction) {
                        item(key = RouteListSection.SelectionLimit) {
                            RoutesSelectionLimitNotice(onShowSelected = showSelected)
                        }
                    }
                    if (visibleRoutes.isEmpty()) {
                        item(key = RouteListSection.EmptySearch) {
                            EmptyState(
                                title = stringResource(Res.string.routes_empty_search),
                                modifier = Modifier.testTag(AutomationId.RoutesEmpty),
                                compact = true,
                            )
                        }
                    }
                    if (searchQuery.isBlank() && recentRoutes.isNotEmpty()) {
                        item(key = RouteListSection.Recent) {
                            RecentRoutesSection(recentRoutes, selectedIds, isSelectionLimitReached, onRouteToggled)
                        }
                    }
                    itemsIndexed(visibleRoutes, key = { _, route -> route.id.value }) { index, route ->
                        RouteRow(
                            route = route,
                            selected = route.id in selectedIds,
                            selectionLimitReached = isSelectionLimitReached,
                            onClick = { onRouteToggled(route.id) },
                        )
                        if (index == 0 && isSelectionLimitReached && scrollSelectionLimitNotice && !scrollSelectedAction) {
                            RoutesSelectionLimitNotice(modifier = Modifier.padding(top = TransitSpacing.Small))
                        }
                    }
                }
            }
        }
    }
    if (selectedSheetOpen) {
        SelectedRoutesSheet(
            routes = routes.filter { it.id in selectedIds },
            onDismiss = { selectedSheetOpen = false },
            onRemove = { routeId ->
                if (selectedSheetOpen) {
                    selectedSheetOpen = false
                    onRouteToggled(routeId)
                }
            },
        )
    }
}

private enum class RouteListSection { Recent, SelectionLimit, EmptySearch }

private val MinimumHeightForPinnedSelectionLimit = 200.dp

@Composable
private fun RouteSearchField(query: String, onQueryChanged: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    val label = stringResource(Res.string.routes_search_label)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.RoutesSearch)
            .semantics { contentDescription = label },
        placeholder = { Text(stringResource(Res.string.routes_search_placeholder)) },
        leadingIcon = { Icon(painterResource(Res.drawable.route_search), contentDescription = null) },
        shape = TransitShapes.Large,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(painterResource(Res.drawable.route_clear), stringResource(Res.string.routes_clear_search))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        singleLine = true,
    )
}
