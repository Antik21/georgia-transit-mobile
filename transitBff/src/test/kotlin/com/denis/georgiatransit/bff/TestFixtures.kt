package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.api.Arrival
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.Direction
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyLeg
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.api.WalkingEstimate
import com.denis.georgiatransit.bff.provider.CityTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.RealtimeArrivals
import com.denis.georgiatransit.bff.provider.RealtimeVehicles
import com.denis.georgiatransit.bff.provider.WalkingQuery
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

internal val text = LocalizedText("ru", "en", "ka")
internal val direction = Direction("test:provider:direction:out", text, text)
internal val route = Route(
    id = "test:provider:route:r1",
    providerId = "r1",
    shortName = "1",
    longName = text,
    color = "#112233",
    textColor = "#FFFFFF",
    mode = "bus",
    directions = listOf(direction),
)
internal val stop = Stop(
    id = "test:provider:stop:s1",
    providerId = "s1",
    code = "1",
    name = text,
    position = GeoPoint(41.7, 44.8),
    routeIds = listOf(route.id),
    mode = "bus",
)
internal val vehicle = Vehicle(
    id = "test:provider:vehicle:v1",
    routeId = route.id,
    directionId = direction.id,
    position = stop.position,
    bearing = 10.0,
    nextStopId = stop.id,
    observedAt = "2030-01-01T00:00:00Z",
    ageSeconds = 1,
    positionKind = PositionKind.GPS,
)
internal val arrival = Arrival(
    stopId = stop.id,
    routeId = route.id,
    tripId = "trip-1",
    headsign = text,
    scheduledAt = "2030-01-01T00:05:00Z",
    expectedAt = "2030-01-01T00:04:00Z",
    expectedInMinutes = 4,
    realtime = true,
    cancelled = false,
    source = ArrivalSource.OFFICIAL_REALTIME,
)
internal val journey = Journey(
    id = "test:provider:journey:j1",
    departureAt = "2030-01-01T00:00:00Z",
    arrivalAt = "2030-01-01T00:10:00Z",
    transfers = 0,
    legs = listOf(
        JourneyLeg(
            route.id,
            direction.id,
            stop.id,
            stop.copy(id = "test:provider:stop:s2", providerId = "s2").id,
            "2030-01-01T00:00:00Z",
            "2030-01-01T00:10:00Z",
        ),
    ),
)

internal fun capabilities(
    routes: Boolean = true,
    stops: Boolean = true,
    routeGeometry: Boolean = true,
    vehiclePositions: Boolean = true,
    officialArrivals: Boolean = true,
    tripPlanning: Boolean = true,
    arrivals: Boolean = officialArrivals,
) = CityCapabilities(
    routes = routes,
    stops = stops,
    routeGeometry = routeGeometry,
    vehiclePositions = vehiclePositions,
    officialArrivals = officialArrivals,
    tripPlanning = tripPlanning,
    arrivals = arrivals,
)

internal open class FakeAdapter(
    override val city: City = City("test", text, GeoPoint(41.7, 44.8), 13.0, capabilities()),
) : CityTransitProviderAdapter {
    val routesCalls = AtomicInteger()
    val routeCalls = AtomicInteger()
    val directionStopsCalls = AtomicInteger()
    val shapeCalls = AtomicInteger()
    val stopDirectoryCalls = AtomicInteger()
    val vehiclesCalls = AtomicInteger()
    val arrivalsCalls = AtomicInteger()
    val journeysCalls = AtomicInteger()
    val walkingCalls = AtomicInteger()
    var routesResult: suspend () -> List<Route> = { listOf(route) }
    var routeResult: suspend () -> Route = { route }
    var stopDirectoryResult: suspend () -> List<Stop> = { listOf(stop) }
    var vehiclesResult: suspend () -> RealtimeVehicles = {
        RealtimeVehicles(listOf(vehicle), Instant.parse("2030-01-01T00:00:00Z"), 30, false)
    }
    var arrivalsResult: suspend () -> RealtimeArrivals = {
        RealtimeArrivals(listOf(arrival), ArrivalSource.OFFICIAL_REALTIME, Instant.parse("2030-01-01T00:00:00Z"), false)
    }
    var journeysResult: suspend () -> List<Journey> = { listOf(journey) }
    var walkingResult: suspend () -> WalkingEstimate = {
        WalkingEstimate(400.0, 300L, "2030-01-01T00:00:00Z")
    }

    override suspend fun routes(locale: String, mode: String?): List<Route> {
        routesCalls.incrementAndGet()
        return routesResult()
    }

    override suspend fun route(routeId: String, locale: String): Route {
        routeCalls.incrementAndGet()
        return routeResult()
    }

    override suspend fun directionStops(routeId: String, directionId: String, locale: String): List<Stop> {
        directionStopsCalls.incrementAndGet()
        return listOf(stop)
    }

    override suspend fun shape(routeId: String, directionId: String): Shape {
        shapeCalls.incrementAndGet()
        return Shape(precision = 5, value = "abc", updatedAt = "2030-01-01T00:00:00Z")
    }

    override suspend fun stopDirectory(locale: String): List<Stop> {
        stopDirectoryCalls.incrementAndGet()
        return stopDirectoryResult()
    }

    override suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles {
        vehiclesCalls.incrementAndGet()
        return vehiclesResult()
    }

    override suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals {
        arrivalsCalls.incrementAndGet()
        return arrivalsResult()
    }

    override suspend fun journeys(query: JourneyQuery): List<Journey> {
        journeysCalls.incrementAndGet()
        return journeysResult()
    }

    override suspend fun walkingEstimate(query: WalkingQuery): WalkingEstimate {
        walkingCalls.incrementAndGet()
        return walkingResult()
    }
}
