package com.denis.georgiatransit.shared.presentation.languageselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.TransitTopAppBar
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.language_apply
import georgiatransit.shared.generated.resources.language_system_default
import georgiatransit.shared.generated.resources.language_title
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
internal fun LanguageSelectionScreen(
    viewModel: LanguageSelectionViewModel,
    handleNavigation: (NavigationEffect) -> Unit,
) {
    val state by viewModel.collectAsState()
    viewModel.collectSideEffect { effect ->
        when (effect) {
            is NavigationEffect -> handleNavigation(effect)
        }
    }
    Content(state = state, onAction = viewModel::dispatchAction)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag(AutomationId.LanguageScreen),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TransitTopAppBar(
                title = stringResource(Res.string.language_title),
                closeButtonModifier = Modifier.testTag(AutomationId.LanguageClose),
                onClose = { onAction(Action.Close) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = TransitSpacing.Large)
                .padding(top = TransitSpacing.Medium, bottom = TransitSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                LanguageRow(
                    name = stringResource(Res.string.language_system_default),
                    selected = state.selectedLanguageTag == null,
                    onSelect = { onAction(Action.Select(null)) },
                    modifier = Modifier.testTag(AutomationId.LanguageSystemDefault),
                )
                state.languages.forEach { language ->
                    LanguageRow(
                        name = language.displayName,
                        selected = state.selectedLanguageTag == language.languageTag,
                        onSelect = { onAction(Action.Select(language.languageTag)) },
                        modifier = Modifier.testTag(AutomationId.LanguageOption),
                    )
                }
            }
            Button(
                onClick = { onAction(Action.Apply) },
                enabled = state.canApply,
                modifier = Modifier.fillMaxWidth().testTag(AutomationId.LanguageApply),
            ) { Text(stringResource(Res.string.language_apply)) }
        }
    }
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ListItem(
            headlineContent = { Text(name) },
            modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Preview
@Composable
private fun Preview() {
    GeorgiaTransitTheme {
        Content(
            state = ViewState(
                languages = listOf(LanguageOption("en", "English"), LanguageOption("ka", "ქართული")),
                appliedLanguageTag = null,
                selectedLanguageTag = "ka",
            ),
            onAction = {},
        )
    }
}
