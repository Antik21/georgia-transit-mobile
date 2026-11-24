package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Instant

enum class JourneyPlanSide(val markerLabel: String, val markerArgb: Long) {
    Origin("A", 0xFF2E7D32L),
    Destination("B", 0xFF8E1B2DL),
}

/** Why "My location" cannot be used yet; every reason still allows picking a map point instead. */
enum class JourneyLocationReason { PermissionRequired, Locating, Approximate }

enum class JourneyPlanErrorReason { Network, Busy, Rejected, Service }

/**
 * Map-entry memory only (ADR 0023): never persisted, routed, logged, or sent to analytics.
 * [pin] is a long-press point waiting for "Route here" or "Route from here".
 */
@Immutable
data class JourneyPlannerUi(
    val isOpen: Boolean = false,
    val pin: GeoPoint? = null,
    val origin: JourneyEndpoint? = null,
    val destination: JourneyEndpoint? = null,
    /** True while the results panel is open, including loading and error states. */
    val showingResults: Boolean = false,
    /** Once results have opened in this planner session, editing requires manual submission. */
    internal val hasOpenedResults: Boolean = false,
    val status: JourneyPlannerStatus = JourneyPlannerStatus.Idle,
    val render: JourneyPlanRenderUi = JourneyPlanRenderUi(),
    /** Countdown and live positions for the selected option's first boarding. */
    val buses: JourneyBusesUi? = null,
    /** A new plan, an explicit option change, or the first known position of its boarding bus. */
    val cameraFitRevision: Long = 0,
    /** The side being placed under the centred map marker; the panel stays hidden until it is chosen or cancelled. */
    val mapPick: JourneyPlanSide? = null,
    internal val boardingFitPosition: GeoPoint? = null,
)

@Immutable
sealed interface JourneyPlannerStatus {
    @Immutable data object Idle : JourneyPlannerStatus
    @Immutable data class PickingPoint(val side: JourneyPlanSide) : JourneyPlannerStatus
    @Immutable data object EndpointsIncomplete : JourneyPlannerStatus
    @Immutable data object Loading : JourneyPlannerStatus
    /** [isStale]: the BFF answered from an old live snapshot, or a re-plan failed and the last answer stays. */
    @Immutable data class Results(
        val options: List<JourneyOptionUi>,
        val selectedId: JourneyId,
        val isRefreshing: Boolean = false,
        val isStale: Boolean = false,
    ) : JourneyPlannerStatus
    @Immutable data class AlreadyThere(val walkMeters: Int, val walkMinutes: Int) : JourneyPlannerStatus
    @Immutable data object Empty : JourneyPlannerStatus
    @Immutable data class Error(val retryable: Boolean, val reason: JourneyPlanErrorReason) : JourneyPlannerStatus
    @Immutable data object Unavailable : JourneyPlannerStatus
    @Immutable data class LocationNeeded(val side: JourneyPlanSide, val reason: JourneyLocationReason) : JourneyPlannerStatus
}

/** Times are approximate when available. */
@Immutable
data class JourneyOptionUi(
    val id: JourneyId,
    val totalMinutes: Int?,
    val arrivalAt: Instant?,
    val transfers: Int,
    val walkingMeters: Int?,
    val timing: JourneyTimingUi?,
    val legs: List<JourneyLegUi>,
    val lines: PersistentList<JourneyPlanLineUi> = persistentListOf(),
    /** In [JourneyLabelUi] display order, whatever order the BFF sent. */
    val labels: List<JourneyLabelUi> = emptyList(),
    val boarding: JourneyBoarding? = null,
) {
    internal val isUntimedTransit: Boolean
        get() = totalMinutes == null && arrivalAt == null && legs.any { it is JourneyLegUi.Ride }
}

