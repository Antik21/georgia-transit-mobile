package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.presentation.cityselection.sections.CityCatalogSection
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.map.sections.CloseIcon
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.city_subtitle
import georgiatransit.shared.generated.resources.city_title
import georgiatransit.shared.generated.resources.continue_action
import georgiatransit.shared.generated.resources.routes_cancel_action
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun CitySelectionScreen(
    viewModel: CitySelectionViewModel,
    onBack: (() -> Unit)?,
    handleNavigation: suspend (NavigationEffect) -> Unit,
) {
    val state by viewModel.collectAsState()
    var platformCommand by remember { mutableStateOf<LocationPlatformCommand?>(null) }
    viewModel.collectSideEffect { effect ->
        when (effect) {
            is NavigationEffect -> handleNavigation(effect)
            is SideEffect.HandleLocation -> platformCommand = effect.command
        }
    }
    PlatformLocationEffect(
        command = platformCommand,
        onCommandConsumed = { platformCommand = null },
        onEvent = { viewModel.dispatchAction(Action.LocationEventReceived(it)) },
    )
    Content(state = state, onAction = viewModel::dispatchAction, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit, onBack: (() -> Unit)? = null) {
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag(AutomationId.CityScreen),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.city_title),
                        modifier = Modifier.semantics { heading() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag(AutomationId.CityBack)) {
                            Icon(CloseIcon, contentDescription = stringResource(Res.string.routes_cancel_action))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(
                    start = TransitSpacing.Large,
                    top = TransitSpacing.Medium,
                    end = TransitSpacing.Large,
                    bottom = TransitSpacing.Large,
                ),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
        ) {
            Text(stringResource(Res.string.city_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            CityCatalogSection(
                catalog = state.catalog,
                cities = state.cities,
                selectedCityId = state.selectedCityId,
                onCityClick = { onAction(Action.CityClicked(it)) },
                onRetry = { onAction(Action.RetryClicked) },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onAction(Action.ContinueClicked) },
                enabled = state.canContinue,
                modifier = Modifier.fillMaxWidth().testTag(AutomationId.CityContinue),
            ) { Text(stringResource(Res.string.continue_action)) }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    GeorgiaTransitTheme {
        Content(
            state = ViewState(
                cities = listOf(
                    CityItemUiModel(CityId("demo"), "Demo", true, false),
                    CityItemUiModel(
                        id = CityId("tbilisi"),
                        name = "Tbilisi",
                        isEnabled = false,
                        isExperimental = false,
                        attribution = listOf(
                            TransitAttribution(
                                id = "transitous",
                                label = LocalizedText.fromLegacy("Transitous sources"),
                                url = "https://transitous.org/sources/",
                            ),
                            TransitAttribution(
                                id = "openstreetmap",
                                label = LocalizedText.fromLegacy("© OpenStreetMap contributors (ODbL)"),
                                url = "https://www.openstreetmap.org/copyright",
                            ),
                        ),
                    ),
                    CityItemUiModel(CityId("batumi"), "Batumi", true, true),
                ),
                selectedCityId = CityId("demo"),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
