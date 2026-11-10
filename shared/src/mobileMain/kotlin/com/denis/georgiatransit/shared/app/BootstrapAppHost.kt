package com.denis.georgiatransit.shared.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.denis.georgiatransit.shared.presentation.splash.Destination as SplashDestination
import com.denis.georgiatransit.shared.presentation.splash.SplashScreen
import com.denis.georgiatransit.shared.presentation.splash.SplashViewModel
import com.denis.georgiatransit.shared.presentation.splash.ViewState
import org.koin.compose.viewmodel.koinViewModel
import org.orbitmvi.orbit.compose.collectAsState

/**
 * A gate before the Navigation 3 stack exists.
 *
 * Bootstrap is intentionally not a navigation destination, so it cannot be restored above a
 * Map or Routes entry. Only a terminal bootstrap result creates the typed navigation stack.
 */
@Composable
internal fun BootstrapAppHost() {
    val viewModel: SplashViewModel = koinViewModel(key = "bootstrap")
    val state by viewModel.collectAsState()

    when (state) {
        ViewState.Loading,
        is ViewState.Error,
        -> SplashScreen(state = state, onAction = viewModel::dispatchAction)

        is ViewState.Ready -> Navigation3AppHost(
            initialDestination = when ((state as ViewState.Ready).destination) {
                SplashDestination.CitySelection -> Destination.CitySelection
                SplashDestination.Map -> Destination.Map
            },
        )
    }
}
