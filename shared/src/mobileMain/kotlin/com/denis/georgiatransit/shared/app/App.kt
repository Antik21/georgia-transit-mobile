package com.denis.georgiatransit.shared.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.denis.georgiatransit.shared.domain.analytics.ProductAnalytics
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import org.koin.compose.koinInject

@Composable
fun App() {
    val analytics = koinInject<ProductAnalytics>()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, analytics) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> analytics.setForeground(true)
                Lifecycle.Event.ON_STOP -> analytics.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            analytics.setForeground(false)
        }
    }
    GeorgiaTransitTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .enableAutomationResourceIds(),
        ) {
            BootstrapAppHost()
        }
    }
}
