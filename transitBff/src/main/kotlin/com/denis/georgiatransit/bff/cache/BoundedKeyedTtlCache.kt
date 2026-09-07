package com.denis.georgiatransit.bff.cache

import com.denis.georgiatransit.bff.api.UpstreamUnavailable
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A bounded, coroutine-safe keyed TTL cache. A key has exactly one loading generation, so all
 * concurrent callers await the same result. Only expired ready values are pruned for capacity;
 * loading and still-live values are never evicted.
 */
class BoundedKeyedTtlCache<K, V>(
    private val ttl: Duration,
    private val maximumEntries: Int,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val entries = mutableMapOf<K, Entry<V>>()

    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
    }

    suspend fun getOrLoad(key: K, loader: suspend () -> V): V {
        val lookup = mutex.withLock {
            pruneExpiredLocked(clock.instant())
            when (val entry = entries[key]) {
                is Ready -> Lookup.Value(entry.value)
                is Loading -> Lookup.InFlight(entry.result)
                null -> Lookup.InFlight(startLoadLocked(key, loader))
            }
        }
        return when (lookup) {
            is Lookup.Value -> lookup.value
            is Lookup.InFlight -> lookup.result.await()
        }
    }

    override fun close() {
        scope.cancel()
    }

    private fun startLoadLocked(key: K, loader: suspend () -> V): CompletableDeferred<V> {
        if (entries.size >= maximumEntries) {
            throw UpstreamUnavailable("The directory cache is at capacity", retryAfterSeconds = 1)
        }
        val result = CompletableDeferred<V>()
        entries[key] = Loading(result)
        scope.launch {
            try {
                val value = loader()
                mutex.withLock {
                    val current = entries[key]
                    if (current is Loading && current.result === result) {
                        entries[key] = Ready(value, clock.instant().plus(ttl.toJavaDuration()))
                    }
                }
                result.complete(value)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        val current = entries[key]
                        if (current is Loading && current.result === result) entries.remove(key)
                    }
                    result.completeExceptionally(failure)
                }
            }
        }
        return result
    }

    private fun pruneExpiredLocked(now: Instant) {
        entries.entries.removeIf { (_, entry) -> entry is Ready && !now.isBefore(entry.expiresAt) }
    }

    private sealed interface Entry<V>

    private data class Ready<V>(
        val value: V,
        val expiresAt: Instant,
    ) : Entry<V>

    private data class Loading<V>(val result: CompletableDeferred<V>) : Entry<V>

    private sealed interface Lookup<out V> {
        data class Value<V>(val value: V) : Lookup<V>

        data class InFlight<V>(val result: Deferred<V>) : Lookup<V>
    }
}
