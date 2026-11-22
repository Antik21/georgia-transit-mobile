package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint

/** The missing side when exactly one endpoint is set. The map pin offers both actions otherwise. */
internal fun journeyExpandedSideFor(origin: JourneyEndpoint?, destination: JourneyEndpoint?): JourneyPlanSide? = when {
    origin != null && destination == null -> JourneyPlanSide.Destination
    origin == null && destination != null -> JourneyPlanSide.Origin
    else -> null
}

internal fun journeyDefaultExpandedSideFor(origin: JourneyEndpoint?, destination: JourneyEndpoint?): JourneyPlanSide? =
    if (origin == null && destination == null) JourneyPlanSide.Origin else journeyExpandedSideFor(origin, destination)
