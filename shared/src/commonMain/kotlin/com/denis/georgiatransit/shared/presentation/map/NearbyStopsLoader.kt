package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import kotlinx.coroutines.CancellationException

/** Performs a nearby request and its one-time catalog recovery without owning Orbit state. */
internal class NearbyStopsLoader(
    private val repository: TransitRepository,
    private val session: TransitSession,
) {
    private suspend fun requestNearby(
        cityId: CityId,
        viewport: MapViewport,
        locale: TransitLocale,
    ): TransitLoadResult<List<TransitStop>> = try {
        repository.nearbyStops(
            cityId = cityId,
            center = viewport.center,
            radiusMeters = viewport.radiusMeters,
            limit = 100,
            locale = locale,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        TransitLoadResult.Failure(TransitFailure.Transport("Nearby stops request failed"))
    }

    suspend fun loadWithRecovery(
        city: TransitCity,
        viewport: MapViewport,
        locale: TransitLocale,
        isCurrent: () -> Boolean,
        clearStops: suspend () -> Boolean,
        currentCity: () -> TransitCity?,
    ): TransitLoadResult<List<TransitStop>> {
        val firstResult = requestNearby(city.id, viewport, locale)
        return recoverInvalidatedQueryOnce(firstResult, city, viewport, locale, isCurrent, clearStops, currentCity)
    }

    private suspend fun recoverInvalidatedQueryOnce(
        firstResult: TransitLoadResult<List<TransitStop>>,
        city: TransitCity,
        viewport: MapViewport,
        locale: TransitLocale,
        isCurrent: () -> Boolean,
        clearStops: suspend () -> Boolean,
        currentCity: () -> TransitCity?,
    ): TransitLoadResult<List<TransitStop>> {
        val failure = (firstResult as? TransitLoadResult.Failure)?.error ?: return firstResult
        return when (failure) {
            is TransitFailure.CityNotFound -> recoverCityNotFound(city, viewport, locale, isCurrent, clearStops, currentCity, failure)
            is TransitFailure.ProviderIdChanged -> {
                if (!clearStops()) return firstResult
                requestNearby(city.id, viewport, locale)
            }
            else -> firstResult
        }
    }

    private suspend fun recoverCityNotFound(
        city: TransitCity,
        viewport: MapViewport,
        locale: TransitLocale,
        isCurrent: () -> Boolean,
        clearStops: suspend () -> Boolean,
        currentCity: () -> TransitCity?,
        originalFailure: TransitFailure.CityNotFound,
    ): TransitLoadResult<List<TransitStop>> {
        if (!clearStops()) {
            return TransitLoadResult.Failure(originalFailure)
        }
        val catalogResult = try {
            repository.revalidateCityCapabilities()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            TransitLoadResult.Failure(TransitFailure.Transport("City catalog revalidation failed"))
        }
        if (!isCurrent()) return TransitLoadResult.Failure(originalFailure)

        val refreshedCities = when (catalogResult) {
            is TransitLoadResult.Data -> {
                if (catalogResult.freshness == TransitFreshness.StaleOffline) {
                    return TransitLoadResult.Failure(
                        catalogResult.revalidationFailure
                            ?: TransitFailure.Transport("City catalog revalidation failed"),
                    )
                }
                catalogResult.value
            }
            is TransitLoadResult.Empty -> {
                if (catalogResult.freshness == TransitFreshness.StaleOffline) {
                    return TransitLoadResult.Failure(
                        catalogResult.revalidationFailure
                            ?: TransitFailure.Transport("City catalog revalidation failed"),
                    )
                }
                emptyList()
            }
            is TransitLoadResult.Failure -> return TransitLoadResult.Failure(catalogResult.error)
        }
        val refreshedCity = refreshedCities.firstOrNull { it.id == city.id }
        if (refreshedCity == null) {
            session.clearSelectedCity()
            return TransitLoadResult.Failure(originalFailure)
        }
        if (refreshedCity != currentCity()) session.selectCity(refreshedCity)
        if (!refreshedCity.capabilities.stops) {
            return TransitLoadResult.Failure(
                TransitFailure.CapabilityUnavailable(
                    message = "Nearby stops are disabled for the selected city",
                    requestId = originalFailure.requestId,
                ),
            )
        }
        if (!isCurrent()) return TransitLoadResult.Failure(originalFailure)
        return requestNearby(city.id, viewport, locale)
    }

}
