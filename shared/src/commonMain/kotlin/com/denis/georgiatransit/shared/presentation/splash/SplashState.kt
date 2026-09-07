package com.denis.georgiatransit.shared.presentation.splash

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.interactor.BootstrapTransitSession

@Immutable
sealed interface ViewState {
    data object Loading : ViewState
    data class Error(val failure: BootstrapTransitSession.Failure) : ViewState
    data class Ready(val destination: Destination) : ViewState
}

sealed interface Action {
    data object RetryClicked : Action
}

enum class Destination {
    CitySelection,
    Map,
}
