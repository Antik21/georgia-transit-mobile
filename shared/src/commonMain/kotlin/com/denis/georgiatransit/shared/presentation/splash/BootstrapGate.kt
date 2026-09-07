package com.denis.georgiatransit.shared.presentation.splash

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Atomic one-at-a-time gate for bootstrap work requested from arbitrary caller threads. */
@OptIn(ExperimentalAtomicApi::class)
internal class BootstrapGate {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(): Boolean = claimed.compareAndSet(expectedValue = false, newValue = true)

    fun release() {
        claimed.store(false)
    }
}
