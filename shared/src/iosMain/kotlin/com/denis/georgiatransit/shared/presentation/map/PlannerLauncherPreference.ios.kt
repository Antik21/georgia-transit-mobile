package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import platform.Foundation.NSUserDefaults

@Composable
internal actual fun rememberPlannerLauncherPreference(): PlannerLauncherPreference = IosPlannerLauncherPreference

private object IosPlannerLauncherPreference : PlannerLauncherPreference {
    override fun wasUsed(): Boolean = NSUserDefaults.standardUserDefaults.boolForKey(PlannerLauncherUsedKey)

    override fun markUsed() {
        NSUserDefaults.standardUserDefaults.setBool(true, forKey = PlannerLauncherUsedKey)
    }
}
