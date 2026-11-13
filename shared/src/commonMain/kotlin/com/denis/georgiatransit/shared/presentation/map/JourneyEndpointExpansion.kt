package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint

/** The endpoint block opened after the endpoints change: the one still missing, or none once both are set. */
internal fun journeyExpandedSideFor(origin: JourneyEndpoint?, destination: JourneyEndpoint?): JourneyPlanSide? = when {
    origin != null && destination == null -> JourneyPlanSide.Destination
    origin == null && destination != null -> JourneyPlanSide.Origin
    else -> null
}
