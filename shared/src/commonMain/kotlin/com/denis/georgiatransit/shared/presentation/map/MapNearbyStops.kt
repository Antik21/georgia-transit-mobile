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
import com.denis.georgiatransit.shared.domain.repository.canUseLastKnownGood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class NearbyRequest(
    val generation: Long,
    val city: TransitCity,
    val viewport: MapViewport,
    val locale: TransitLocale,
)

/** Owns the viewport request lifecycle, stop page and capability interlock. */
internal class MapNearbyStops(repository: TransitRepository, session: TransitSession) {
    private val loader = NearbyStopsLoader(repository, session)
    private var job: Job? = null
    private var generation = 0L
    private var blockedCityId: CityId? = null
    var viewport: MapViewport? = null
        private set
    var stops: List<TransitStop> = emptyList()
        private set

    fun reset() {
        job?.cancel()
        job = null
        generation++
        viewport = null
        stops = emptyList()
        blockedCityId = null
    }

    fun clearStops() {
        stops = emptyList()
    }

    fun blocked(cityId: CityId): Boolean = blockedCityId == cityId

    fun isCurrent(request: NearbyRequest, city: TransitCity?, locale: TransitLocale): Boolean =
        request.generation == generation && city?.id == request.city.id &&
            city.capabilities.stops && !blocked(request.city.id) && locale == request.locale

    fun isCurrent(generation: Long, cityId: CityId, locale: TransitLocale,
                  city: TransitCity?, currentLocale: TransitLocale): Boolean =
        generation == this.generation && city?.id == cityId && city.capabilities.stops &&
            !blocked(cityId) && locale == currentLocale

    fun start(
        scope: CoroutineScope,
        city: TransitCity,
        candidate: MapViewport,
        locale: TransitLocale,
        force: Boolean,
        currentCity: () -> TransitCity?,
        currentLocale: () -> TransitLocale,
        onLoading: (NearbyRequest) -> Unit,
        clearForRecovery: suspend (NearbyRequest) -> Boolean,
        onResult: (NearbyRequest, TransitLoadResult<List<TransitStop>>) -> Unit,
    ): NearbyRequest? {
        if (!city.capabilities.stops || blocked(city.id) ||
            !candidate.center.isMapCoordinate() || !candidate.zoom.isFinite() || candidate.radiusMeters <= 0) return null
        val normalized = with(MapViewportPolicy) { candidate.normalized() }
        if (!force && viewport == normalized) return null
        viewport = normalized
        val request = NearbyRequest(++generation, city, normalized, locale)
        job?.cancel()
        job = scope.launch {
            onLoading(request)
            delay(350L)
            if (!isCurrent(request, currentCity(), currentLocale())) return@launch
            val result = loader.loadWithRecovery(
                city = city,
                viewport = normalized,
                locale = locale,
                isCurrent = { isCurrent(request, currentCity(), currentLocale()) },
                clearStops = { clearForRecovery(request) },
                currentCity = currentCity,
            )
            if (isCurrent(request, currentCity(), currentLocale())) onResult(request, result)
        }
        return request
    }

    /** Returns whether selection and sheet data must be cleared along with this result. */
    fun accept(result: TransitLoadResult<List<TransitStop>>, cityId: CityId): Boolean = when (result) {
        is TransitLoadResult.Data -> {
            stops = result.value.take(100)
            false
        }
        is TransitLoadResult.Empty -> {
            if (result.freshness != TransitFreshness.StaleOffline) stops = emptyList()
            false
        }
        is TransitLoadResult.Failure -> when (result.error) {
            is TransitFailure.CapabilityUnavailable -> {
                generation++
                blockedCityId = cityId
                true
            }
            is TransitFailure.CityNotFound, is TransitFailure.ProviderIdChanged -> true
            else -> false
        }
    }

    fun contentState(result: TransitLoadResult<List<TransitStop>>): MapContentState = when (result) {
        is TransitLoadResult.Data -> if (result.freshness == TransitFreshness.StaleOffline) {
            MapContentState.Offline(isStale = true)
        } else MapContentState.Ready
        is TransitLoadResult.Empty -> if (result.freshness == TransitFreshness.StaleOffline) {
            MapContentState.Offline(isStale = true)
        } else MapContentState.Empty
        is TransitLoadResult.Failure -> if (result.error.canUseLastKnownGood) {
            MapContentState.RetryableError
        } else MapContentState.Unavailable
    }
}
