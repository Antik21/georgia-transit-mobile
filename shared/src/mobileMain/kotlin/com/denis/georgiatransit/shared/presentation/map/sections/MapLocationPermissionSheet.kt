package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.component.TransitCloseButton
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.location_action_settings
import georgiatransit.shared.generated.resources.location_permission_message
import georgiatransit.shared.generated.resources.location_permission_request
import georgiatransit.shared.generated.resources.location_permission_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapLocationPermissionSheet(
    openSettings: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().enableAutomationResourceIds()
                .testTag(AutomationId.MapLocationPermissionSheet)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TransitSpacing.Large)
                .padding(bottom = TransitSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.location_permission_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                TransitCloseButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            if (!sheetState.isVisible) onDismiss()
                        }
                    },
                    modifier = Modifier.testTag(AutomationId.MapLocationPermissionClose),
                    tonal = true,
                )
            }
            Text(
                text = stringResource(Res.string.location_permission_message),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapLocationPermissionConfirm),
            ) {
                val label = if (openSettings) Res.string.location_action_settings else Res.string.location_permission_request
                Text(stringResource(label), textAlign = TextAlign.Center)
            }
        }
    }
}
