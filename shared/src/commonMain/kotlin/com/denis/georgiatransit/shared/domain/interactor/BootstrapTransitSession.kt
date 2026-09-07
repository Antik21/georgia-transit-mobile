package com.denis.georgiatransit.shared.domain.interactor

import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfiguration
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class BootstrapTransitSession(
    private val repository: TransitRepository,
    private val session: TransitSession,
    private val selectedCityStore: SelectedCityStore,
    private val runtimeConfigurationSource: RuntimeBootstrapConfigurationSource,
) {
    suspend fun bootstrap(): Result {
        val runtimeConfiguration = when (val result = loadRuntimeConfiguration()) {
            is RuntimeConfigurationResult.Ready -> result.configuration
            is RuntimeConfigurationResult.Error -> {
                session.clearSelectedCity()
                return Result.Error(result.failure)
            }
        }
        val cachedCity = readValidCachedCity(runtimeConfiguration)

        return try {
            val currentCities = withTimeout(runtimeConfiguration.bootstrapTimeoutMillis) {
                repository.loadCityCapabilitySnapshot()
            }
            val currentCity = cachedCity?.let { cached ->
                currentCities.firstOrNull { it.id == cached.id && it.isMapEligible() }
            }

            if (currentCity != null) {
                session.selectCity(currentCity)
                Result.OpenMap
            } else {
                clearInvalidSelection()
                Result.OpenCitySelection
            }
        } catch (_: TimeoutCancellationException) {
            restoreOfflineOrError(cachedCity, Failure.TimedOut)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            restoreOfflineOrError(cachedCity, Failure.Unavailable)
        }
    }

    private fun restoreOfflineOrError(cachedCity: TransitCity?, failure: Failure): Result {
        if (cachedCity != null) {
            session.selectCity(cachedCity)
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
        RuntimeConfigurationResult.Error(Failure.RuntimeConfigurationUnavailable)
    }

    private fun readValidCachedCity(configuration: RuntimeBootstrapConfiguration): TransitCity? {
        val cachedSnapshot = try {
            selectedCityStore.read()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            clearPersistedSelection()
            null
        }

        return cachedSnapshot
            ?.takeIf { it.schemaVersion in configuration.compatibleCachedCitySchemaVersions }
            ?.city
            ?.takeIf { it.isMapEligible() }
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
