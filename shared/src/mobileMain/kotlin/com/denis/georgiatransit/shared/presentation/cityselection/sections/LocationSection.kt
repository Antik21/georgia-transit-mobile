package com.denis.georgiatransit.shared.presentation.cityselection.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
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

@Composable
internal fun LocationSection(
    location: LocationState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    Card(modifier = modifier.fillMaxWidth()) {
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
