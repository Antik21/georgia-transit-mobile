package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.JourneyPlanMode
import com.denis.georgiatransit.shared.domain.model.RouteId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Instant

enum class JourneyPlanSide { Origin, Destination }

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
    val mode: JourneyPlanMode = JourneyPlanMode.Fastest,
    val status: JourneyPlannerStatus = JourneyPlannerStatus.Idle,
    val render: JourneyPlanRenderUi = JourneyPlanRenderUi(),
)

@Immutable
sealed interface JourneyPlannerStatus {
    @Immutable data object Idle : JourneyPlannerStatus
    @Immutable data class PickingPoint(val side: JourneyPlanSide) : JourneyPlannerStatus
    @Immutable data object EndpointsIncomplete : JourneyPlannerStatus
    @Immutable data object Loading : JourneyPlannerStatus
    /** [isStale] keeps the last answer visible when a periodic re-plan could not refresh it. */
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

/** Every time is approximate: the planner is a `CLIENT_ESTIMATE`, never an official timetable. */
@Immutable
data class JourneyOptionUi(
    val id: JourneyId,
    val totalMinutes: Int,
    val arrivalAt: Instant,
    val transfers: Int,
    val walkingMeters: Int?,
    val timing: JourneyTimingUi?,
    val legs: List<JourneyLegUi>,
    val lines: PersistentList<JourneyPlanLineUi> = persistentListOf(),
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

    /** [waitIsLive] is true only for a projected live vehicle; anything else is an estimate. */
    @Immutable data class Ride(
        val route: JourneyRouteBadgeUi,
        val stopCount: Int?,
        val rideMinutes: Int,
        val waitMinutes: Int?,
        val waitIsLive: Boolean,
    ) : JourneyLegUi
}

@Immutable
data class JourneyRouteBadgeUi(
    val routeId: RouteId,
    val label: String,
    val backgroundArgb: Long,
    val textArgb: Long,
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
    data object RouteHere : JourneyPlannerAction
    data object RouteFromHere : JourneyPlannerAction
    data object DismissPin : JourneyPlannerAction
    data object SetOriginToMyLocation : JourneyPlannerAction
    data object SetDestinationToMyLocation : JourneyPlannerAction
    data object StartPickingOrigin : JourneyPlannerAction
    data object StartPickingDestination : JourneyPlannerAction
    data object Swap : JourneyPlannerAction
    data object Clear : JourneyPlannerAction
    data class SelectOption(val id: JourneyId) : JourneyPlannerAction
    data class ChangeMode(val mode: JourneyPlanMode) : JourneyPlannerAction
    data object Retry : JourneyPlannerAction
    data object ClosePlanner : JourneyPlannerAction
}

internal const val JourneyWalkLineArgb = 0xFF6B7280L
internal const val JourneyUnknownRouteArgb = 0xFF455A64L
