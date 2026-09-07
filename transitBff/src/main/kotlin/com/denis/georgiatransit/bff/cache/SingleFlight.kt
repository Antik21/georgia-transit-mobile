package com.denis.georgiatransit.bff.cache

import com.denis.georgiatransit.bff.api.SingleFlightCapacityExceeded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<K, Deferred<V>>()

    suspend fun get(key: K, loader: suspend () -> V): V {
        val deferred = mutex.withLock {
            inFlight[key] ?: createDeferred(key, loader)
        }
        return deferred.await()
    }

    private fun createDeferred(key: K, loader: suspend () -> V): Deferred<V> {
        if (inFlight.size >= maximumEntries) throw SingleFlightCapacityExceeded()
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            withTimeout(lifetime.toJavaDuration().toMillis()) { loader() }
        }
        inFlight[key] = deferred
        deferred.invokeOnCompletion {
            scope.launch {
                mutex.withLock {
                    if (inFlight[key] === deferred) inFlight.remove(key)
                }
            }
        }
        deferred.start()
        return deferred
    }

    override fun close() {
        scope.cancel()
    }
}
