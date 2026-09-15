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
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_cancel_action
import georgiatransit.shared.generated.resources.routes_selection_count
import georgiatransit.shared.generated.resources.routes_selection_limit_warning
import georgiatransit.shared.generated.resources.routes_subtitle
import georgiatransit.shared.generated.resources.routes_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RoutesHeaderSection(
    cityName: String,
    selectedCount: Int,
    isSelectionLimitReached: Boolean,
    onCancel: () -> Unit,
) {
    TextButton(
        onClick = onCancel,
        modifier = Modifier.testTag(AutomationId.RoutesCancel),
    ) {
        Text(stringResource(Res.string.routes_cancel_action))
    }
    Text(stringResource(Res.string.routes_title), style = MaterialTheme.typography.headlineSmall)
    Text(cityName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(Res.string.routes_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        text = stringResource(
            Res.string.routes_selection_count,
            selectedCount,
            RouteSelectionPolicy.MaximumSelectedRoutes,
        ),
        modifier = Modifier.testTag(AutomationId.RoutesSelectionCount),
        style = MaterialTheme.typography.labelLarge,
    )
    if (isSelectionLimitReached) {
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
}
