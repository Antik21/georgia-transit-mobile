package com.denis.georgiatransit.shared.presentation.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.presentation.ui.contrastSafeRouteTextColor
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.EmptyState
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_cancel_action
import georgiatransit.shared.generated.resources.routes_confirm_action
import georgiatransit.shared.generated.resources.routes_direction
import georgiatransit.shared.generated.resources.routes_empty
import georgiatransit.shared.generated.resources.routes_empty_search
import georgiatransit.shared.generated.resources.routes_error
import georgiatransit.shared.generated.resources.routes_loading
import georgiatransit.shared.generated.resources.routes_mode_bus
import georgiatransit.shared.generated.resources.routes_mode_ferry
import georgiatransit.shared.generated.resources.routes_mode_metro
import georgiatransit.shared.generated.resources.routes_mode_tram
import georgiatransit.shared.generated.resources.routes_offline
import georgiatransit.shared.generated.resources.routes_offline_unavailable
import georgiatransit.shared.generated.resources.routes_retry
import georgiatransit.shared.generated.resources.routes_row_description
import georgiatransit.shared.generated.resources.routes_row_description_with_direction
import georgiatransit.shared.generated.resources.routes_row_limit_reached
import georgiatransit.shared.generated.resources.routes_row_selected
import georgiatransit.shared.generated.resources.routes_row_unselected
import georgiatransit.shared.generated.resources.routes_search_label
import georgiatransit.shared.generated.resources.routes_search_placeholder
import georgiatransit.shared.generated.resources.routes_subtitle
import georgiatransit.shared.generated.resources.routes_selection_count
import georgiatransit.shared.generated.resources.routes_selection_limit_warning
import georgiatransit.shared.generated.resources.routes_title
import georgiatransit.shared.generated.resources.routes_unavailable
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
        TextButton(
            onClick = { onAction(Action.CancelClicked) },
            modifier = Modifier.testTag(AutomationId.RoutesCancel),
        ) {
            Text(stringResource(Res.string.routes_cancel_action))
        }
        Text(stringResource(Res.string.routes_title), style = MaterialTheme.typography.headlineSmall)
        Text(state.cityName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.routes_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(
                Res.string.routes_selection_count,
                state.selectedIds.size,
                RouteSelectionPolicy.MaximumSelectedRoutes,
            ),
            modifier = Modifier.testTag(AutomationId.RoutesSelectionCount),
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.isSelectionLimitReached) {
            Card(
                modifier = Modifier.fillMaxWidth().testTag(AutomationId.RoutesSelectionWarning),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    text = stringResource(
                        Res.string.routes_selection_limit_warning,
                        RouteSelectionPolicy.MaximumSelectedRoutes,
                    ),
                    modifier = Modifier.padding(TransitSpacing.Medium),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        when (val catalog = state.catalog) {
            CatalogState.Loading -> LoadingState(
                message = stringResource(Res.string.routes_loading),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesLoading),
            )

            is CatalogState.Available -> RouteList(
                state = state,
                isStaleOffline = catalog.freshness ==
                    com.denis.georgiatransit.shared.domain.repository.TransitFreshness.StaleOffline,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )

            is CatalogState.Empty -> EmptyState(
                title = stringResource(Res.string.routes_empty),
                message = stringResource(Res.string.routes_offline)
                    .takeIf { catalog.freshness == com.denis.georgiatransit.shared.domain.repository.TransitFreshness.StaleOffline },
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesEmpty),
            )

            is CatalogState.OfflineNoCache -> ErrorState(
                title = stringResource(Res.string.routes_offline_unavailable),
                retryLabel = stringResource(Res.string.routes_retry).takeIf { catalog.canRetry },
                onRetry = { onAction(Action.RetryClicked) }.takeIf { catalog.canRetry },
                retryModifier = Modifier.testTag(AutomationId.RoutesRetry),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesOffline),
            )

            CatalogState.Unavailable -> EmptyState(
                title = stringResource(Res.string.routes_unavailable),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesUnavailable),
            )

            is CatalogState.Error -> ErrorState(
                title = stringResource(Res.string.routes_error),
                retryLabel = stringResource(Res.string.routes_retry).takeIf { catalog.canRetry },
                onRetry = { onAction(Action.RetryClicked) }.takeIf { catalog.canRetry },
                retryModifier = Modifier.testTag(AutomationId.RoutesRetry),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesError),
            )
        }

        Button(
            onClick = { onAction(Action.ConfirmClicked) },
            enabled = state.canConfirm,
            modifier = Modifier.fillMaxWidth().testTag(AutomationId.RoutesConfirm),
        ) { Text(stringResource(Res.string.routes_confirm_action)) }
    }
}