/** The first transit boarding of an option: where its nearest buses are looked up. */
@Immutable
data class JourneyBoarding(
    val stopId: StopId,
    val routeId: RouteId,
    val directionId: DirectionId? = null,
    val stopPosition: GeoPoint? = null,
    /** Travel heading leaving the stop; picks the right branch where a route runs a street both ways. */
    val departureBearing: Double? = null,
    /** A vehicle explicitly selected for this leg; the arrivals feed does not identify direction. */
    val plannedVehicleId: VehicleId? = null,
)

@Immutable
data class JourneyBusesUi(
    val optionId: JourneyId,
    val boarding: JourneyBoarding,
    val etaMinutes: List<Int>,
    val vehicles: List<JourneyBusUi>,
)

@Immutable
data class JourneyBusUi(
    val id: VehicleId,
    val position: GeoPoint,
    val bearingDegrees: Double?,
    val etaMinutes: Int,
    val route: JourneyRouteBadgeUi,
    val positionKind: VehiclePositionKind = VehiclePositionKind.Unknown,
)

enum class JourneyTimingUi { Live, Mixed, Estimated }

enum class JourneyWalkRole { Access, Transfer, Egress, Direct }

@Immutable
sealed interface JourneyLegUi {
    @Immutable data class Walk(
        val role: JourneyWalkRole,
        val meters: Int?,
        val minutes: Int,
        val isApproximate: Boolean,
    ) : JourneyLegUi

    /** A null [waitMinutes] means the wait is unknown. */
    @Immutable data class Ride(
        val route: JourneyRouteBadgeUi,
        val stopCount: Int?,
        val rideMinutes: Int?,
        val waitMinutes: Int?,
        val waitIsLive: Boolean,
        val fromStopId: StopId? = null,
        val toStopId: StopId? = null,
        val fromStopName: String? = null,
        val toStopName: String? = null,
    ) : JourneyLegUi
}

@Immutable
data class JourneyRouteBadgeUi(
    val routeId: RouteId,
    val label: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val mode: TransitMode = TransitMode.Bus,
)

enum class JourneyPlanLineKind { Transit, Walk }

@Immutable
data class JourneyPlanLineUi(
    val kind: JourneyPlanLineKind,
    val points: PersistentList<GeoPoint>,
    val colorArgb: Long,
)

/** Selected option geometry and endpoint markers; empty while nothing is chosen. */
@Immutable
data class JourneyPlanRenderUi(
    val lines: PersistentList<JourneyPlanLineUi> = persistentListOf(),
    val origin: GeoPoint? = null,
    val destination: GeoPoint? = null,
)

/** Planner commands; the long press arrives as [MapPlatformEvent.MapLongPressed]. */
sealed interface JourneyPlannerAction : Action {
    data object OpenPlanner : JourneyPlannerAction
    data object RouteHere : JourneyPlannerAction
    data object RouteFromHere : JourneyPlannerAction
    data object DismissPin : JourneyPlannerAction
    /** A tap on empty map: drops the pin, or clears the one already shown; ignored while map picking. */
    data class TapMap(val point: GeoPoint) : JourneyPlannerAction
    data object SetOriginToMyLocation : JourneyPlannerAction
    data object SetDestinationToMyLocation : JourneyPlannerAction
    data object StartPickingOrigin : JourneyPlannerAction
    data object StartPickingDestination : JourneyPlannerAction
    data class StartMapPick(val side: JourneyPlanSide) : JourneyPlannerAction
    data class ConfirmMapPick(val point: GeoPoint) : JourneyPlannerAction
    data object CancelMapPick : JourneyPlannerAction
    data object Swap : JourneyPlannerAction
    data object Clear : JourneyPlannerAction
    data object FindRoute : JourneyPlannerAction
    data object EditPoints : JourneyPlannerAction
    data class SelectOption(val id: JourneyId) : JourneyPlannerAction
    data object Retry : JourneyPlannerAction
    data object ClosePlanner : JourneyPlannerAction
}

internal const val JourneyWalkLineArgb = 0xFF6B7280L
internal const val JourneyUnknownRouteArgb = 0xFF455A64L
