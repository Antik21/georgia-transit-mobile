package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.denis.georgiatransit.shared.domain.model.WalkingEstimateSource
import com.denis.georgiatransit.shared.presentation.map.WalkingDistanceFormat
import com.denis.georgiatransit.shared.presentation.map.WalkingDurationFormat
import com.denis.georgiatransit.shared.presentation.map.WalkingEstimateUi
import com.denis.georgiatransit.shared.presentation.map.WalkingEstimateUnavailableReason
import com.denis.georgiatransit.shared.presentation.map.formatWalkingEstimate
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_walking_estimate_about
import georgiatransit.shared.generated.resources.map_walking_estimate_action
import georgiatransit.shared.generated.resources.map_walking_estimate_distance_kilometers
import georgiatransit.shared.generated.resources.map_walking_estimate_distance_less_than_fifty
import georgiatransit.shared.generated.resources.map_walking_estimate_distance_meters
import georgiatransit.shared.generated.resources.map_walking_estimate_duration_hours_minutes
import georgiatransit.shared.generated.resources.map_walking_estimate_duration_minutes
import georgiatransit.shared.generated.resources.map_walking_estimate_fallback_note
import georgiatransit.shared.generated.resources.map_walking_estimate_loading
import georgiatransit.shared.generated.resources.map_walking_estimate_source_approximate
import georgiatransit.shared.generated.resources.map_walking_estimate_source_routed
import georgiatransit.shared.generated.resources.map_walking_estimate_title
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_location
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_no_accurate_fix
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_permission_denied
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_permission_required
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_restricted
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_services_disabled
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_settings
import org.jetbrains.compose.resources.stringResource

/** Separate, non-blocking walking feedback. Every duration is explicitly displayed as approximate. */
@Composable
internal fun WalkingEstimateSection(
    estimate: WalkingEstimateUi,
    onMyLocation: () -> Unit,
    isLocationActionable: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapWalkingEstimate),
        shape = TransitShapes.Small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(TransitSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        ) {
            Text(stringResource(Res.string.map_walking_estimate_title), style = MaterialTheme.typography.labelLarge)
            when (estimate) {
                WalkingEstimateUi.Loading -> Text(
                    stringResource(Res.string.map_walking_estimate_loading),
                    modifier = Modifier.testTag(AutomationId.MapWalkingEstimateLoading).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                is WalkingEstimateUi.Ready -> {
                    val formatted = formatWalkingEstimate(
                        distanceMeters = estimate.distanceMeters,
                        durationSeconds = estimate.durationSeconds,
                        locale = estimate.locale,
                    )
                    Text(
                        stringResource(Res.string.map_walking_estimate_about, walkingDurationLabel(formatted.duration)),
                        modifier = Modifier.testTag(AutomationId.MapWalkingEstimateReady).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(walkingDistanceLabel(formatted.distance), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            if (estimate.source == WalkingEstimateSource.Routed) {
                                Res.string.map_walking_estimate_source_routed
                            } else {
                                Res.string.map_walking_estimate_source_approximate
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (estimate.source == WalkingEstimateSource.Approximate) {
                        Text(
                            stringResource(Res.string.map_walking_estimate_fallback_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is WalkingEstimateUi.Unavailable -> {
                    Text(
                        walkingUnavailableLabel(estimate.reason),
                        modifier = Modifier.testTag(AutomationId.MapWalkingEstimateUnavailable).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (isLocationActionable) {
                        TextButton(onClick = onMyLocation, modifier = Modifier.testTag(AutomationId.MapWalkingEstimateAction)) {
                            Text(stringResource(Res.string.map_walking_estimate_action))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun walkingDistanceLabel(distance: WalkingDistanceFormat): String = when (distance) {
    WalkingDistanceFormat.UnderFiftyMeters -> stringResource(Res.string.map_walking_estimate_distance_less_than_fifty)
    is WalkingDistanceFormat.Meters -> stringResource(Res.string.map_walking_estimate_distance_meters, distance.value)
    is WalkingDistanceFormat.KilometersTenths -> stringResource(Res.string.map_walking_estimate_distance_kilometers, distance.value)
    is WalkingDistanceFormat.KilometersWhole -> stringResource(Res.string.map_walking_estimate_distance_kilometers, distance.value.toString())
}

@Composable
private fun walkingDurationLabel(duration: WalkingDurationFormat): String = when (duration) {
    is WalkingDurationFormat.Minutes -> stringResource(Res.string.map_walking_estimate_duration_minutes, duration.value)
    is WalkingDurationFormat.HoursMinutes -> stringResource(
        Res.string.map_walking_estimate_duration_hours_minutes,
        duration.hours,
        duration.minutes,
    )
}

@Composable
private fun walkingUnavailableLabel(reason: WalkingEstimateUnavailableReason): String = stringResource(
    when (reason) {
        WalkingEstimateUnavailableReason.PermissionRequired -> Res.string.map_walking_estimate_unavailable_permission_required
        WalkingEstimateUnavailableReason.PermissionDenied -> Res.string.map_walking_estimate_unavailable_permission_denied
        WalkingEstimateUnavailableReason.SettingsRequired -> Res.string.map_walking_estimate_unavailable_settings
        WalkingEstimateUnavailableReason.Restricted -> Res.string.map_walking_estimate_unavailable_restricted
        WalkingEstimateUnavailableReason.ServicesDisabled -> Res.string.map_walking_estimate_unavailable_services_disabled
        WalkingEstimateUnavailableReason.LocationUnavailable -> Res.string.map_walking_estimate_unavailable_location
        WalkingEstimateUnavailableReason.NoAccurateFix -> Res.string.map_walking_estimate_unavailable_no_accurate_fix
    },
)
