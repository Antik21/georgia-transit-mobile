package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.TransitArrival
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the soonest buses are looked up for; any change restarts the lookup. */
internal data class JourneyBusKey(
    val cityId: CityId,
    val optionId: JourneyId,
    val boarding: JourneyBoarding,
    val locale: TransitLocale,
    val positions: Boolean,
    val shapes: Boolean = false,
)

internal data class JourneyBusesUpdate(
    val key: JourneyBusKey,
    val generation: Long,
    val soonest: List<Pair<VehicleId, Int>>,
    val positions: Map<VehicleId, TransitVehicle>,
    val headings: Map<VehicleId, Double> = emptyMap(),
)

internal object JourneyBusesProjector {
    const val COUNT = 2

    /** The two vehicles that reach the boarding stop first on the leg's route, soonest first. */
    fun soonest(arrivals: List<TransitArrival>, boarding: JourneyBoarding): List<Pair<VehicleId, Int>> = arrivals
        .filter { it.routeId == boarding.routeId && !it.cancelled }
        .mapNotNull { arrival -> arrival.vehicleId?.let { id -> arrival.expectedInMinutes?.let { id to it } } }
        .distinctBy { it.first }
        .sortedBy { it.second }
        .take(COUNT)

    fun project(option: JourneyOptionUi, update: JourneyBusesUpdate): JourneyBusesUi {
        val route = option.legs.filterIsInstance<JourneyLegUi.Ride>().first { it.route.routeId == option.boarding?.routeId }.route
        return JourneyBusesUi(
            optionId = option.id,
            boarding = update.key.boarding,
            etaMinutes = update.soonest.map { it.second },
            vehicles = update.soonest.mapNotNull { (id, eta) ->
                update.positions[id]?.let { vehicle -> JourneyBusUi(id, vehicle.position, update.headings[id], eta, route) }
            },
        )
    }
}

/**
 * Sole owner of the selected option's nearest-bus polling: arrivals at the stop-arrivals cadence,
 * positions at the vehicle cadence. Latest wins; nothing polls without a key.
 */
internal class JourneyBusesWorkflow(
    private val repository: TransitRepository,
    private val vehicleIntervalMillis: Long,
    private val arrivalsIntervalMillis: Long = JOURNEY_ARRIVALS_INTERVAL_MILLIS,
) {
    private var key: JourneyBusKey? = null
    private var generation = 0L
    private var job: Job? = null
    // Every minute's re-plan mints new option IDs and restarts polling; the route shape itself is unchanged.
    private var cachedPath: Pair<JourneyBoarding, PreparedVehicleRoutePath>? = null

    fun isCurrent(update: JourneyBusesUpdate): Boolean = update.generation == generation && update.key == key

    fun sync(scope: CoroutineScope, next: JourneyBusKey?, onUpdate: (JourneyBusesUpdate) -> Unit) {
        if (next == key) return
        key = next
        generation++
        job?.cancel()
        job = next?.let { start(scope, it, generation, onUpdate) }
    }

    private fun start(scope: CoroutineScope, key: JourneyBusKey, generation: Long, onUpdate: (JourneyBusesUpdate) -> Unit) =
        scope.launch {
            var soonest = emptyList<Pair<VehicleId, Int>>()
            var positions = emptyMap<VehicleId, TransitVehicle>()
            var headings = emptyMap<VehicleId, Double>()
            val headingSource = JourneyBusHeadings(routePath(key), key.boarding.stopPosition, key.boarding.departureBearing)
            suspend fun refreshPositions() {
                if (!key.positions || soonest.isEmpty()) return
                val page = guarded { repository.vehicles(key.cityId, key.boarding.routeId) }
                if (page is TransitLoadResult.Data) {
                    positions = page.value.items.associateBy { it.id }
                    headings = headingSource.update(positions.values)
                }
            }
            fun publish() {
                if (generation == this@JourneyBusesWorkflow.generation) {
                    onUpdate(JourneyBusesUpdate(key, generation, soonest, positions, headings))
                }
            }
            launch {
                while (true) {
                    val page = guarded { repository.arrivals(key.cityId, key.boarding.stopId, ARRIVALS_LIMIT, key.locale) }
                    // A failed poll keeps the last known buses; there is no error state for this hint.
                    if (page is TransitLoadResult.Data) soonest = JourneyBusesProjector.soonest(page.value.items, key.boarding)
                    refreshPositions()
                    publish()
                    delay(arrivalsIntervalMillis)
                }
            }
            launch {
                while (true) {
                    delay(vehicleIntervalMillis)
                    refreshPositions()
                    publish()
                }
            }
        }

    private suspend fun routePath(key: JourneyBusKey): PreparedVehicleRoutePath? {
        val directionId = key.boarding.directionId?.takeIf { key.positions && key.shapes } ?: return null
        cachedPath?.takeIf { (boarding) -> boarding.routeId == key.boarding.routeId && boarding.directionId == directionId }
            ?.let { return it.second }
        val shape = guarded { repository.directionShape(key.cityId, key.boarding.routeId, directionId) }
        val points = ((shape as? TransitLoadResult.Data)?.value?.let(::decodeEncodedPolyline) as? EncodedPolylineDecodeResult.Success)
            ?.points ?: return null
        return PreparedVehicleRoutePath.from(
            MapPolyline(key.boarding.routeId, directionId, points, routeColorArgb = 0, TransitFreshness.Network),
        )?.also { cachedPath = key.boarding to it }
    }

    private suspend fun <T> guarded(block: suspend () -> TransitLoadResult<T>): TransitLoadResult<T>? = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val ARRIVALS_LIMIT = 30
    }
}

/** The stop-arrivals sheet's cadence (StopArrivalsPoller). */
internal const val JOURNEY_ARRIVALS_INTERVAL_MILLIS = 20_000L
