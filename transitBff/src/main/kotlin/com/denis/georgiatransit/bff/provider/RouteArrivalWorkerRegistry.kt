package com.denis.georgiatransit.bff.provider

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns demand-driven polling jobs keyed by a normalized route ID. A request touches the route and
 * receives the latest complete snapshot. The job keeps refreshing independently until the route
 * has had no requests for [idleTimeout].
 *
 * This registry is intentionally in-process. A multi-replica deployment must put a distributed
 * lease or route affinity in front of it before enabling a provider whose quota cannot tolerate
 * one poller per replica.
 */
internal class RouteArrivalWorkerRegistry<V>(
    private val pollInterval: Duration,
    private val idleTimeout: Duration,
    private val loader: suspend (String) -> V,
    private val nowNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    init {
        require(!pollInterval.isZero && !pollInterval.isNegative) { "pollInterval must be positive" }
        require(!idleTimeout.isZero && !idleTimeout.isNegative) { "idleTimeout must be positive" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val workers = mutableMapOf<String, Worker<V>>()
    private var closed = false

    suspend fun get(routeId: String): V {
        val worker = synchronized(lock) {
            check(!closed) { "Route arrival workers are closed" }
            workers[routeId]?.also { it.touch(nowNanos()) }
                ?: createWorker(routeId, nowNanos()).also { workers[routeId] = it }
        }
        worker.firstAttempt.await()
        return worker.latest.get()?.getOrThrow()
            ?: error("Route arrival worker completed no refresh attempt")
    }

    internal fun activeRouteCount(): Int = synchronized(lock) { workers.size }

    private fun createWorker(routeId: String, touchedAt: Long): Worker<V> {
        val worker = Worker<V>(touchedAt)
        worker.job = scope.launch {
            while (currentCoroutineContext().isActive) {
                val idleNanos = nowNanos() - worker.lastTouchedNanos.get()
                if (idleNanos >= idleTimeout.toNanos()) {
                    val removed = synchronized(lock) {
                        val stillIdle = nowNanos() - worker.lastTouchedNanos.get() >= idleTimeout.toNanos()
                        stillIdle && workers[routeId] === worker && workers.remove(routeId) != null
                    }
                    if (removed) break
                    continue
                }

                try {
                    worker.latest.set(Result.success(loader(routeId)))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    worker.latest.set(Result.failure(failure))
                } finally {
                    worker.firstAttempt.complete(Unit)
                }

                val remainingIdleNanos = idleTimeout.toNanos() -
                    (nowNanos() - worker.lastTouchedNanos.get())
                if (remainingIdleNanos <= 0L) continue
                delay((minOf(pollInterval.toNanos(), remainingIdleNanos) / 1_000_000L).coerceAtLeast(1L))
            }
        }
        return worker
    }

    override fun close() {
        val jobs = synchronized(lock) {
            if (closed) return
            closed = true
            val cancellation = CancellationException("Route arrival workers are closed")
            workers.values.forEach { it.firstAttempt.completeExceptionally(cancellation) }
            workers.values.mapNotNull(Worker<V>::job).also { workers.clear() }
        }
        scope.cancel(CancellationException("Route arrival workers are closed"))
        jobs.forEach(Job::cancel)
    }

    private class Worker<V>(touchedAt: Long) {
        val lastTouchedNanos = AtomicLong(touchedAt)
        val latest = AtomicReference<Result<V>?>(null)
        val firstAttempt = CompletableDeferred<Unit>()
        var job: Job? = null

        fun touch(now: Long) {
            lastTouchedNanos.set(now)
        }
    }
}