@Composable
private fun RouteList(
    state: ViewState,
    isStaleOffline: Boolean,
    onAction: (Action) -> Unit,
    modifier: Modifier,
) {
    // The city key resets the list only for another city; Navigation 3 keeps this saveable state
    // when the same route entry is recreated.
    val listState = rememberSaveable(state.cityId?.value, saver = LazyListState.Saver) { LazyListState() }
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
            value = state.searchQuery,
            onValueChange = { onAction(Action.SearchChanged(it)) },
            modifier = Modifier.fillMaxWidth().testTag(AutomationId.RoutesSearch),
            label = { Text(stringResource(Res.string.routes_search_label)) },
            placeholder = { Text(stringResource(Res.string.routes_search_placeholder)) },
            singleLine = true,
        )
        when {
            state.routes.isEmpty() -> EmptyState(
                title = stringResource(Res.string.routes_empty),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesEmpty),
            )

            state.visibleRoutes.isEmpty() -> EmptyState(
                title = stringResource(Res.string.routes_empty_search),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesEmpty),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesList),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                items(state.visibleRoutes, key = { it.id.value }) { route ->
                    RouteRow(
                        route = route,
                        selected = route.id in state.selectedIds,
                        selectionLimitReached = state.isSelectionLimitReached,
                        onClick = { onAction(Action.RouteToggled(route.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteRow(
    route: RouteItemUiModel,
    selected: Boolean,
    selectionLimitReached: Boolean,
    onClick: () -> Unit,
) {
    val isToggleEnabled = selected || !selectionLimitReached
    val mode = route.mode.displayName()
    val selectionState = stringResource(
        if (selected) Res.string.routes_row_selected else Res.string.routes_row_unselected,
    )
    val description = route.direction.takeIf(String::isNotBlank)?.let { direction ->
        stringResource(
            Res.string.routes_row_description_with_direction,
            route.shortName,
            route.name,
            direction,
            mode,
        )
    } ?: stringResource(Res.string.routes_row_description, route.shortName, route.name, mode)
    val accessibilityDescription = if (isToggleEnabled) {
        description
    } else {
        "$description. ${stringResource(Res.string.routes_row_limit_reached)}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AutomationId.RoutesOption)
                .selectable(
                    selected = selected,
                    enabled = isToggleEnabled,
                    role = Role.Checkbox,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityDescription
                    stateDescription = selectionState
                }
                .padding(TransitSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
        ) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color(route.colorArgb), TransitShapes.Full),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    route.shortName,
                    color = Color(contrastSafeRouteTextColor(route.colorArgb, route.textColorArgb)),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(route.shortName, style = MaterialTheme.typography.titleMedium)
                Text(route.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                route.direction.takeIf(String::isNotBlank)?.let { direction ->
                    Text(
                        stringResource(Res.string.routes_direction, direction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(mode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The row owns the one checkable semantics target; this is visual state only.
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = isToggleEnabled,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun TransitMode.displayName(): String = stringResource(
    when (this) {
        TransitMode.Bus -> Res.string.routes_mode_bus
        TransitMode.Metro -> Res.string.routes_mode_metro
        TransitMode.Tram -> Res.string.routes_mode_tram
        TransitMode.Ferry -> Res.string.routes_mode_ferry
    },
)

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
                catalog = CatalogState.Available(
                    com.denis.georgiatransit.shared.domain.repository.TransitFreshness.Network,
                ),
            ),
            onAction = {},
        )
    }
}
