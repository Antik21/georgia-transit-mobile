package com.denis.georgiatransit.shared.app

import androidx.compose.ui.window.ComposeUIViewController
import com.denis.georgiatransit.shared.di.initGeorgiaTransitKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initGeorgiaTransitKoin()
    return ComposeUIViewController { App() }
}
