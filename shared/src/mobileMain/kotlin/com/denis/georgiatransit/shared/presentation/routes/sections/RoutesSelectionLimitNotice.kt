package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_selection_limit_warning
import georgiatransit.shared.generated.resources.routes_show_selected
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RoutesSelectionLimitNotice(modifier: Modifier = Modifier, onShowSelected: (() -> Unit)? = null) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(AutomationId.RoutesSelectionWarning)
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = stringResource(Res.string.routes_selection_limit_warning, RouteSelectionPolicy.MaximumSelectedRoutes),
            modifier = Modifier.padding(
                start = TransitSpacing.Medium,
                top = TransitSpacing.Medium,
                end = TransitSpacing.Medium,
                bottom = if (onShowSelected == null) TransitSpacing.Medium else TransitSpacing.ExtraSmall,
            ),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onShowSelected != null) {
            TextButton(
                onClick = onShowSelected,
                modifier = Modifier.padding(horizontal = TransitSpacing.ExtraSmall)
                    .testTag(AutomationId.RoutesShowSelected),
            ) {
                Text(stringResource(Res.string.routes_show_selected), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
