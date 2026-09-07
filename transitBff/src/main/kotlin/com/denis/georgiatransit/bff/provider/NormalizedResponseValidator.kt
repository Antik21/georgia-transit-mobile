package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.Arrival
import com.denis.georgiatransit.bff.api.ArrivalPage
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.api.Direction
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.api.VehiclePage
import java.time.Instant

private const val MaximumOpaqueValueLength = 256
private const val MaximumRealtimeAgeSeconds = 86_400
private val cityIdPattern = Regex("[a-z][a-z0-9-]{1,31}")
private val publicIdPattern = Regex("([^:\\s]+):([^:\\s]+):([^:\\s]+):([^:\\s]+)")
private val modeValues = setOf("bus", "metro", "tram", "ferry")
private val colorPattern = Regex("#[0-9A-Fa-f]{6}")

/**
 * Rejects malformed normalized data before it can enter a cache or an HTTP response. The message
 * is intentionally generic because adapter output is untrusted provider data.
 */
object NormalizedResponseValidator {
    fun city(city: City) {
        if (!cityIdPattern.matches(city.id)) invalid()
        localized(city.name)
        point(city.center)
        finiteBetween(city.defaultZoom, 0.0, 22.0)
        when (city.availability.readiness) {
            CityReadiness.DEVELOPMENT_FIXTURE -> {
                if (city.availability.source != CitySource.FIXTURE) invalid()
            }
            CityReadiness.PRODUCTION_READY -> {
                if (city.availability.source != CitySource.REVIEWED_ADAPTER) invalid()
            }
            CityReadiness.UNREVIEWED -> {
                if (city.availability.source != CitySource.UNREVIEWED_ADAPTER) invalid()
            }
        }
    }

    fun cities(cities: List<City>) {
        cities.forEach(::city)
        if (cities.map(City::id).distinct().size != cities.size) invalid()
    }

    fun routes(city: City, routes: List<Route>) {
        routes.forEach { route(city.id, it) }
        if (routes.map(Route::id).distinct().size != routes.size) invalid()
    }

    fun route(cityId: String, route: Route, expectedRouteId: String? = null) {
        val routeId = entityId(route.id, cityId, "route")
        if (expectedRouteId != null && route.id != expectedRouteId) invalid()
        opaque(route.providerId)
        if (route.providerId != routeId.suffix || route.shortName.isBlank() || !colorPattern.matches(route.color) ||
            !colorPattern.matches(route.textColor) || route.mode !in modeValues
        ) {
            invalid()
        }
        localized(route.longName)
        route.directions.forEach { direction(cityId, routeId.provider, it) }
        if (route.directions.map(Direction::id).distinct().size != route.directions.size) invalid()
    }

    fun directionStops(cityId: String, route: Route, directionId: String, stops: List<Stop>) {
        val routeId = entityId(route.id, cityId, "route")
        directionForRoute(cityId, route, directionId)
        stops.forEach { stop(cityId, it, route.id, routeId.provider) }
        if (stops.map(Stop::id).distinct().size != stops.size) invalid()
    }

    fun shape(cityId: String, route: Route, directionId: String, shape: Shape) {
        directionForRoute(cityId, route, directionId)
        if (shape.format != "encoded_polyline" || shape.precision !in 0..8 || shape.value.isBlank()) invalid()
        timestamp(shape.updatedAt)
    }

    fun stops(city: City, stops: List<Stop>) {
        stops.forEach { stop(city.id, it) }
        if (stops.map(Stop::id).distinct().size != stops.size) invalid()
    }

    fun vehiclePage(
        cityId: String,
        route: Route,
        directionId: String?,
        page: VehiclePage,
    ) {
        val requestedRoute = entityId(route.id, cityId, "route")
        directionId?.let { directionForRoute(cityId, route, it) }
        timestamp(page.observedAt)
        if (page.maxAgeSeconds !in 0..MaximumRealtimeAgeSeconds) invalid()
        page.items.forEach { vehicle(cityId, requestedRoute.provider, route.id, directionId, it) }
    }

    fun arrivalPage(cityId: String, stopId: String, page: ArrivalPage) {
        val stop = entityId(stopId, cityId, "stop")
        timestamp(page.observedAt)
        arrivalSource(page.source)
        page.items.forEach { arrival(cityId, stop.provider, stopId, it) }
    }

    fun journeyPage(cityId: String, page: JourneyPage) {
        timestamp(page.observedAt)
        page.items.forEach { journey(cityId, it) }
    }

    private fun stop(cityId: String, stop: Stop, requiredRouteId: String? = null, provider: String? = null) {
        val stopId = entityId(stop.id, cityId, "stop")
        opaque(stop.providerId)
        if (stop.providerId != stopId.suffix || stop.code.isBlank() || stop.mode !in modeValues ||
            (provider != null && stopId.provider != provider)
        ) {
            invalid()
        }
        localized(stop.name)
        point(stop.position)
        if (stop.routeIds.isEmpty() || stop.routeIds.distinct().size != stop.routeIds.size) invalid()
        stop.routeIds.forEach { routeId ->
            val route = entityId(routeId, cityId, "route")
            if (route.provider != stopId.provider) invalid()
        }
        if (requiredRouteId != null && requiredRouteId !in stop.routeIds) invalid()
    }

