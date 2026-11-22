package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryLegendUi
import com.denis.georgiatransit.shared.presentation.map.VehicleLayerState
import com.denis.georgiatransit.shared.presentation.map.VehicleRouteAccessibilityUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_routes_action
import georgiatransit.shared.generated.resources.map_settings
import georgiatransit.shared.generated.resources.map_settings_gear
import georgiatransit.shared.generated.resources.schedule_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapControls(
    vehicleLayerState: VehicleLayerState,
    vehicleRoutes: List<VehicleRouteAccessibilityUi>,
    routeGeometryLegends: List<RouteGeometryLegendUi>,
    settingsAvailable: Boolean,
    routesAvailable: Boolean,
    schedulesAvailable: Boolean,
    onFocusRoute: (RouteId) -> Unit,
    onRetryRoute: (RouteId) -> Unit,
    onRemoveRoute: (RouteId) -> Unit,
    onSettings: () -> Unit,
    onRoutes: () -> Unit,
    onSchedules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showStaleVehicleNotice = vehicleRoutes.any { it.layerState == VehicleLayerState.Stale } &&
        vehicleRoutes.all { it.vehicleCount == 0 }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box {
            if (!showStaleVehicleNotice) {
                VehicleAccessibility(layerState = vehicleLayerState, routes = vehicleRoutes)
            }
            Column(verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                RouteGeometryLegend(routes = routeGeometryLegends, onFocus = onFocusRoute, onRetry = onRetryRoute, onRemove = onRemoveRoute)
                if (showStaleVehicleNotice) {
                    VehicleAccessibility(layerState = vehicleLayerState, routes = vehicleRoutes, showStaleNotice = true)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (settingsAvailable) {
                        OutlinedIconButton(onClick = onSettings, modifier = Modifier.testTag(AutomationId.MapSettings)) {
                            Icon(
                                painter = painterResource(Res.drawable.map_settings_gear),
                                contentDescription = stringResource(Res.string.map_settings),
                            )
                        }
                    }
                    if (routesAvailable) {
                        Button(
                            onClick = onRoutes,
                            modifier = Modifier.weight(1f).testTag(AutomationId.MapRoutes),
                        ) { Text(stringResource(Res.string.map_routes_action)) }
                    }
                }
                if (schedulesAvailable) {
                    OutlinedButton(
                        onClick = onSchedules,
                        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapSchedules),
                    ) { Text(stringResource(Res.string.schedule_title)) }
                }
            }
        }
    }
}
