package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryLegendState
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryLegendUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_route_geometry_loading
import georgiatransit.shared.generated.resources.map_route_geometry_partial
import georgiatransit.shared.generated.resources.map_route_geometry_palette_overflow
import georgiatransit.shared.generated.resources.map_route_geometry_ready
import georgiatransit.shared.generated.resources.map_route_geometry_remove_accessibility
import georgiatransit.shared.generated.resources.map_route_geometry_retry_accessibility
import georgiatransit.shared.generated.resources.map_route_geometry_retryable
import georgiatransit.shared.generated.resources.map_route_geometry_title
import georgiatransit.shared.generated.resources.map_route_geometry_unavailable
import org.jetbrains.compose.resources.stringResource

/** Compact route chips for the actual ordered route-polyline source. */
@Composable
internal fun RouteGeometryLegend(
    routes: List<RouteGeometryLegendUi>,
    onFocus: (RouteId) -> Unit,
    onRetry: (RouteId) -> Unit,
    onRemove: (RouteId) -> Unit,
) {
    if (routes.isEmpty()) return
    Text(stringResource(Res.string.map_route_geometry_title), style = MaterialTheme.typography.labelLarge)
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapRouteGeometryLegend),
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        items(routes, key = { it.routeId.value }) { route ->
            val foreground = Color(route.textColorArgb)
            val background = Color(route.colorArgb)
            val removeDescription = stringResource(Res.string.map_route_geometry_remove_accessibility, route.routeLabel)
            val retryDescription = stringResource(Res.string.map_route_geometry_retry_accessibility, route.routeLabel)
            val status = routeGeometryStatusLabel(route)
            InputChip(
                selected = route.isFocused,
                onClick = { onFocus(route.routeId) },
                modifier = Modifier
                    .testTag(AutomationId.MapRouteGeometryChip)
                    .height(MaterialInputChipHeight)
                    .semantics {
                        contentDescription = "${route.routeLabel}. $status"
                        selected = route.isFocused
                        if (route.canRetry) {
                            customActions = listOf(
                                CustomAccessibilityAction(retryDescription) {
                                    onRetry(route.routeId)
                                    true
                                },
                            )
                        }
                    },
                label = {
                    Text(
                        text = route.routeLabel,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = DirectionsBusIcon,
                        contentDescription = null,
                        modifier = Modifier.size(MaterialInputChipLeadingIconSize),
                    )
                },
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .size(RouteGeometryActionSize)
                            .clip(CircleShape)
                            .testTag(AutomationId.MapRouteGeometryRemove)
                            .semantics(mergeDescendants = true) {
                                contentDescription = removeDescription
                            }
                            .clickable { onRemove(route.routeId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = CloseIcon,
                            contentDescription = null,
                            modifier = Modifier.size(MaterialInputChipCloseIconSize),
                        )
                    }
                },
                shape = RoundedCornerShape(MaterialInputChipCornerRadius),
                colors = InputChipDefaults.inputChipColors(
                    containerColor = background,
                    labelColor = foreground,
                    leadingIconColor = foreground,
                    trailingIconColor = foreground,
                    selectedContainerColor = background,
                    selectedLabelColor = foreground,
                    selectedLeadingIconColor = foreground,
                    selectedTrailingIconColor = foreground,
                ),
            )
            if (route.canRetry) {
                // A real target keeps both accessibility and automated recovery actionable.
                Box(
                    modifier = Modifier
                        .size(RouteGeometryActionSize)
                        .testTag(AutomationId.MapRouteGeometryRetry)
                        .semantics(mergeDescendants = true) { contentDescription = retryDescription }
                        .clickable { onRetry(route.routeId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↻",
                        color = foreground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun routeGeometryStatusLabel(route: RouteGeometryLegendUi): String = when (route.state) {
    RouteGeometryLegendState.Loading -> stringResource(
        Res.string.map_route_geometry_loading,
        route.successfulDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.Ready -> stringResource(
        Res.string.map_route_geometry_ready,
        route.successfulDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.Partial -> stringResource(
        Res.string.map_route_geometry_partial,
        route.successfulDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.Retryable -> stringResource(
        Res.string.map_route_geometry_retryable,
        route.failedDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.Unavailable -> stringResource(
        Res.string.map_route_geometry_unavailable,
        route.failedDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.PaletteOverflow -> stringResource(Res.string.map_route_geometry_palette_overflow)
}

private val RouteGeometryLegendUi.failedDirections: Int
    get() = (totalDirections - successfulDirections).coerceAtLeast(0)

/** Material Symbols "directions_bus" geometry, used under the Apache-2.0 license. */
internal val DirectionsBusIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DirectionsBus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            fill = SolidColor(Color.Black),
            pathData = PathParser().parsePathString(
                "M4 16c0 .88.39 1.67 1 2.22V20c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h8v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4S4 2.5 4 6v10zm3.5 1c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zM17 17c-.83 0-1.5-.67-1.5-1.5S16.17 14 17 14s1.5.67 1.5 1.5S17.83 17 17 17zM18 12H6V6h12v6z",
            ).toNodes(),
        )
    }.build()
}

/** Material Symbols "close" geometry, used under the Apache-2.0 license. */
private val CloseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Close",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            fill = SolidColor(Color.Black),
            pathData = PathParser().parsePathString(
                "M18.3 5.71 16.89 4.29 12 9.17 7.11 4.29 5.7 5.71 10.59 10.59 5.7 15.48 7.11 16.9 12 12 16.89 16.9 18.3 15.48 13.41 10.59z",
            ).toNodes(),
        )
    }.build()
}

// Material 2 input-chip measurements: 32dp container, 24dp leading icon, and 18dp close icon.
private val MaterialInputChipHeight = 32.dp
private val MaterialInputChipCornerRadius = 16.dp
private val MaterialInputChipLeadingIconSize = 24.dp
private val MaterialInputChipCloseIconSize = 18.dp
private val RouteGeometryActionSize = MaterialInputChipCloseIconSize
