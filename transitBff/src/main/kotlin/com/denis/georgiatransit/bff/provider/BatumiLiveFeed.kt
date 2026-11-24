package com.denis.georgiatransit.bff.provider

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

internal enum class BatumiLiveSource { BATBUS, THETA }

internal data class BatumiLiveGeneration(
    val source: BatumiLiveSource,
    val vehiclesByRoute: Map<String, List<BatumiLiveVehicle>>,
    val observedAt: Instant,
)

/** Owned by the sequential city worker. Every attempt probes BatBus first. */
internal class BatumiLiveFeed(
    private val primary: BatumiAllBusesClient,
    private val theta: BatumiThetaCatalogClient,
    private val clock: Clock,
    private val isEnabled: () -> Boolean,
) {
    private val headings = BatumiVehicleHeadings()
    private var firstFailureAt: Instant? = null
    private var nextThetaSweepAt: Instant = Instant.MIN

    fun enabled(): Boolean = isEnabled().also { enabled ->
        if (!enabled) firstFailureAt = null
    }

    suspend fun load(catalog: BatumiCatalog): BatumiLiveGeneration = headings.update(loadGeneration(catalog))

    private suspend fun loadGeneration(catalog: BatumiCatalog): BatumiLiveGeneration {
        if (!enabled()) throw ProviderUnavailable("Batumi live polling is disabled")
        val startedAt = clock.instant()
        try {
            val response = primary.getAllBuses()
            val observedAt = clock.instant()
            val vehicles = BatumiAllBusesParser.parse(response, catalog, observedAt)
            validateEtaWorkload(catalog, vehicles)
            firstFailureAt = null
            return BatumiLiveGeneration(BatumiLiveSource.BATBUS, vehicles, observedAt)
        } catch (failure: ProviderFailure) {
            // A malformed primary is unavailable, but cannot disable an independent validated source.
            val schemaFailure = failure is ProviderNormalizedSchemaFailure || failure is ProviderJsonDecodeFailure
            if (firstFailureAt == null) {
                firstFailureAt = startedAt
                val issue = failure.javaClass.simpleName
                log.warn("batumi_primary_outage_started issue={}", issue)
            }
            if (Duration.between(requireNotNull(firstFailureAt), clock.instant()) < FailoverDelay) {
                if (schemaFailure) throw ProviderUnavailable("Batumi primary live data did not pass validation")
                throw failure
            }
        }

        try {
            return loadTheta(catalog)
        } catch (failure: ProviderRateLimited) {
            val retryAt = clock.instant().plusSeconds(failure.retryAfterSeconds.toLong())
            nextThetaSweepAt = maxOf(nextThetaSweepAt, retryAt)
            throw failure
        }
    }

    private suspend fun loadTheta(catalog: BatumiCatalog): BatumiLiveGeneration {
        val startedAt = clock.instant()
        if (startedAt.isBefore(nextThetaSweepAt)) {
            throw ProviderUnavailable("Batumi fallback is waiting for the provider retry window")
        }
        nextThetaSweepAt = startedAt.plus(ThetaSweepPeriod)
        val permits = Semaphore(ThetaParallelRequests)
        val entries = withTimeoutOrNull(ThetaSweepTimeout.toMillis()) {
            coroutineScope {
                catalog.routes.map { route ->
                    async {
                        permits.withPermit {
                            if (!isEnabled()) throw ProviderUnavailable("Batumi live polling is disabled")
                            val response = theta.getBusLocsOnRoute(catalog.rawRouteId(route.id))
                            val receivedAt = clock.instant()
                            val vehicles = BatumiThetaLiveParser.parse(response, route, receivedAt, requireArray = true)
                            Triple(route.id, receivedAt, vehicles)
                        }
                    }
                }.awaitAll()
            }
        } ?: throw ProviderTimeout("Batumi fallback sweep timed out")
        val vehiclesByRoute = entries.associate { (routeId, _, vehicles) -> routeId to vehicles }
        if (vehiclesByRoute.values.sumOf { it.size } > BatumiMaximumGlobalVehicles) {
            throw ProviderNormalizedSchemaFailure("Batumi fallback exceeds the global vehicle limit")
        }
        validateEtaWorkload(catalog, vehiclesByRoute)
        return BatumiLiveGeneration(BatumiLiveSource.THETA, vehiclesByRoute, entries.minOf { it.second })
    }

    private fun validateEtaWorkload(catalog: BatumiCatalog, vehicles: Map<String, List<BatumiLiveVehicle>>) {
        validateEtaWorkload(catalog.routes.map { route ->
            vehicles.getValue(route.id).size to catalog.cycle(route.id).chain.size
        })
    }

    private companion object {
        val log = LoggerFactory.getLogger("TransitBff.BatumiFeed")
        val FailoverDelay: Duration = Duration.ofMinutes(3)
        val ThetaSweepPeriod: Duration = Duration.ofSeconds(10)
        val ThetaSweepTimeout: Duration = Duration.ofSeconds(10)
        const val ThetaParallelRequests = 4
    }
}
