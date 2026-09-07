package com.denis.georgiatransit.shared.presentation.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.domain.interactor.BootstrapTransitSession
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitColors
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.bootstrap_error_configuration_invalid
import georgiatransit.shared.generated.resources.bootstrap_error_configuration_unavailable
import georgiatransit.shared.generated.resources.bootstrap_error_timeout
import georgiatransit.shared.generated.resources.bootstrap_error_unavailable
import georgiatransit.shared.generated.resources.bootstrap_loading
import georgiatransit.shared.generated.resources.bootstrap_retry
import georgiatransit.shared.generated.resources.bootstrap_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun SplashScreen(state: ViewState, onAction: (Action) -> Unit) {
    when (state) {
        ViewState.Loading -> SplashLoading()
        is ViewState.Error -> SplashError(state.failure, onAction)
        is ViewState.Ready -> Unit
    }
}

@Composable
private fun SplashLoading() {
    SplashSurface(Modifier.testTag(AutomationId.SplashLoading)) {
        LoadingState(message = stringResource(Res.string.bootstrap_loading))
    }
}

@Composable
private fun SplashError(
    failure: BootstrapTransitSession.Failure,
    onAction: (Action) -> Unit,
) {
    val message = stringResource(
        when (failure) {
            BootstrapTransitSession.Failure.TimedOut -> Res.string.bootstrap_error_timeout
            BootstrapTransitSession.Failure.Unavailable -> Res.string.bootstrap_error_unavailable
            BootstrapTransitSession.Failure.RuntimeConfigurationUnavailable ->
                Res.string.bootstrap_error_configuration_unavailable
            BootstrapTransitSession.Failure.RuntimeConfigurationInvalid ->
                Res.string.bootstrap_error_configuration_invalid
        },
    )
    SplashSurface(Modifier.testTag(AutomationId.SplashError)) {
        ErrorState(
            title = stringResource(Res.string.bootstrap_title),
            message = message,
            retryLabel = stringResource(Res.string.bootstrap_retry),
            onRetry = { onAction(Action.RetryClicked) },
            retryModifier = Modifier.testTag(AutomationId.SplashRetry),
        )
    }
}

@Composable
private fun SplashSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(TransitColors.LaunchBackground),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Preview
@Composable
private fun LoadingPreview() {
    GeorgiaTransitTheme { SplashScreen(ViewState.Loading, onAction = {}) }
}

@Preview
@Composable
private fun ErrorPreview() {
    GeorgiaTransitTheme {
        SplashScreen(ViewState.Error(BootstrapTransitSession.Failure.Unavailable), onAction = {})
    }
}
