package com.denis.georgiatransit.shared.domain.interactor

import com.denis.georgiatransit.shared.core.telemetry.AppTelemetry
import com.denis.georgiatransit.shared.core.telemetry.NoOpAppTelemetry
import com.denis.georgiatransit.shared.core.telemetry.NonFatalIssue
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfiguration
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitRepositoryException
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

class BootstrapTransitSession(
    private val repository: TransitRepository,
    private val session: TransitSession,
    private val selectedCityStore: SelectedCityStore,
    private val runtimeConfigurationSource: RuntimeBootstrapConfigurationSource,
    private val telemetry: AppTelemetry = NoOpAppTelemetry,
) {
    suspend fun bootstrap(requestedCity: CityId? = null, requestedRoute: RouteId? = null): Result {
        val runtimeConfiguration = when (val result = loadRuntimeConfiguration()) {
            is RuntimeConfigurationResult.Ready -> result.configuration
            is RuntimeConfigurationResult.Error -> {
                session.clearSelectedCity()
                return Result.Error(result.failure)
            }
        }
        val cachedSelection = readValidCachedSelection(runtimeConfiguration)

        // A first selection owns its catalog request so it can represent loading, empty, and
        // retryable failures in the City Selection UI instead of collapsing them into Splash.
        // A direct city link also needs the catalog before it can open the map.
        if (cachedSelection == null && requestedCity == null) {
            session.clearSelectedCity()
            return Result.OpenCitySelection
        }

        val targetCity = requestedCity ?: cachedSelection?.city?.id
        val targetRoutes = if (requestedCity == null) cachedSelection?.selectedRouteIds.orEmpty() else setOfNotNull(requestedRoute)
        return try {
            val bootstrapStartedAt = TimeSource.Monotonic.markNow()
            val currentCities = withTimeout(runtimeConfiguration.bootstrapTimeoutMillis) {
                repository.loadCityCapabilitySnapshot()
            }
            val currentCity = currentCities.firstOrNull { it.id == targetCity && it.isMapEligible() }

            if (currentCity != null) {
                session.restoreCitySelection(
                    city = currentCity,
                    routeIds = validateRouteSelection(
                        city = currentCity,
                        routeIds = targetRoutes,
                        timeoutMillis = (
                            runtimeConfiguration.bootstrapTimeoutMillis -
                                bootstrapStartedAt.elapsedNow().inWholeMilliseconds
                            ).coerceAtLeast(0L),
                    ),
                )
                Result.OpenMap
            } else {
                if (requestedCity == null) clearInvalidSelection()
                Result.OpenCitySelection
            }
        } catch (_: TimeoutCancellationException) {
            restoreOfflineOrError(cachedSelection, requestedCity, Failure.TimedOut)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (failure !is TransitRepositoryException) telemetry.recordNonFatal(NonFatalIssue.Startup)
            restoreOfflineOrError(cachedSelection, requestedCity, Failure.Unavailable)
        }
    }

    private fun restoreOfflineOrError(cachedSelection: CachedCitySnapshot?, requestedCity: CityId?, failure: Failure): Result {
        if (requestedCity != null && cachedSelection?.city?.id != requestedCity) return Result.Error(failure)
        val cachedCity = cachedSelection?.city
        if (cachedCity != null) {
            // The city snapshot is eligible for offline restoration, but route IDs are not: their
            // authoritative city catalog could not be revalidated during this bootstrap.
            session.restoreCitySelection(
                city = cachedCity,
                routeIds = emptySet(),
            )
            return Result.OpenMap
        }

        session.clearSelectedCity()
        return Result.Error(failure)
    }

    private suspend fun loadRuntimeConfiguration(): RuntimeConfigurationResult = try {
        val configuration = withTimeout(RuntimeConfigurationSourceTimeoutMillis) {
            runtimeConfigurationSource.load()
        }
            ?: return RuntimeConfigurationResult.Error(Failure.RuntimeConfigurationUnavailable)

        if (configuration.validationFailure() == null) {
            RuntimeConfigurationResult.Ready(configuration)
        } else {
            RuntimeConfigurationResult.Error(Failure.RuntimeConfigurationInvalid)
        }
    } catch (_: TimeoutCancellationException) {
        RuntimeConfigurationResult.Error(Failure.RuntimeConfigurationUnavailable)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        telemetry.recordNonFatal(NonFatalIssue.Startup)
        RuntimeConfigurationResult.Error(Failure.RuntimeConfigurationUnavailable)
    }

    private fun readValidCachedSelection(configuration: RuntimeBootstrapConfiguration): CachedCitySnapshot? {
        val cachedSnapshot = try {
            selectedCityStore.read()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            telemetry.recordNonFatal(NonFatalIssue.SelectedCityStorage)
            clearPersistedSelection()
            null
        }

        return cachedSnapshot
            ?.takeIf { it.schemaVersion in configuration.compatibleCachedCitySchemaVersions }
            ?.takeIf { it.city.isMapEligible() }
            ?.let { snapshot ->
                snapshot.copy(selectedRouteIds = snapshot.routeIdsForRestore())
            }
            ?: run {
                if (cachedSnapshot != null) clearPersistedSelection()
                null
            }
    }

    private fun clearInvalidSelection() {
        // Clear memory first: a failed durable clear must never leave an invalid runtime city.
        session.clearSelectedCity()
        clearPersistedSelection()
    }

    private fun clearPersistedSelection() {
        try {
            selectedCityStore.clear()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // A stale durable value will be revalidated at the next bootstrap attempt.
            telemetry.recordNonFatal(NonFatalIssue.SelectedCityStorage)
        }
    }

    private fun TransitCity.isMapEligible(): Boolean =
        id.value.isNotBlank() &&
            name.isNotBlank() &&
            countryCode.isNotBlank() &&
            center.latitude.isFinite() &&
            center.latitude in -90.0..90.0 &&
            center.longitude.isFinite() &&
            center.longitude in -180.0..180.0 &&
            capabilities.stops

    /**
     * Uses the remaining shared bootstrap budget so route hydration cannot extend Splash
     * indefinitely. A missing, stale, malformed, or failed route response restores the valid
     * city with an empty selection rather than exposing IDs that Map cannot resolve.
     */
    private suspend fun validateRouteSelection(
        city: TransitCity,
        routeIds: Set<RouteId>,
        timeoutMillis: Long,
    ): Set<RouteId> {
        if (!city.capabilities.routes || routeIds.isEmpty() || timeoutMillis <= 0L) return emptySet()

        return try {
            when (
                val result = withTimeout(timeoutMillis) {
                    repository.refreshRoutes(RouteListRequest(cityId = city.id, mode = null))
                }
            ) {
                is TransitLoadResult.Data -> when (result.freshness) {
                    TransitFreshness.Network,
                    TransitFreshness.CacheValid,
                    TransitFreshness.NetworkValidated,
                    -> result.value
                        .takeIf { routes -> routes.all { it.cityId == city.id } }
                        ?.mapTo(mutableSetOf()) { it.id }
                        ?.let(routeIds::intersect)
                        .orEmpty()

                    TransitFreshness.StaleOffline -> emptySet()
                }

                is TransitLoadResult.Empty,
                is TransitLoadResult.Failure,
                -> emptySet()
            }
        } catch (_: TimeoutCancellationException) {
            emptySet()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (failure !is TransitRepositoryException) telemetry.recordNonFatal(NonFatalIssue.Startup)
            emptySet()
        }
    }

    sealed interface Result {
        data object OpenCitySelection : Result
        data object OpenMap : Result
        data class Error(val failure: Failure) : Result
    }

    enum class Failure {
        TimedOut,
        Unavailable,
        RuntimeConfigurationUnavailable,
        RuntimeConfigurationInvalid,
    }

    private sealed interface RuntimeConfigurationResult {
        data class Ready(val configuration: RuntimeBootstrapConfiguration) : RuntimeConfigurationResult
        data class Error(val failure: Failure) : RuntimeConfigurationResult
    }

    private companion object {
        /** Trusted guard for the platform adapter; it cannot be changed by loaded configuration. */
        const val RuntimeConfigurationSourceTimeoutMillis = 1_000L
    }
}
