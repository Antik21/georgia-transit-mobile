package com.denis.georgiatransit.bff.provider

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A single always-on, sequential poller that atomically publishes only complete snapshots. */
internal class ContinuousSnapshotWorker<V>(
    pollInterval: Duration,
    private val onSuccess: (V, Long) -> Unit = { _, _ -> },
    private val onFailure: (Throwable, Long) -> Unit = { _, _ -> },
    private val isEnabled: () -> Boolean = { true },
    private val loader: suspend () -> V,
) : AutoCloseable {
    init {
        require(!pollInterval.isZero && !pollInterval.isNegative) { "pollInterval must be positive" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val latest = AtomicReference<Result<V>?>(null)
    private val firstAttempt = CompletableDeferred<Unit>()
    private val job: Job = scope.launch {
        while (isActive) {
            if (!isEnabled()) {
                delay(pollInterval.toMillis().coerceAtLeast(1L))
                continue
            }
            val startedAt = System.nanoTime()
            try {
                val value = loader()
                latest.set(Result.success(value))
                onSuccess(value, System.nanoTime() - startedAt)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // A failed refresh never replaces a previously validated snapshot.
                if (latest.get()?.isSuccess != true) latest.set(Result.failure(failure))
                onFailure(failure, System.nanoTime() - startedAt)
            } finally {
                firstAttempt.complete(Unit)
            }
            val elapsedNanos = System.nanoTime() - startedAt
            val remainingMillis = ((pollInterval.toNanos() - elapsedNanos) / 1_000_000L).coerceAtLeast(1L)
            delay(remainingMillis)
        }
    }

    suspend fun get(): V {
        firstAttempt.await()
        return latest.get()?.getOrThrow() ?: error("Continuous worker completed no refresh attempt")
    }

    internal fun currentOrNull(): V? = latest.get()?.getOrNull()

    override fun close() {
        val cancellation = CancellationException("Continuous snapshot worker is closed")
        firstAttempt.completeExceptionally(cancellation)
        scope.cancel(cancellation)
        job.cancel(cancellation)
    }
}
