package com.denis.georgiatransit.shared.domain.repository

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Collapses BFF transport failures into at most one user notice per outage. Background polling
 * cannot repeat it; a reachable BFF or a new user request (such as a route selection) re-arms it.
 */
class ConnectionErrorNotices {
    private val notified = MutableStateFlow(false)

    // A failure during bootstrap happens before the map collects; it must wait, not be dropped.
    private val pending = Channel<Unit>(Channel.CONFLATED)

    val notices: Flow<Unit> = pending.receiveAsFlow()

    fun onTransportFailure() {
        if (notified.compareAndSet(expect = false, update = true)) pending.trySend(Unit)
    }

    fun rearm() {
        notified.value = false
        pending.tryReceive()
    }
}
