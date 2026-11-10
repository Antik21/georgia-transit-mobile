package com.denis.georgiatransit.shared.presentation.location

import androidx.compose.runtime.Composable

/** Executes foreground-only OS work and re-queries authorization whenever the screen resumes. */
@Composable
expect fun PlatformLocationEffect(
    command: LocationPlatformCommand?,
    onCommandConsumed: () -> Unit,
    onEvent: (LocationPlatformEvent) -> Unit,
)
