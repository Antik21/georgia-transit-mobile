package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.ArrivalPage
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.VehiclePage
import com.denis.georgiatransit.bff.FakeAdapter
import com.denis.georgiatransit.bff.arrival
import com.denis.georgiatransit.bff.direction
import com.denis.georgiatransit.bff.journey
import com.denis.georgiatransit.bff.route
import com.denis.georgiatransit.bff.stop
import com.denis.georgiatransit.bff.text
import com.denis.georgiatransit.bff.vehicle
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NormalizedResponseValidatorTest {
    private val city: City = FakeAdapter().city
    private val validShape = Shape(precision = 5, value = "encoded", updatedAt = "2030-01-01T00:00:00Z")
    private val validVehicles = VehiclePage(listOf(vehicle), "2030-01-01T00:00:00Z", 30, false)
    private val validArrivals = ArrivalPage(listOf(arrival), ArrivalSource.OFFICIAL_REALTIME, "2030-01-01T00:00:00Z", false)
    private val validJourneys = JourneyPage(listOf(journey), "2030-01-01T00:00:00Z")

    @Test
    fun `all valid normalized payloads pass`() {
        NormalizedResponseValidator.city(city)
        NormalizedResponseValidator.cities(listOf(city))
        NormalizedResponseValidator.routes(city, listOf(route))
        NormalizedResponseValidator.route("test", route, route.id)
        NormalizedResponseValidator.directionStops("test", route, direction.id, listOf(stop))
        NormalizedResponseValidator.stops(city, listOf(stop))
        NormalizedResponseValidator.shape("test", route, direction.id, validShape)
        NormalizedResponseValidator.vehiclePage("test", route, direction.id, validVehicles)
        NormalizedResponseValidator.arrivalPage("test", stop.id, validArrivals)
        NormalizedResponseValidator.journeyPage("test", validJourneys)
    }

    @Test
    fun `cities reject malformed identity localization WGS84 numerics and duplicates`() {
        assertInvalid(
            { NormalizedResponseValidator.city(city.copy(id = "T")) },
            { NormalizedResponseValidator.city(city.copy(name = text.copy(ka = ""))) },
            { NormalizedResponseValidator.city(city.copy(center = GeoPoint(Double.NaN, 0.0))) },
            { NormalizedResponseValidator.city(city.copy(center = GeoPoint(91.0, 0.0))) },
            { NormalizedResponseValidator.city(city.copy(defaultZoom = 22.1)) },
            { NormalizedResponseValidator.cities(listOf(city, city)) },
        )
    }

    @Test
    fun `routes reject malformed and cross-provider identifiers fields enums colors and relationships`() {
        assertInvalid(
            { NormalizedResponseValidator.route("test", route.copy(id = "three:segments:only")) },
            { NormalizedResponseValidator.route("test", route.copy(id = "other:provider:route:r1")) },
            { NormalizedResponseValidator.route("test", route.copy(providerId = "wrong")) },
            { NormalizedResponseValidator.route("test", route.copy(shortName = "")) },
            { NormalizedResponseValidator.route("test", route.copy(color = "112233")) },
            { NormalizedResponseValidator.route("test", route.copy(textColor = "#GGGGGG")) },
            { NormalizedResponseValidator.route("test", route.copy(mode = "train")) },
            {
                NormalizedResponseValidator.route(
                    "test",
                    route.copy(directions = listOf(direction.copy(id = "test:other:direction:out"))),
                )
            },
            { NormalizedResponseValidator.route("test", route.copy(directions = listOf(direction, direction))) },
            { NormalizedResponseValidator.route("test", route, "test:provider:route:other") },
        )
    }

    @Test
    fun `stops reject invalid references providers WGS84 modes and duplicates`() {
        assertInvalid(
            { NormalizedResponseValidator.stops(city, listOf(stop.copy(id = "test:provider:stop"))) },
            { NormalizedResponseValidator.stops(city, listOf(stop.copy(providerId = "wrong"))) },
            { NormalizedResponseValidator.stops(city, listOf(stop.copy(code = ""))) },
            { NormalizedResponseValidator.stops(city, listOf(stop.copy(position = GeoPoint(0.0, 181.0)))) },
            { NormalizedResponseValidator.stops(city, listOf(stop.copy(routeIds = emptyList()))) },
            { NormalizedResponseValidator.stops(city, listOf(stop.copy(routeIds = listOf("test:other:route:r1")))) },
            { NormalizedResponseValidator.stops(city, listOf(stop.copy(mode = "rail"))) },
            { NormalizedResponseValidator.stops(city, listOf(stop, stop)) },
            {
                NormalizedResponseValidator.directionStops(
                    "test",
                    route,
                    direction.id,
                    listOf(stop.copy(routeIds = listOf("test:provider:route:other"))),
                )
            },
        )
    }

    @Test
    fun `shape rejects malformed format precision content timestamp and direction`() {
        assertInvalid(
            { NormalizedResponseValidator.shape("test", route, direction.id, validShape.copy(format = "points")) },
            { NormalizedResponseValidator.shape("test", route, direction.id, validShape.copy(precision = 9)) },
            { NormalizedResponseValidator.shape("test", route, direction.id, validShape.copy(value = "")) },
            { NormalizedResponseValidator.shape("test", route, direction.id, validShape.copy(updatedAt = "2030-01-01")) },
            { NormalizedResponseValidator.shape("test", route, "test:provider:direction:missing", validShape) },
        )
    }

    @Test
    fun `vehicles reject malformed relationships timestamps WGS84 and numeric bounds`() {
        assertInvalid(
            {
                NormalizedResponseValidator.vehiclePage(
                    "test",
                    route,
                    direction.id,
                    validVehicles.copy(items = listOf(vehicle.copy(id = "test:provider:bus:v1"))),
                )
            },
            {
                NormalizedResponseValidator.vehiclePage(
                    "test",
                    route,
                    direction.id,
                    validVehicles.copy(items = listOf(vehicle.copy(routeId = "test:provider:route:other"))),
                )
            },
            {
                NormalizedResponseValidator.vehiclePage(
                    "test",
                    route,
                    direction.id,
                    validVehicles.copy(items = listOf(vehicle.copy(position = GeoPoint(-91.0, 0.0)))),
                )
            },
            {
                NormalizedResponseValidator.vehiclePage(
                    "test",
                    route,
                    direction.id,
                    validVehicles.copy(items = listOf(vehicle.copy(bearing = 361.0))),
                )
            },
            {
                NormalizedResponseValidator.vehiclePage(
                    "test",
                    route,
                    direction.id,
                    validVehicles.copy(items = listOf(vehicle.copy(ageSeconds = -1))),
                )
            },
            { NormalizedResponseValidator.vehiclePage("test", route, direction.id, validVehicles.copy(observedAt = "bad")) },
            { NormalizedResponseValidator.vehiclePage("test", route, direction.id, validVehicles.copy(maxAgeSeconds = -1)) },
        )
        PositionKind.entries.forEach { kind ->
            NormalizedResponseValidator.vehiclePage(
                "test",
                route,
                direction.id,
                validVehicles.copy(items = listOf(vehicle.copy(positionKind = kind))),
            )
        }
    }

    @Test
    fun `arrivals reject cross-provider references timestamps and numeric bounds`() {
        assertInvalid(
            {
                NormalizedResponseValidator.arrivalPage(
                    "test",
                    stop.id,
                    validArrivals.copy(items = listOf(arrival.copy(stopId = "test:provider:stop:other"))),
                )
            },
            {
                NormalizedResponseValidator.arrivalPage(
                    "test",
                    stop.id,
                    validArrivals.copy(items = listOf(arrival.copy(routeId = "test:other:route:r1"))),
                )
            },
            {
                NormalizedResponseValidator.arrivalPage(
                    "test",
                    stop.id,
                    validArrivals.copy(items = listOf(arrival.copy(expectedAt = "2030-01-01T00:00:00+01:00"))),
                )
            },
            {
                NormalizedResponseValidator.arrivalPage(
                    "test",
                    stop.id,
                    validArrivals.copy(items = listOf(arrival.copy(expectedInMinutes = 1_441))),
                )
            },
            { NormalizedResponseValidator.arrivalPage("test", stop.id, validArrivals.copy(observedAt = "bad")) },
        )
        ArrivalSource.entries.forEach { source ->
            NormalizedResponseValidator.arrivalPage("test", stop.id, validArrivals.copy(source = source, items = emptyList()))
        }
    }

    @Test
    fun `journeys reject invalid IDs providers times transfer bounds and relationships`() {
        assertInvalid(
            {
                NormalizedResponseValidator.journeyPage(
                    "test",
                    validJourneys.copy(items = listOf(journey.copy(id = "test:provider:trip:j1"))),
                )
            },
            {
                NormalizedResponseValidator.journeyPage(
                    "test",
                    validJourneys.copy(items = listOf(journey.copy(arrivalAt = "2029-01-01T00:00:00Z"))),
                )
            },
            {
                NormalizedResponseValidator.journeyPage(
                    "test",
                    validJourneys.copy(items = listOf(journey.copy(transfers = 7))),
                )
            },
            {
                NormalizedResponseValidator.journeyPage(
                    "test",
                    validJourneys.copy(items = listOf(journey.copy(legs = emptyList()))),
                )
            },
            {
                val badLeg = journey.legs.single().copy(routeId = "test:other:route:r1")
                NormalizedResponseValidator.journeyPage(
                    "test",
                    validJourneys.copy(items = listOf(journey.copy(legs = listOf(badLeg)))),
                )
            },
            {
                val badLeg = journey.legs.single().copy(arrivalAt = "2031-01-01T00:00:00Z")
                NormalizedResponseValidator.journeyPage(
                    "test",
                    validJourneys.copy(items = listOf(journey.copy(legs = listOf(badLeg)))),
                )
            },
            { NormalizedResponseValidator.journeyPage("test", validJourneys.copy(observedAt = "not-an-instant")) },
        )
    }

    private fun assertInvalid(vararg checks: () -> Unit) {
        checks.forEach { check -> assertFailsWith<ProviderBadGateway> { check() } }
    }
}
