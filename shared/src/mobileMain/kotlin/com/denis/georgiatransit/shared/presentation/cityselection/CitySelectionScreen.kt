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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.presentation.cityselection.sections.CityCatalogSection
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.TransitTopAppBar
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.city_open_map
import georgiatransit.shared.generated.resources.city_privacy_link
import georgiatransit.shared.generated.resources.city_privacy_notice
import georgiatransit.shared.generated.resources.city_title
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

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit, onBack: (() -> Unit)? = null) {
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag(AutomationId.CityScreen),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TransitTopAppBar(
                title = stringResource(Res.string.city_title),
                closeButtonModifier = Modifier.testTag(AutomationId.CityBack),
                onClose = onBack,
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
            CityCatalogSection(
                catalog = state.catalog,
                cities = state.cities,
                selectedCityId = state.selectedCityId,
                onCityClick = { onAction(Action.CityClicked(it)) },
                onRetry = { onAction(Action.RetryClicked) },
                modifier = Modifier.weight(1f),
            )
            if (state.catalog !is CityCatalogState.RetryableError) {
                val openMap = stringResource(Res.string.city_open_map)
                Button(
                    onClick = { onAction(Action.ContinueClicked) },
                    enabled = state.canContinue,
                    modifier = Modifier.fillMaxWidth().testTag(AutomationId.CityContinue),
                ) { Text(openMap) }
                PrivacyNotice(openMap, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PrivacyNotice(openMap: String, modifier: Modifier = Modifier) {
    val linkLabel = stringResource(Res.string.city_privacy_link)
    val notice = stringResource(Res.string.city_privacy_notice, openMap, linkLabel)
    val language = Locale.current.language
    val link = LinkAnnotation.Url(
        url = "https://www.geotransit.com.ge/privacy.html?lang=$language",
        styles = TextLinkStyles(
            style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
        ),
    )
    Text(
        text = buildAnnotatedString {
            append(notice)
            val start = notice.indexOf(linkLabel)
            addLink(link, start, start + linkLabel.length)
        },
        modifier = modifier.testTag(AutomationId.CityPrivacyNotice),
        style = MaterialTheme.typography.bodySmall,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
