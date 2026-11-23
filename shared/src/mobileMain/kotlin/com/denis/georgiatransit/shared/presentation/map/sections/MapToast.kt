package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.location_failure_unavailable
import georgiatransit.shared.generated.resources.map_connection_error
import georgiatransit.shared.generated.resources.map_planner_unavailable
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal data class MapToastNotice(val id: Int, val kind: MapToastKind)

internal enum class MapToastKind(val message: StringResource, val automationId: String) {
    ConnectionError(Res.string.map_connection_error, AutomationId.MapConnectionError),
    LocationError(Res.string.location_failure_unavailable, AutomationId.MapLocationError),
    PlannerUnavailable(Res.string.map_planner_unavailable, AutomationId.MapPlannerUnavailable),
}

@Composable
internal fun MapToast(notice: MapToastNotice?, modifier: Modifier = Modifier) {
    if (notice == null) return
    var visible by remember { mutableStateOf(false) }
    val timeoutMillis = if (notice.kind == MapToastKind.PlannerUnavailable) {
        LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
            PLANNER_UNAVAILABLE_DURATION_MILLIS,
            containsText = true,
        ) ?: PLANNER_UNAVAILABLE_DURATION_MILLIS
    } else {
        TOAST_DURATION_MILLIS
    }
    LaunchedEffect(notice.id, timeoutMillis) {
        visible = true
        delay(timeoutMillis)
        visible = false
    }
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            modifier = Modifier
                .testTag(notice.kind.automationId)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = TransitShapes.Full,
            shadowElevation = TransitSpacing.ExtraSmall,
        ) {
            Text(
                text = stringResource(notice.kind.message),
                modifier = Modifier.padding(horizontal = TransitSpacing.Medium, vertical = TransitSpacing.Small),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private const val TOAST_DURATION_MILLIS = 3_000L
private const val PLANNER_UNAVAILABLE_DURATION_MILLIS = 4_000L
