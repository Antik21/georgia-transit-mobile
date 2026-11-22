package com.denis.georgiatransit.shared.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.denis.georgiatransit.shared.presentation.languageselection.AppLanguageSettings
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

@Composable
internal actual fun rememberAppLanguageSettings(): AppLanguageSettings? {
    val settings = remember {
        if (NSBundle.mainBundle.localizations.count { it != "Base" } < 2) return@remember null
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return@remember null
        AppLanguageSettings.SystemSettings {
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        }
    } ?: return null

    // iOS exposes the per-app setting when the device has more than one preferred language.
    var available by remember { mutableStateOf(NSLocale.preferredLanguages.size > 1) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            available = NSLocale.preferredLanguages.size > 1
        }
    }
    return settings.takeIf { available }
}
