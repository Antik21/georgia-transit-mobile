package com.denis.georgiatransit.bff.cache

import com.denis.georgiatransit.bff.api.SingleFlightCapacityExceeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Shares a bounded real-time fetch among callers. Work belongs to the BFF scope, so cancelling one
 * HTTP call does not cancel another caller's refresh. Entries are removed when their work settles.
 */
class SingleFlight<K, V>(
    private val lifetime: Duration,
    private val maximumEntries: Int = 128,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val inFlight = mutableMapOf<K, Deferred<V>>()
    private var isClosed = false

    suspend fun get(key: K, loader: suspend () -> V): V {
        val deferred = synchronized(lock) {
            checkOpenLocked()
            pruneCompletedLocked()
            checkOpenLocked()
            inFlight[key] ?: createDeferred(key, loader)
        }
        return deferred.await()
    }

    private fun createDeferred(key: K, loader: suspend () -> V): Deferred<V> {
        checkOpenLocked()
        if (inFlight.size >= maximumEntries) throw SingleFlightCapacityExceeded()
        val deferred = CompletableDeferred<V>()
        inFlight[key] = deferred
        scope.launch {
            try {
                val value = withTimeout(lifetime.toJavaDuration().toMillis()) { loader() }
                synchronized(lock) {
                    deferred.complete(value)
                    if (inFlight[key] === deferred) inFlight.remove(key)
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    synchronized(lock) {
                        deferred.completeExceptionally(failure)
                        if (inFlight[key] === deferred) inFlight.remove(key)
                    }
                }
            }
        }
        return deferred
    }

    private fun pruneCompletedLocked() {
        inFlight.entries.removeIf { (_, deferred) -> deferred.isCompleted }
    }

    override fun close() {
        val deferreds = synchronized(lock) {
            if (isClosed) {
                null
            } else {
                isClosed = true
                inFlight.values.toList().also { inFlight.clear() }
            }
        } ?: return
        val cancellation = CancellationException("SingleFlight is closed")
        scope.cancel(cancellation)
        deferreds.forEach { it.cancel(cancellation) }
    }

    private fun checkOpenLocked() {
        if (isClosed) throw CancellationException("SingleFlight is closed")
    }
}
