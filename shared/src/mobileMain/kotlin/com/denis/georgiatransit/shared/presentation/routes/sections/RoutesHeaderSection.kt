package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.route_city_pin
import georgiatransit.shared.generated.resources.routes_selection_count
import georgiatransit.shared.generated.resources.routes_selection_limit_warning
import georgiatransit.shared.generated.resources.routes_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RoutesHeaderSection(
    cityName: String,
    selectedCount: Int,
    isSelectionLimitReached: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
    ) {
        Text(
            text = stringResource(Res.string.routes_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cityName.isNotBlank()) {
                    Icon(
                        painter = painterResource(Res.drawable.route_city_pin),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(cityName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                }
            }
            Surface(
                shape = TransitShapes.Full,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    text = stringResource(Res.string.routes_selection_count, selectedCount),
                    modifier = Modifier.testTag(AutomationId.RoutesSelectionCount)
                        .padding(horizontal = TransitSpacing.Small, vertical = TransitSpacing.ExtraSmall),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
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
}
