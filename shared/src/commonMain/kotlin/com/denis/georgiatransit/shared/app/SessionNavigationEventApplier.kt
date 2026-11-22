package com.denis.georgiatransit.shared.app

import com.denis.georgiatransit.shared.domain.repository.TransitSession

/**
 * Applies shell events using the TransitSession snapshot that exists at the moment of navigation.
 *
 * This keeps selection ownership in [TransitSession] while allowing the policy seam to be used
 * without Compose or a Navigation 3 back stack.
 */
internal class SessionNavigationEventApplier(
    private val session: TransitSession,
) {
    fun apply(
        currentStack: List<Destination>,
        event: NavigationEvent,
        languagePickerAvailable: Boolean = false,
    ): List<Destination> {
        val city = session.selectedCity.value
        return NavigationTransitionPolicy.transition(
            currentStack = currentStack,
            selectedCityId = city?.id,
            event = event,
            schedulesAvailable = city?.capabilities?.schedules == true,
            languagePickerAvailable = languagePickerAvailable,
        )
    }
}
