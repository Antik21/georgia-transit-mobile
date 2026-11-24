package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryLegendState
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryLegendUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.CloseIcon
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_route_geometry_chip_description
import georgiatransit.shared.generated.resources.map_route_geometry_focus_action
import georgiatransit.shared.generated.resources.map_route_geometry_focused
import georgiatransit.shared.generated.resources.map_route_geometry_loading
import georgiatransit.shared.generated.resources.map_route_geometry_partial
import georgiatransit.shared.generated.resources.map_route_geometry_selection_overflow
import georgiatransit.shared.generated.resources.map_route_geometry_ready
import georgiatransit.shared.generated.resources.map_route_geometry_remove_accessibility
import georgiatransit.shared.generated.resources.map_route_geometry_retry_accessibility
import georgiatransit.shared.generated.resources.map_route_geometry_retryable
import georgiatransit.shared.generated.resources.map_route_geometry_status_unavailable
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
    modifier: Modifier = Modifier,
) {
    if (routes.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        Text(
            stringResource(Res.string.map_route_geometry_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapRouteGeometryLegend),
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            items(routes, key = { it.routeId.value }) { route ->
                val retryDescription = stringResource(Res.string.map_route_geometry_retry_accessibility, route.routeLabel)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RouteGeometryChip(
                        route = route,
                        retryDescription = retryDescription,
                        onFocus = { onFocus(route.routeId) },
                        onRetry = { onRetry(route.routeId) },
                        onRemove = { onRemove(route.routeId) },
                    )
                    if (route.canRetry) {
                        RouteGeometryRetry(retryDescription, onClick = { onRetry(route.routeId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteGeometryChip(
    route: RouteGeometryLegendUi,
    retryDescription: String,
    onFocus: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foreground = Color(route.textColorArgb)
    val removeDescription = stringResource(Res.string.map_route_geometry_remove_accessibility, route.routeLabel)
    val focusAction = stringResource(Res.string.map_route_geometry_focus_action, route.routeLabel)
    val focused = stringResource(Res.string.map_route_geometry_focused)
    val description = stringResource(
        Res.string.map_route_geometry_chip_description,
        route.routeLabel,
        routeGeometryStatusLabel(route),
    )
    Surface(
        modifier = modifier.heightIn(min = RouteGeometryChipHeight),
        shape = InputChipDefaults.shape,
        color = Color(route.colorArgb),
        border = if (route.isFocused) BorderStroke(2.dp, foreground) else InputChipDefaults.inputChipBorder(
            enabled = true,
            selected = false,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Label and remove are separate touch and accessibility targets.
            Row(
                modifier = Modifier.heightIn(min = RouteGeometryChipHeight)
                    .testTag(AutomationId.MapRouteGeometryChip)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        if (route.isFocused) stateDescription = focused
                        if (route.canRetry) {
                            customActions = listOf(CustomAccessibilityAction(retryDescription) {
                                onRetry()
                                true
                            })
                        }
                    }
                    .clickable(enabled = route.canFocus, onClickLabel = focusAction, role = Role.Button, onClick = onFocus)
                    .padding(
                        start = TransitSpacing.Small,
                        end = TransitSpacing.ExtraSmall,
                        top = TransitSpacing.ExtraSmall,
                        bottom = TransitSpacing.ExtraSmall,
                    ),
                horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (route.state == RouteGeometryLegendState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(InputChipDefaults.IconSize).clearAndSetSemantics {},
                        color = foreground,
                        trackColor = foreground.copy(alpha = 0.25f),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(DirectionsBusIcon, contentDescription = null, tint = foreground, modifier = Modifier.size(InputChipDefaults.IconSize))
                }
                Text(
                    route.routeLabel,
                    modifier = Modifier.clearAndSetSemantics {},
                    color = foreground,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            VerticalDivider(modifier = Modifier.height(InputChipDefaults.IconSize), color = foreground.copy(alpha = 0.42f))
            Box(
                modifier = Modifier.padding(end = TransitSpacing.ExtraSmall)
                    .size(RouteGeometryRemoveSize)
                    .clip(CircleShape)
                    .testTag(AutomationId.MapRouteGeometryRemove)
                    .semantics { contentDescription = removeDescription }
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CloseIcon, contentDescription = null, tint = foreground, modifier = Modifier.size(InputChipDefaults.IconSize))
            }
        }
    }
}

@Composable
private fun RouteGeometryRetry(description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(RouteGeometryChipHeight)
            .clip(CircleShape)
            .testTag(AutomationId.MapRouteGeometryRetry)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(RouteGeometryRefreshIcon, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
        }
    }
}

@Composable
private fun routeGeometryStatusLabel(route: RouteGeometryLegendUi): String = when {
    route.totalDirections == 0 -> stringResource(Res.string.map_route_geometry_status_unavailable)
    else -> routeGeometryDirectionsStatus(route)
}

@Composable
private fun routeGeometryDirectionsStatus(route: RouteGeometryLegendUi): String = when (route.state) {
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
    RouteGeometryLegendState.SelectionOverflow -> stringResource(Res.string.map_route_geometry_selection_overflow)
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

/** Material Symbols "refresh" geometry, used under the Apache-2.0 license. */
private val RouteGeometryRefreshIcon: ImageVector by lazy {
    ImageVector.Builder("Refresh", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            fill = SolidColor(Color.Black),
            pathData = PathParser().parsePathString(
                "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-8 3.58-8 8s3.58 8 8 8c3.73 0 6.85-2.55 7.74-6h-2.08c-.83 2.33-3.05 4-5.66 4a6 6 0 1 1 0-12c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
            ).toNodes(),
        )
    }.build()
}

// PRODUCT REQUIREMENT: use Material 3 Input Chip geometry (32.dp minimum height, 18.dp icons,
// labelLarge text, standard shape and 8.dp icon/label/edge spacing). The remove action has
// a compact circular press indication. Do not add explicit 48.dp action slots or minimum widths:
// they widen these chips. Change this style only on an explicit product/design request.
private val RouteGeometryChipHeight = InputChipDefaults.Height
// Each half of an 8.dp trailing gap belongs to the neighbouring target/outer padding.
private val RouteGeometryRemoveSize = InputChipDefaults.IconSize + TransitSpacing.ExtraSmall * 2
