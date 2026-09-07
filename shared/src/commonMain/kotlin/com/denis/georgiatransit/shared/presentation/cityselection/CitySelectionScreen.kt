package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.EmptyState
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.city_catalog_empty
import georgiatransit.shared.generated.resources.city_catalog_error
import georgiatransit.shared.generated.resources.city_catalog_loading
import georgiatransit.shared.generated.resources.city_catalog_offline
import georgiatransit.shared.generated.resources.city_catalog_retry
import georgiatransit.shared.generated.resources.city_experimental
import georgiatransit.shared.generated.resources.city_subtitle
import georgiatransit.shared.generated.resources.city_title
import georgiatransit.shared.generated.resources.city_unavailable
import georgiatransit.shared.generated.resources.continue_action
import georgiatransit.shared.generated.resources.location_action_enable
import georgiatransit.shared.generated.resources.location_action_location_settings
import georgiatransit.shared.generated.resources.location_action_refresh
import georgiatransit.shared.generated.resources.location_action_retry
import georgiatransit.shared.generated.resources.location_action_settings
import georgiatransit.shared.generated.resources.location_body
import georgiatransit.shared.generated.resources.location_failure_invalid
import georgiatransit.shared.generated.resources.location_failure_timed_out
import georgiatransit.shared.generated.resources.location_failure_unavailable
import georgiatransit.shared.generated.resources.location_locating
import georgiatransit.shared.generated.resources.location_status_approximate
import georgiatransit.shared.generated.resources.location_status_denied
import georgiatransit.shared.generated.resources.location_status_error
import georgiatransit.shared.generated.resources.location_status_not_determined
import georgiatransit.shared.generated.resources.location_status_precise
import georgiatransit.shared.generated.resources.location_status_restricted
import georgiatransit.shared.generated.resources.location_status_services_disabled
import georgiatransit.shared.generated.resources.location_status_settings_required
import georgiatransit.shared.generated.resources.location_status_unavailable
import georgiatransit.shared.generated.resources.location_title
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun CitySelectionScreen(
    viewModel: CitySelectionViewModel,
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
    Content(state = state, onAction = viewModel::dispatchAction)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().testTag(AutomationId.CityScreen).padding(TransitSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
    ) {
        Text(
            text = stringResource(Res.string.city_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(Res.string.city_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val catalog = state.catalog) {
            CityCatalogState.Loading -> LoadingState(
                message = stringResource(Res.string.city_catalog_loading),
                modifier = Modifier.weight(1f).testTag(AutomationId.CityLoading),
            )

            is CityCatalogState.Populated -> CityList(
                state = state,
                isStaleOffline = catalog.freshness == com.denis.georgiatransit.shared.domain.repository.TransitFreshness.StaleOffline,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )

            is CityCatalogState.Empty -> EmptyState(
                title = stringResource(Res.string.city_catalog_empty),
                message = stringResource(Res.string.city_catalog_offline)
                    .takeIf { catalog.freshness == com.denis.georgiatransit.shared.domain.repository.TransitFreshness.StaleOffline },
                actionLabel = stringResource(Res.string.city_catalog_retry),
                onAction = { onAction(Action.RetryClicked) },
                actionModifier = Modifier.testTag(AutomationId.CityRetry),
                modifier = Modifier.weight(1f).testTag(AutomationId.CityEmpty),
            )

            is CityCatalogState.RetryableError -> ErrorState(
                title = stringResource(Res.string.city_catalog_error),
                retryLabel = stringResource(Res.string.city_catalog_retry),
                onRetry = { onAction(Action.RetryClicked) },
                retryModifier = Modifier.testTag(AutomationId.CityRetry),
                modifier = Modifier.weight(1f).testTag(AutomationId.CityError),
            )
        }
        LocationCard(state.location, onClick = { onAction(Action.LocationClicked) })
        Button(
            onClick = { onAction(Action.ContinueClicked) },
            enabled = state.canContinue,
            modifier = Modifier.fillMaxWidth().testTag(AutomationId.CityContinue),
        ) { Text(stringResource(Res.string.continue_action)) }
    }
}

@Composable
private fun CityList(
    state: ViewState,
    isStaleOffline: Boolean,
    onAction: (Action) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        if (isStaleOffline) {
            Text(
                text = stringResource(Res.string.city_catalog_offline),
                modifier = Modifier
                    .testTag(AutomationId.CityStaleOffline)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
    }
}

@Composable
private fun LocationCard(location: LocationState, onClick: () -> Unit) {
    val permission = location.permission
    val status = when {
        location.isLocating -> stringResource(Res.string.location_locating)
        location.failure != null -> when (requireNotNull(location.failure)) {
            LocationFailure.TimedOut -> stringResource(Res.string.location_failure_timed_out)
            LocationFailure.InvalidFix -> stringResource(Res.string.location_failure_invalid)
            LocationFailure.Unavailable -> stringResource(Res.string.location_failure_unavailable)
        }
        else -> when (permission) {
            LocationPermissionState.NotDetermined -> stringResource(Res.string.location_status_not_determined)
            is LocationPermissionState.Granted -> when (permission.precision) {
                LocationPrecision.Precise -> stringResource(Res.string.location_status_precise)
                LocationPrecision.Approximate -> stringResource(Res.string.location_status_approximate)
            }
            LocationPermissionState.Denied -> stringResource(Res.string.location_status_denied)
            LocationPermissionState.SettingsRequired -> stringResource(Res.string.location_status_settings_required)
            LocationPermissionState.Restricted -> stringResource(Res.string.location_status_restricted)
            LocationPermissionState.ServicesDisabled -> stringResource(Res.string.location_status_services_disabled)
            is LocationPermissionState.Unavailable -> stringResource(Res.string.location_status_unavailable)
            is LocationPermissionState.Error -> stringResource(Res.string.location_status_error)
        }
    }
    val action = when (permission) {
        LocationPermissionState.NotDetermined -> Res.string.location_action_enable
        is LocationPermissionState.Granted -> Res.string.location_action_refresh
        LocationPermissionState.Denied -> Res.string.location_action_retry
        LocationPermissionState.SettingsRequired -> Res.string.location_action_settings
        LocationPermissionState.ServicesDisabled -> Res.string.location_action_location_settings
        LocationPermissionState.Restricted -> null
        is LocationPermissionState.Unavailable -> Res.string.location_action_retry.takeIf { permission.canRetry }
        is LocationPermissionState.Error -> Res.string.location_action_retry.takeIf { permission.canRetry }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Text(stringResource(Res.string.location_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.location_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.labelLarge)
            action?.let {
                OutlinedButton(
                    onClick = onClick,
                    enabled = !location.isLocating,
                    modifier = Modifier.fillMaxWidth().testTag(AutomationId.CityLocation),
                ) { Text(stringResource(it)) }
            }
        }
    }
}

@Composable
private fun CityRow(city: CityItemUiModel, selected: Boolean, onClick: () -> Unit) {
    val modifier = city.id.citySelectionAutomationId()?.let { Modifier.testTag(it) } ?: Modifier
    Card(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = city.isEnabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, enabled = city.isEnabled, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = city.localizedName.citySelectionDisplayName(Locale.current.language, city.name),
                    style = MaterialTheme.typography.titleMedium,
                )
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
