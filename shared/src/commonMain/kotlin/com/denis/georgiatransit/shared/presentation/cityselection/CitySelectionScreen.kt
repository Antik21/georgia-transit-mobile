package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.city_experimental
import georgiatransit.shared.generated.resources.city_subtitle
import georgiatransit.shared.generated.resources.city_title
import georgiatransit.shared.generated.resources.city_unavailable
import georgiatransit.shared.generated.resources.continue_action
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun CitySelectionScreen(
    viewModel: CitySelectionViewModel,
    handleNavigation: suspend (NavigationEffect) -> Unit,
) {
    val state by viewModel.collectAsState()
    viewModel.collectSideEffect { effect -> handleNavigation(effect as NavigationEffect) }
    Content(state = state, onAction = viewModel::dispatchAction)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().testTag(AutomationId.CityScreen).padding(TransitSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
    ) {
        Text(stringResource(Res.string.city_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.city_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            items(state.cities, key = { it.id.value }) { city ->
                CityRow(
                    city = city,
                    selected = state.selectedCityId == city.id,
                    onClick = { onAction(Action.CityClicked(city.id)) },
                )
            }
        }
        Button(
            onClick = { onAction(Action.ContinueClicked) },
            enabled = state.selectedCityId != null,
            modifier = Modifier.fillMaxWidth().testTag(AutomationId.CityContinue),
        ) { Text(stringResource(Res.string.continue_action)) }
    }
}

@Composable
private fun CityRow(city: CityItemUiModel, selected: Boolean, onClick: () -> Unit) {
    val tag = when (city.id.value) {
        "tbilisi" -> AutomationId.CityTbilisi
        "batumi" -> AutomationId.CityBatumi
        else -> "city-selection.${city.id.value}"
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .then(if (city.isEnabled) Modifier.clickable(onClick = onClick) else Modifier.semantics { disabled() }),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, enabled = city.isEnabled, onClick = if (city.isEnabled) onClick else null)
            Column(modifier = Modifier.weight(1f)) {
                Text(city.name, style = MaterialTheme.typography.titleMedium)
                if (city.isExperimental) {
                    Text(stringResource(Res.string.city_experimental), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!city.isEnabled) Text(stringResource(Res.string.city_unavailable), style = MaterialTheme.typography.labelLarge)
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
                    CityItemUiModel(CityId("tbilisi"), "Tbilisi", true, false),
                    CityItemUiModel(CityId("batumi"), "Batumi", true, true),
                    CityItemUiModel(CityId("kutaisi"), "Kutaisi", false, true),
                ),
                selectedCityId = CityId("tbilisi"),
            ),
            onAction = {},
        )
    }
}