    private fun vehicle(
        cityId: String,
        provider: String,
        requestedRouteId: String,
        requestedDirectionId: String?,
        vehicle: Vehicle,
    ) {
        val vehicleId = entityId(vehicle.id, cityId, "vehicle")
        val route = entityId(vehicle.routeId, cityId, "route")
        if (vehicleId.provider != provider || route.provider != provider || vehicle.routeId != requestedRouteId) invalid()
        vehicle.directionId?.let { direction(cityId, provider, it) }
        if (requestedDirectionId != null && vehicle.directionId != requestedDirectionId) invalid()
        vehicle.nextStopId?.let {
            if (entityId(it, cityId, "stop").provider != provider) invalid()
        }
        point(vehicle.position)
        vehicle.bearing?.let { finiteBetween(it, 0.0, 360.0) }
        vehicle.observedAt?.let(::timestamp)
        vehicle.ageSeconds?.let { if (it !in 0..MaximumRealtimeAgeSeconds) invalid() }
        positionKind(vehicle.positionKind)
    }

    private fun arrival(cityId: String, provider: String, requestedStopId: String, arrival: Arrival) {
        val stop = entityId(arrival.stopId, cityId, "stop")
        val route = entityId(arrival.routeId, cityId, "route")
        if (stop.provider != provider || route.provider != provider || arrival.stopId != requestedStopId) invalid()
        arrival.tripId?.let(::opaque)
        localized(arrival.headsign)
        arrival.scheduledAt?.let(::timestamp)
        arrival.expectedAt?.let(::timestamp)
        arrival.expectedInMinutes?.let { if (it !in 0..1_440) invalid() }
        arrivalSource(arrival.source)
    }

    private fun journey(cityId: String, journey: Journey) {
        val journeyId = entityId(journey.id, cityId, "journey")
        val departure = timestamp(journey.departureAt)
        val arrival = timestamp(journey.arrivalAt)
        if (journey.transfers !in 0..6 || arrival.isBefore(departure) || journey.legs.isEmpty()) invalid()
        journey.legs.forEach { leg ->
            val route = entityId(leg.routeId, cityId, "route")
            if (route.provider != journeyId.provider) invalid()
            direction(cityId, route.provider, leg.directionId)
            if (entityId(leg.fromStopId, cityId, "stop").provider != route.provider ||
                entityId(leg.toStopId, cityId, "stop").provider != route.provider
            ) {
                invalid()
            }
            val legDeparture = timestamp(leg.departureAt)
            val legArrival = timestamp(leg.arrivalAt)
            if (legArrival.isBefore(legDeparture) || legDeparture.isBefore(departure) || legArrival.isAfter(arrival)) invalid()
        }
    }

    private fun direction(cityId: String, provider: String, direction: Direction) {
        direction(cityId, provider, direction.id)
        localized(direction.name)
        localized(direction.headsign)
    }

    private fun direction(cityId: String, provider: String, directionId: String) {
        if (entityId(directionId, cityId, "direction").provider != provider) invalid()
    }

    private fun directionForRoute(cityId: String, route: Route, directionId: String) {
        val routeId = entityId(route.id, cityId, "route")
        if (route.directions.none { it.id == directionId }) invalid()
        direction(cityId, routeId.provider, directionId)
    }

    private fun localized(value: LocalizedText) {
        if (value.ru.isBlank() || value.en.isBlank() || value.ka.isBlank()) invalid()
    }

    private fun positionKind(value: PositionKind) {
        when (value) {
            PositionKind.GPS,
            PositionKind.ESTIMATED,
            PositionKind.UNKNOWN,
            -> Unit
        }
    }

    private fun arrivalSource(value: ArrivalSource) {
        when (value) {
            ArrivalSource.OFFICIAL_REALTIME,
            ArrivalSource.AGGREGATOR_REALTIME,
            ArrivalSource.SCHEDULE,
            ArrivalSource.CLIENT_ESTIMATE,
            -> Unit
        }
    }

    private fun point(point: GeoPoint) {
        finiteBetween(point.latitude, -90.0, 90.0)
        finiteBetween(point.longitude, -180.0, 180.0)
    }

    private fun finiteBetween(value: Double, minimum: Double, maximum: Double) {
        if (!value.isFinite() || value !in minimum..maximum) invalid()
    }

    private fun timestamp(value: String): Instant {
        if (value.isBlank() || !value.endsWith("Z")) invalid()
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun opaque(value: String) {
        if (value.isBlank() || value.length > MaximumOpaqueValueLength || value.any(Char::isWhitespace)) invalid()
    }

    private fun entityId(value: String, cityId: String, entity: String): PublicId {
        val match = publicIdPattern.matchEntire(value) ?: invalid()
        val (idCity, provider, idEntity, suffix) = match.destructured
        if (value.length > MaximumOpaqueValueLength || idCity != cityId || idEntity != entity) invalid()
        return PublicId(idCity, provider, idEntity, suffix)
    }

    private fun invalid(): Nothing =
        throw ProviderBadGateway("The provider returned an invalid normalized response")

    private data class PublicId(
        val city: String,
        val provider: String,
        val entity: String,
        val suffix: String,
    )
}
