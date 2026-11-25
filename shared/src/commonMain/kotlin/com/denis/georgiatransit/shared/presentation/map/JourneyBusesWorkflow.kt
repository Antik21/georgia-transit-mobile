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
import kotlin.time.Instant

/** What the soonest buses are looked up for; any change restarts the lookup. */
internal data class JourneyBusKey(
    val cityId: CityId,
    val optionId: JourneyId,
    val boarding: JourneyBoarding,
    val locale: TransitLocale,
    val positions: Boolean,
    val shapes: Boolean = false,
    val arrivals: Boolean = true,
)

internal data class JourneyBusesUpdate(
    val key: JourneyBusKey,
    val generation: Long,
    val soonest: List<Pair<VehicleId, Int>>,
    val positions: Map<VehicleId, TransitVehicle>,
    val headings: Map<VehicleId, Double> = emptyMap(),
    val passedAt: Instant? = null,
)

internal object JourneyBusesProjector {
    private const val MAX_INTERVAL_MINUTES = 45

    /** The first bus is the one in the plan; a later bus at the same stop and route may follow it. */
    fun soonest(arrivals: List<TransitArrival>, boarding: JourneyBoarding): List<Pair<VehicleId, Int>> {
        val plannedVehicleId = boarding.plannedVehicleId ?: return emptyList()
        val candidates = arrivals.asSequence()
            .filter { it.stopId == boarding.stopId && it.routeId == boarding.routeId && !it.cancelled }
            .mapNotNull { arrival ->
                val id = arrival.vehicleId ?: return@mapNotNull null
                val minutes = arrival.expectedInMinutes?.takeIf { it >= 0 } ?: return@mapNotNull null
                id to minutes
            }
            .sortedBy { it.second }
            .distinctBy { it.first }
            .toList()
        val first = candidates.firstOrNull { it.first == plannedVehicleId } ?: return emptyList()
        val second = candidates.firstOrNull { (id, minutes) ->
            id != plannedVehicleId && minutes >= first.second && minutes - first.second <= MAX_INTERVAL_MINUTES
        }
        return listOfNotNull(first, second)
    }

    fun project(option: JourneyOptionUi, update: JourneyBusesUpdate): JourneyBusesUi {
        val route = option.legs.filterIsInstance<JourneyLegUi.Ride>().first { it.route.routeId == option.boarding?.routeId }.route
        return JourneyBusesUi(
            optionId = option.id,
            boarding = update.key.boarding,
            etaMinutes = update.soonest.map { it.second },
            vehicles = update.soonest.mapNotNull { (id, eta) ->
                update.positions[id]?.let { vehicle ->
                    JourneyBusUi(id, vehicle.position, update.headings[id], eta, route, vehicle.positionKind)
                }
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
    private val clock: VehicleRealtimeClock = SystemVehicleRealtimeClock(),
    private val frameIntervalMillis: Long = DefaultVehicleRealtimeTickerPolicy.frameIntervalMillis,
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
            var displayedVehicle: TransitVehicle? = null
            var passedAt: Instant? = null
            fun publish() {
                if (generation == this@JourneyBusesWorkflow.generation) {
                    val bus = displayedVehicle
                    val positions = bus?.let { mapOf(it.id to it) }.orEmpty()
                    val headings = bus?.bearing?.let { mapOf(bus.id to it) }.orEmpty()
                    onUpdate(JourneyBusesUpdate(key, generation, soonest, positions, headings, passedAt))
                }
            }
            if (key.arrivals) launch {
                while (true) {
                    val page = guarded { repository.arrivals(key.cityId, key.boarding.stopId, ARRIVALS_LIMIT, key.locale) }
                    // A stale or failed response cannot support a current countdown.
                    soonest = if (page is TransitLoadResult.Data && !page.value.stale && page.freshness != TransitFreshness.StaleOffline) {
                        JourneyBusesProjector.soonest(page.value.items, key.boarding)
                    } else emptyList()
                    publish()
                    delay(arrivalsIntervalMillis)
                }
            }
            if (key.positions) launch {
                var path = routePath(key)
                val motion = JourneyBoardingBusMotion(key.boarding, path, clock)
                val passage = JourneyBoardingPassage(key.boarding)
                var animation: Job? = null
                fun renderFrame() {
                    val frame = motion.frame()
                    if (frame == displayedVehicle) return
                    displayedVehicle = frame
                    if (soonest.isNotEmpty()) publish()
                }
                while (true) {
                    val page = guarded { repository.vehicles(key.cityId, key.boarding.routeId) }
                    if (path == null && key.shapes) path = routePath(key)
                    if (page is TransitLoadResult.Data && page.freshness != TransitFreshness.StaleOffline &&
                        path?.let { passage.observe(page.value, it, clock.wallNow().toEpochMilliseconds()) } == true) {
                        passedAt = page.value.items.first { it.id == key.boarding.plannedVehicleId }.observedAt
                        publish()
                        break
                    }
                    animation?.cancel()
                    motion.accept(page)
                    renderFrame()
                    if (motion.isMoving()) animation = launch {
                        while (motion.isMoving()) {
                            delay(frameIntervalMillis)
                            renderFrame()
                        }
                    }
                    delay(vehicleIntervalMillis)
                }
            }
        }

    private suspend fun routePath(key: JourneyBusKey): PreparedVehicleRoutePath? {
        val directionId = key.boarding.directionId?.takeIf { key.shapes } ?: return null
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
