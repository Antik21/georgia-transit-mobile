package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.Arrival
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.AttributionLink
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.RouteSchedule
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.TransitModeValues
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.cache.BoundedKeyedTtlCache
import com.denis.georgiatransit.bff.geo.decodePolyline
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.provider.CityTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.ProviderCapabilityUnavailable
import com.denis.georgiatransit.bff.provider.RealtimeArrivals
import com.denis.georgiatransit.bff.provider.RealtimeVehicles
import java.time.Clock
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

internal class KutaisiAzryTransitProviderAdapter(
    private val feed: AzryFeed,
    private val clock: Clock = Clock.systemUTC(),
) : CityTransitProviderAdapter, AutoCloseable {
    override val telemetryProvider = TelemetryProvider.KUTAISI_AZRY
    override val city = City(
        id = KnownCityIds.Kutaisi,
        name = LocalizedText(ru = "Кутаиси", en = "Kutaisi", ka = "ქუთაისი"),
        center = GeoPoint(42.271608, 42.705448),
        defaultZoom = 15.25,
        capabilities = CityCapabilities(
            routes = true,
            stops = true,
            routeGeometry = true,
            vehiclePositions = true,
            officialArrivals = true,
            tripPlanning = false,
            schedules = true,
        ),
        availability = CityAvailability(CityReadiness.PRODUCTION_READY, CitySource.REVIEWED_ADAPTER),
        attribution = listOf(AttributionLink("azry", azryText("Kutaisi Municipal Transport · AZRY"), "https://kutaisi-bus.msplus.ge/")),
    )

    private val catalog = AzryCatalog(feed)
    private val schedule = AzryTimetables(feed)
    private val livePositions = BoundedKeyedTtlCache<String, RealtimeVehicles>(10.seconds, 100, clock)

    override suspend fun routes(locale: String, mode: String?) =
        if (mode == null || mode == TransitModeValues.Bus) catalog.routes(azryLocale(locale)) else emptyList()

    override suspend fun route(routeId: String, locale: String) = catalog.route(routeId, azryLocale(locale))

    override suspend fun stopDirectory(locale: String) = catalog.stops(azryLocale(locale))

    override suspend fun directionStops(routeId: String, directionId: String, locale: String) =
        azryLocale(locale).let { language ->
            val route = catalog.rawRoute(routeId, language)
            val pattern = route.requirePattern(directionId)
            feed.directionStops(route.id, pattern.patternSuffix, language)
                .map { it.normalized(listOf(routeId)) }
                .distinctBy { it.id }
        }

    override suspend fun shape(routeId: String, directionId: String): Shape {
        val route = catalog.rawRoute(routeId, "en")
        val suffix = route.requirePattern(directionId).patternSuffix
        val value = feed.polylines(route.id, listOf(suffix))[suffix]?.encodedValue?.takeIf(String::isNotBlank)
            ?: invalidAzry()
        if (value.length > 250_000 || value.any { it.code !in 63..126 }) invalidAzry()
        val points = try { decodePolyline(value) } catch (_: IllegalArgumentException) { invalidAzry() }
        if (points.size < 2 || points.any { it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 }) invalidAzry()
        return Shape(precision = 5, value = value, updatedAt = clock.instant().toString())
    }

    override suspend fun schedule(routeId: String, directionId: String, date: LocalDate, locale: String): RouteSchedule {
        val language = azryLocale(locale)
        val route = catalog.rawRoute(routeId, language)
        val pattern = route.requirePattern(directionId)
        return schedule.forDate(route, pattern, date, language)
    }

    override suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles {
        val route = catalog.rawRoute(routeId, "en")
        directionId?.let { route.requirePattern(it) }
        val page = livePositions.getOrLoad(routeId) { fetchPositions(routeId, route) }
        return if (directionId == null) page else page.copy(items = page.items.filter { it.directionId == directionId })
    }

    private suspend fun fetchPositions(routeId: String, route: AzryRoute): RealtimeVehicles {
        val suffixes = route.patterns.map(AzryPattern::patternSuffix)
        val raw = feed.positions(route.id, suffixes)
        val receivedAt = clock.instant()
        val rows = suffixes.flatMap { suffix ->
            raw[suffix].orEmpty().map { row ->
                Vehicle(
                    id = azryPublicId(PublicEntityType.Vehicle, row.vehicleId.content),
                    routeId = routeId,
                    directionId = azryDirectionId(route.id, suffix),
                    position = GeoPoint(row.lat, row.lon),
                    bearing = row.heading?.takeIf { it >= 0.0 && it < 360.0 },
                    nextStopId = row.nextStopId?.takeIf(String::isNotBlank)?.let { azryPublicId(PublicEntityType.Stop, it) },
                    positionKind = PositionKind.GPS,
                )
            }
        }
        // A feed may briefly advertise a bus in both variants during a turnaround. Avoid a
        // directional assignment until it appears in exactly one variant.
        val unique = rows.groupBy(Vehicle::id).values.mapNotNull { matches ->
            matches.distinct().singleOrNull()
        }
        return RealtimeVehicles(unique, receivedAt, maxAgeSeconds = 30, stale = false)
    }

    override suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals {
        val language = azryLocale(locale)
        val stop = catalog.stop(stopId, language)
        val routesAtStop = catalog.servingRoutes(stop.id, language).groupBy(AzryRoute::shortName)
        val board = feed.arrivals(stop.id, language, limit)
        val receivedAt = clock.instant()
        val items = board.mapNotNull { row ->
            if (row.vehicleMode != "BUS") return@mapNotNull null
            val candidates = routesAtStop[row.shortName].orEmpty()
            val route = candidates.singleOrNull() ?: candidates.filter { candidate ->
                catalog.rawRoute(azryPublicId(PublicEntityType.Route, candidate.id), language)
                    .patterns.any { it.patternSuffix == row.patternSuffix }
            }.singleOrNull() ?: return@mapNotNull null
            val predicted = row.realtimeArrivalMinutes?.takeIf { row.realtime == true && it in 0..1_440 }
            val planned = row.scheduledArrivalMinutes?.takeIf { it in 0..1_440 }
            if (predicted == null && planned == null) return@mapNotNull null
            Arrival(
                stopId = stopId,
                routeId = azryPublicId(PublicEntityType.Route, route.id),
                headsign = azryText(row.headsign.orEmpty().ifBlank { route.longName.orEmpty().ifBlank { route.shortName } }),
                scheduledAt = planned?.let { receivedAt.plusSeconds(it * 60L).toString() },
                expectedAt = predicted?.let { receivedAt.plusSeconds(it * 60L).toString() },
                expectedInMinutes = predicted,
                realtime = predicted != null,
                cancelled = false,
                source = if (predicted != null) ArrivalSource.OFFICIAL_REALTIME else ArrivalSource.SCHEDULE,
            )
        }.take(limit)
        val source = if (items.any(Arrival::realtime)) ArrivalSource.OFFICIAL_REALTIME else ArrivalSource.SCHEDULE
        return RealtimeArrivals(items, source, receivedAt, stale = false)
    }

    override suspend fun journeys(query: JourneyQuery): Nothing =
        throw ProviderCapabilityUnavailable("Journey planning is not available")

    override fun close() {
        catalog.close()
        schedule.close()
        livePositions.close()
        feed.close()
    }
}
