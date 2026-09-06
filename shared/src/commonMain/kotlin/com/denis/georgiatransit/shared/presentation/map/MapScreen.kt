package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitColors
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_change_city_action
import georgiatransit.shared.generated.resources.map_nearby_stops
import georgiatransit.shared.generated.resources.map_preview_note
import georgiatransit.shared.generated.resources.map_routes_action
import georgiatransit.shared.generated.resources.map_selected_routes
import georgiatransit.shared.generated.resources.map_stop_one
import georgiatransit.shared.generated.resources.map_stop_two
import georgiatransit.shared.generated.resources.map_title
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun MapScreen(viewModel: MapViewModel, handleNavigation: suspend (NavigationEffect) -> Unit) {
    val state by viewModel.collectAsState()
    viewModel.collectSideEffect { effect -> handleNavigation(effect as NavigationEffect) }
    Content(state = state, onAction = viewModel::dispatchAction)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().testTag(AutomationId.MapScreen)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Text(stringResource(Res.string.map_title), style = MaterialTheme.typography.headlineSmall)
            Text(state.cityName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
        state.viewport?.let { viewport ->
            MapPreview(viewport = viewport, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Text(stringResource(Res.string.map_nearby_stops), style = MaterialTheme.typography.titleMedium)
            StopCard(stringResource(Res.string.map_stop_one), "301 · 337")
            StopCard(stringResource(Res.string.map_stop_two), "301 · 395")
            if (state.selectedRouteNames.isNotEmpty()) {
                Text(
                    stringResource(Res.string.map_selected_routes, state.selectedRouteNames.joinToString()),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                OutlinedButton(
                    onClick = { onAction(Action.ChangeCityClicked) },
                    modifier = Modifier.weight(1f).testTag(AutomationId.MapChangeCity),
                ) { Text(stringResource(Res.string.map_change_city_action)) }
                Button(
                    onClick = { onAction(Action.RoutesClicked) },
                    modifier = Modifier.weight(1f).testTag(AutomationId.MapRoutes),
                ) { Text(stringResource(Res.string.map_routes_action)) }
            }
        }
    }
}

@Composable
private fun MapPreview(viewport: MapViewport, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(TransitColors.MapLand), contentAlignment = Alignment.Center) {
        PlatformMap(viewport = viewport, modifier = Modifier.fillMaxSize())
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), shape = RoundedCornerShape(TransitSpacing.Small)) {
            Text(stringResource(Res.string.map_preview_note), modifier = Modifier.padding(TransitSpacing.Small), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StopCard(title: String, routes: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.height(12.dp).fillMaxWidth(.03f).background(TransitColors.Brand, CircleShape))
            Column { Text(title); Text(routes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    GeorgiaTransitTheme {
        Content(
            ViewState(
                cityName = "Tbilisi",
                viewport = MapViewport(com.denis.georgiatransit.shared.domain.model.GeoPoint(41.7151, 44.8271)),
                selectedRouteNames = listOf("301", "337"),
            ),
            onAction = {},
        )
    }
}
