package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.component.TransitCloseButton
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_change_city_action
import georgiatransit.shared.generated.resources.map_settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapSettingsSheet(
    onDismiss: () -> Unit,
    onChangeCity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Content(
            onClose = {
                scope.launch {
                    sheetState.hide()
                    if (!sheetState.isVisible) onDismiss()
                }
            },
            onChangeCity = onChangeCity,
            modifier = Modifier.enableAutomationResourceIds().testTag(AutomationId.MapSettingsSheet),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    onClose: () -> Unit,
    onChangeCity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    ) {
        BottomSheetDefaults.DragHandle(
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .testTag(AutomationId.MapSettingsHandle)
                .clearAndSetSemantics {},
        )
        Column(
            modifier = Modifier.padding(horizontal = TransitSpacing.Large)
                .padding(bottom = TransitSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.map_settings),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                TransitCloseButton(
                    onClick = onClose,
                    modifier = Modifier.testTag(AutomationId.MapSettingsClose),
                    tonal = true,
                )
            }
            OutlinedButton(
                onClick = onChangeCity,
                modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapChangeCity),
            ) {
                Text(stringResource(Res.string.map_change_city_action))
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    GeorgiaTransitTheme {
        Content(onClose = {}, onChangeCity = {})
    }
}
