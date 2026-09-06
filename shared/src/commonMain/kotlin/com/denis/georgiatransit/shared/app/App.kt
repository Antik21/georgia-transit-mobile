package com.denis.georgiatransit.shared.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme

@Composable
fun App() {
    GeorgiaTransitTheme {
        Box(Modifier.fillMaxSize().enableAutomationResourceIds()) { Navigation3AppHost() }
    }
}

