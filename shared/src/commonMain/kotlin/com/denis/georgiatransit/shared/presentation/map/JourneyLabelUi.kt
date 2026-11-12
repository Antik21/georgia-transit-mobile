package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.JourneyLabel

/** The one label palette for every client (ADR 0022): never tied to option order, route, session, or theme. */
enum class JourneyLabelUi(val label: JourneyLabel, val backgroundArgb: Long, val textArgb: Long) {
    Optimal(JourneyLabel.Optimal, 0xFF00796BL, 0xFFFFFFFFL),
    Fastest(JourneyLabel.Fastest, 0xFFC2410CL, 0xFFFFFFFFL),
    LeastWalking(JourneyLabel.LeastWalking, 0xFF1D4ED8L, 0xFFFFFFFFL),
    NoTransfers(JourneyLabel.NoTransfers, 0xFF6D28D9L, 0xFFFFFFFFL),
    ;

    companion object {
        fun of(labels: Collection<JourneyLabel>): List<JourneyLabelUi> = entries.filter { it.label in labels }
    }
}
