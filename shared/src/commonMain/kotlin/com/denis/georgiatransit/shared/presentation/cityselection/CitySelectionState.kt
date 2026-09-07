package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationState

@Immutable
data class ViewState(
    val cities: List<CityItemUiModel> = emptyList(),
    val selectedCityId: CityId? = null,
    val catalog: CityCatalogState = if (cities.isEmpty()) {
        CityCatalogState.Loading
    } else {
        CityCatalogState.Populated(TransitFreshness.CacheValid)
    },
    val location: LocationState = LocationState(),
    val isConfirming: Boolean = false,
) {
    val canContinue: Boolean
        get() = !isConfirming &&
            catalog is CityCatalogState.Populated &&
            selectedCityId != null &&
            cities.any { it.id == selectedCityId && it.isEnabled }
}

/** The catalog state is explicit so an empty snapshot is never mistaken for a loading failure. */
@Immutable
sealed interface CityCatalogState {
    data object Loading : CityCatalogState

    data class Populated(
        val freshness: TransitFreshness,
        val revalidationFailure: TransitFailure? = null,
    ) : CityCatalogState

    data class Empty(
        val freshness: TransitFreshness,
        val revalidationFailure: TransitFailure? = null,
    ) : CityCatalogState

    data class RetryableError(val failure: TransitFailure) : CityCatalogState
}

@Immutable
data class CityItemUiModel(
    val id: CityId,
    val name: String,
    val isEnabled: Boolean,
    val isExperimental: Boolean,
    /** BFF-provided display values; no city names are supplied by the client. */
    val localizedName: LocalizedText = LocalizedText.fromLegacy(name),
    /** Normalized public credits stay visible even when the city cannot yet be selected. */
    val attribution: List<TransitAttribution> = emptyList(),
)

sealed interface Action {
    data class CityClicked(val cityId: CityId) : Action
    data object RetryClicked : Action
    data object ContinueClicked : Action
    data object LocationClicked : Action
    data class LocationEventReceived(val event: LocationPlatformEvent) : Action
}

sealed interface SideEffect {
    data class HandleLocation(val command: LocationPlatformCommand) : SideEffect
}

sealed interface NavigationEffect : SideEffect {
    data object OpenMap : NavigationEffect
}
