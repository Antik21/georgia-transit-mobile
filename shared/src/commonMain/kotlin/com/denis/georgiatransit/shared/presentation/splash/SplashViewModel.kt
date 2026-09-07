package com.denis.georgiatransit.shared.presentation.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.interactor.BootstrapTransitSession
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class SplashViewModel(
    private val bootstrapTransitSession: BootstrapTransitSession,
) : ViewModel(), ContainerHost<ViewState, Nothing> {
    private val bootstrapGate = BootstrapGate()

    override val container: Container<ViewState, Nothing> = viewModelScope.container(
        initialState = ViewState.Loading,
        onCreate = { bootstrap() },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            Action.RetryClicked -> bootstrap()
        }
    }

    private fun bootstrap() {
        // Calls may race across threads, so atomically claim the gate before enqueuing an intent.
        if (!bootstrapGate.tryClaim()) return
        intent {
            try {
                reduce { ViewState.Loading }
                val result = bootstrapTransitSession.bootstrap()
                reduce {
                    when (result) {
                        BootstrapTransitSession.Result.OpenCitySelection -> ViewState.Ready(Destination.CitySelection)
                        BootstrapTransitSession.Result.OpenMap -> ViewState.Ready(Destination.Map)
                        is BootstrapTransitSession.Result.Error -> ViewState.Error(result.failure)
                    }
                }
            } finally {
                bootstrapGate.release()
            }
        }
    }
}
