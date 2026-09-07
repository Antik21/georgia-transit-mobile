package com.denis.georgiatransit.bff.cache

import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A small coroutine-safe cache that also deduplicates a refresh for one key. */
class ExpiringCache<T>(
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    private var entry: Entry<T>? = null

    suspend fun getOrLoad(loader: suspend () -> T): T =
        mutex.withLock {
            val now = clock.instant()
            entry?.takeIf { now.isBefore(it.expiresAt) }?.value
                ?: loader().also { value -> entry = Entry(value, now.plus(ttl.toJavaDuration())) }
        }

    private data class Entry<T>(val value: T, val expiresAt: Instant)
}
