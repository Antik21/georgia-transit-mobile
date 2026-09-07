package com.denis.georgiatransit.shared.presentation.splash

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapGateConcurrentClaimTest {
    @Test
    fun concurrentClaimsHaveOneWinnerAndReleaseAllowsAnotherClaim() {
        val gate = BootstrapGate()
        val workersReady = CountDownLatch(ConcurrentClaimCount)
        val releaseWorkers = CountDownLatch(1)
        val workersDone = CountDownLatch(ConcurrentClaimCount)
        val successfulClaims = AtomicInteger(0)
        val workers = List(ConcurrentClaimCount) { index ->
            thread(name = "bootstrap-gate-racer-$index") {
                workersReady.countDown()
                try {
                    releaseWorkers.await()
                    if (gate.tryClaim()) successfulClaims.incrementAndGet()
                } finally {
                    workersDone.countDown()
                }
            }
        }

        try {
            assertTrue(workersReady.await(ThreadTimeoutSeconds, TimeUnit.SECONDS))
            releaseWorkers.countDown()
            assertTrue(workersDone.await(ThreadTimeoutSeconds, TimeUnit.SECONDS))

            assertEquals(1, successfulClaims.get())
            assertFalse(gate.tryClaim())

            gate.release()
            assertTrue(gate.tryClaim())
            assertFalse(gate.tryClaim())
        } finally {
            releaseWorkers.countDown()
            workers.forEach { worker ->
                worker.join(TimeUnit.SECONDS.toMillis(ThreadTimeoutSeconds))
            }
            assertTrue(workers.none { it.isAlive })
            gate.release()
        }
    }

    private companion object {
        const val ConcurrentClaimCount = 32
        const val ThreadTimeoutSeconds = 5L
    }
}
