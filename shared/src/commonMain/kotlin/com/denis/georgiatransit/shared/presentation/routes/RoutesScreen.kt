package com.denis.georgiatransit.shared.presentation.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.EmptyState
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_back_action
import georgiatransit.shared.generated.resources.routes_confirm_action
import georgiatransit.shared.generated.resources.routes_error
import georgiatransit.shared.generated.resources.routes_empty
import georgiatransit.shared.generated.resources.routes_loading
import georgiatransit.shared.generated.resources.routes_offline
import georgiatransit.shared.generated.resources.routes_retry
import georgiatransit.shared.generated.resources.routes_subtitle
import georgiatransit.shared.generated.resources.routes_title
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun RoutesScreen(viewModel: RoutesViewModel, handleNavigation: suspend (NavigationEffect) -> Unit) {
    val state by viewModel.collectAsState()
    viewModel.collectSideEffect { effect -> handleNavigation(effect as NavigationEffect) }
    Content(state = state, onAction = viewModel::dispatchAction)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .testTag(AutomationId.RoutesScreen)
            .systemBarsPadding()
            .padding(TransitSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
    ) {
        TextButton(
            onClick = { onAction(Action.BackClicked) },
            modifier = Modifier.testTag(AutomationId.RoutesBack),
        ) {
            Text(stringResource(Res.string.routes_back_action))
        }
        Text(stringResource(Res.string.routes_title), style = MaterialTheme.typography.headlineSmall)
        Text(state.cityName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.routes_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val catalog = state.catalog) {
            CatalogState.Loading -> LoadingState(
                message = stringResource(Res.string.routes_loading),
                modifier = Modifier.weight(1f).testTag(AutomationId.RoutesLoading),
            )
            is CatalogState.Available -> RouteList(
                state = state,
                isStaleOffline = catalog.freshness == com.denis.georgiatransit.shared.domain.repository.TransitFreshness.StaleOffline,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
            is CatalogState.Empty -> EmptyState(
                title = stringResource(Res.string.routes_empty),
                message = stringResource(Res.string.routes_offline)
                    .takeIf { catalog.freshness == com.denis.georgiatransit.shared.domain.repository.TransitFreshness.StaleOffline },
                modifier = Modifier.weight(1f),
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
            enabled = state.catalog is CatalogState.Available,
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
    Column(modifier = modifier) {
        if (isStaleOffline) {
            Text(
                text = stringResource(Res.string.routes_offline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.routes.isEmpty()) {
            EmptyState(title = stringResource(Res.string.routes_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                items(state.routes, key = { it.id.value }) { route ->
                    RouteRow(route, route.id in state.selectedIds) { onAction(Action.RouteToggled(route.id)) }
                }
            }
        }
    }
}

@Composable
private fun RouteRow(route: RouteItemUiModel, selected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(AutomationId.RoutesOption).clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
        ) {
            Box(Modifier.size(36.dp).background(Color(route.colorArgb), TransitShapes.Full), contentAlignment = Alignment.Center) {
                Text(route.shortName, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(route.shortName, style = MaterialTheme.typography.titleMedium)
                Text(route.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(selected, onCheckedChange = { onClick() })
        }
    }
}

@Preview
@Composable
private fun Preview() {
    val id = RouteId("preview:301")
    GeorgiaTransitTheme {
        Content(
            ViewState(
                "Tbilisi",
                listOf(RouteItemUiModel(id, "301", "Station Square — Varketili", 0xFF2A9D8F)),
                setOf(id),
                CatalogState.Available(com.denis.georgiatransit.shared.domain.repository.TransitFreshness.Network),
            ),
            onAction = {},
        )
    }
}
