package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable

internal interface PlannerLauncherPreference {
    fun wasUsed(): Boolean
    fun markUsed()
}

internal const val PlannerLauncherUsedKey = "planner_launcher_used"

@Composable
internal expect fun rememberPlannerLauncherPreference(): PlannerLauncherPreference
