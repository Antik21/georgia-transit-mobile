package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.denis.georgiatransit.shared.presentation.map.VehicleLayerState
import com.denis.georgiatransit.shared.presentation.map.VehicleRouteAccessibilityUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_vehicle_route_summary
import georgiatransit.shared.generated.resources.map_vehicle_status_live
import georgiatransit.shared.generated.resources.map_vehicle_status_loading
import georgiatransit.shared.generated.resources.map_vehicle_status_mixed
import georgiatransit.shared.generated.resources.map_vehicle_status_retryable
import georgiatransit.shared.generated.resources.map_vehicle_status_stale
import georgiatransit.shared.generated.resources.map_vehicle_status_unavailable
import georgiatransit.shared.generated.resources.map_vehicles_summary
import org.jetbrains.compose.resources.stringResource

/**
 * Keeps a live, localized summary for assistive technology and UI automation without adding a
 * production-facing status row to the sheet.
 */
@Composable
internal fun VehicleAccessibility(
    layerState: VehicleLayerState,
    routes: List<VehicleRouteAccessibilityUi>,
    modifier: Modifier = Modifier,
) {
    if (layerState == VehicleLayerState.Hidden || routes.isEmpty()) return
    val status = vehicleStatusLabel(layerState)
    val routeCounts = routes.map { route ->
        stringResource(
            Res.string.map_vehicle_route_summary,
            route.routeLabel,
            route.vehicleCount,
            vehicleStatusLabel(route.layerState),
        )
    }.joinToString(separator = " · ")
    val summary = stringResource(Res.string.map_vehicles_summary, status, routeCounts)
    val summaryModifier = Modifier
        .fillMaxWidth()
        .testTag(AutomationId.MapVehicles)
        .semantics {
            contentDescription = summary
            liveRegion = LiveRegionMode.Polite
        }
    val hasLiveNonemptyRoute = routes.any { route ->
        route.layerState == VehicleLayerState.Live && route.vehicleCount > 0
    }
    if (hasLiveNonemptyRoute) {
        // Transparent text retains real semantics bounds without restoring the visual status row.
        Box(modifier = modifier.fillMaxWidth().testTag(AutomationId.MapVehiclesLiveNonempty)) {
            Text(
                text = summary,
                modifier = summaryModifier,
                color = Color.Transparent,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        Text(
            text = summary,
            modifier = modifier.then(summaryModifier),
            color = Color.Transparent,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun vehicleStatusLabel(layerState: VehicleLayerState): String = when (layerState) {
    VehicleLayerState.Hidden -> ""
    VehicleLayerState.Loading -> stringResource(Res.string.map_vehicle_status_loading)
    VehicleLayerState.Live -> stringResource(Res.string.map_vehicle_status_live)
    VehicleLayerState.Stale -> stringResource(Res.string.map_vehicle_status_stale)
    VehicleLayerState.Retryable -> stringResource(Res.string.map_vehicle_status_retryable)
    VehicleLayerState.Unavailable -> stringResource(Res.string.map_vehicle_status_unavailable)
    VehicleLayerState.Mixed -> stringResource(Res.string.map_vehicle_status_mixed)
}
