package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.component.TransitCloseButton
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_selected_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectedRoutesSheet(
    routes: List<RouteItemUiModel>,
    onDismiss: () -> Unit,
    onRemove: (RouteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().enableAutomationResourceIds()
                .testTag(AutomationId.RoutesSelectedSheet)
                .padding(horizontal = TransitSpacing.Medium)
                .padding(bottom = TransitSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.routes_selected_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                TransitCloseButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(AutomationId.RoutesSelectedClose),
                    tonal = true,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                items(routes, key = { it.id.value }) { route ->
                    RouteRow(
                        route = route,
                        selected = true,
                        selectionLimitReached = true,
                        onClick = { onRemove(route.id) },
                    )
                }
            }
        }
    }
}
