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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_connection_error
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Shows a short, non-interactive notice each time [noticeId] advances; 0 means none yet. */
@Composable
internal fun MapConnectionToast(noticeId: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(noticeId) {
        if (noticeId == 0) return@LaunchedEffect
        visible = true
        delay(TOAST_DURATION_MILLIS)
        visible = false
    }
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            modifier = Modifier
                .testTag(AutomationId.MapConnectionError)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = TransitShapes.Full,
            shadowElevation = TransitSpacing.ExtraSmall,
        ) {
            Text(
                text = stringResource(Res.string.map_connection_error),
                modifier = Modifier.padding(horizontal = TransitSpacing.Medium, vertical = TransitSpacing.Small),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private const val TOAST_DURATION_MILLIS = 3_000L
