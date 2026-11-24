package com.denis.georgiatransit.shared.presentation.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberPlannerLauncherPreference(): PlannerLauncherPreference {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidPlannerLauncherPreference(context) }
}

private class AndroidPlannerLauncherPreference(context: Context) : PlannerLauncherPreference {
    private val preferences by lazy {
        context.getSharedPreferences("georgia_transit.map", Context.MODE_PRIVATE)
    }

    override fun wasUsed(): Boolean = preferences.getBoolean(PlannerLauncherUsedKey, false)

    override fun markUsed() {
        preferences.edit().putBoolean(PlannerLauncherUsedKey, true).apply()
    }
}
