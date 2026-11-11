package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val LocationRefreshIntervalMillis = 5_000L

@Composable
internal fun MapLocationRefreshEffect(hasPermission: Boolean, onRefresh: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnRefresh = rememberUpdatedState(onRefresh)
    LaunchedEffect(lifecycle, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(LocationRefreshIntervalMillis)
                currentOnRefresh.value()
            }
        }
    }
}
