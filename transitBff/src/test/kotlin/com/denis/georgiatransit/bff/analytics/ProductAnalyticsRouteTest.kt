package com.denis.georgiatransit.bff.analytics

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ProductAnalyticsRouteTest {
    @Test
    fun routeAllowsOnlyDocumentedAnalyticsEvents() = testApplication {
        val delivered = mutableListOf<AnalyticsRequest>()
        application {
            routing {
                productAnalyticsRoute {
                    delivered += it
                    HttpStatusCode.NoContent
                }
            }
        }

        suspend fun submit(body: String) = client.post("/v1/analytics") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status

        val event = AnalyticsRequest("telegram_mini_app", DeviceId, 1_000, 1_100, "finger_double_tap")
        val valid = Json.encodeToString(event)
        assertEquals(HttpStatusCode.NoContent, submit(valid))
        assertEquals(HttpStatusCode.BadRequest, submit(valid.replace("finger_double_tap", "wheel")))
        assertEquals(HttpStatusCode.BadRequest, submit(valid.replace("telegram_mini_app", "custom")))
        assertEquals(HttpStatusCode.BadRequest, submit(valid.dropLast(1) + ",\"latitude\":41.0}"))
        assertEquals(HttpStatusCode.PayloadTooLarge, submit(" ".repeat(513)))
        val selection = AnalyticsRequest.RoutesSelection("kutaisi", 2, SelectionId)
        val routes = event.copy(zoomType = null, routesSelection = selection)
        assertEquals(HttpStatusCode.NoContent, submit(Json.encodeToString(routes)))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(routes.copy(zoomType = "button"))))
        val invalidCity = routes.copy(routesSelection = selection.copy(cityId = "unknown"))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(invalidCity)))
        val invalidCount = routes.copy(routesSelection = selection.copy(routeCount = 11))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(invalidCount)))
        val recent = event.copy(zoomType = null, recentRoutesUsed = true)
        assertEquals(HttpStatusCode.NoContent, submit(Json.encodeToString(recent)))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(recent.copy(zoomType = "button"))))
        val mixedSelection = recent.copy(routesSelection = selection)
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(mixedSelection)))
        val search = recent.copy(recentRoutesUsed = false, routeSearchUsed = true)
        val searchBody = Json.encodeToString(search)
        assertEquals(HttpStatusCode.NoContent, submit(searchBody))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(search.copy(recentRoutesUsed = true))))
        assertEquals(HttpStatusCode.BadRequest, submit(searchBody.dropLast(1) + ",\"query\":\"D1\"}"))
        val planning = AnalyticsRequest.JourneyPlanning("long_press", SelectionId)
        val journey = search.copy(routeSearchUsed = false, journeyPlanning = planning)
        val journeyBody = Json.encodeToString(journey)
        assertEquals(HttpStatusCode.NoContent, submit(journeyBody))
        val invalidSource = journey.copy(journeyPlanning = planning.copy(source = "unknown"))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(invalidSource)))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(journey.copy(routeSearchUsed = true))))
        assertEquals(HttpStatusCode.BadRequest, submit(journeyBody.replace(
            "\"source\":\"long_press\"", "\"source\":\"long_press\",\"latitude\":41.0",
        )))
        val opening = AnalyticsRequest.StopOpening("batumi", SelectionId)
        val stop = journey.copy(journeyPlanning = null, stopOpening = opening)
        val stopBody = Json.encodeToString(stop)
        assertEquals(HttpStatusCode.NoContent, submit(stopBody))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(stop.copy(journeyPlanning = planning))))
        val invalidStopCity = stop.copy(stopOpening = opening.copy(cityId = "unknown"))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(invalidStopCity)))
        assertEquals(HttpStatusCode.BadRequest, submit(stopBody.replace(
            "\"cityId\":\"batumi\"", "\"cityId\":\"batumi\",\"stopId\":\"batumi:fixture:stop:1\"",
        )))
        val vehicle = stop.copy(stopOpening = null, vehicleTapId = SelectionId)
        val vehicleBody = Json.encodeToString(vehicle)
        assertEquals(HttpStatusCode.NoContent, submit(vehicleBody))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(vehicle.copy(vehicleTapId = "bus-1"))))
        assertEquals(HttpStatusCode.BadRequest, submit(Json.encodeToString(vehicle.copy(stopOpening = opening))))
        assertEquals(HttpStatusCode.BadRequest, submit(vehicleBody.dropLast(1) + ",\"vehicleId\":\"bus-1\"}"))
        assertEquals(listOf(event, routes, recent, search, journey, stop, vehicle), delivered)
    }

    @Test
    fun mapsAllClientsAndUsesStablePerSessionDeduplication() {
        for (client in listOf("android", "ios", "web", "telegram_mini_app")) {
            val opening = AnalyticsRequest(client, DeviceId, 1_000, 1_000)
            val identify = opening.toAmplitudeEvent()
            assertEquals("\$identify", identify.getValue("event_type").jsonPrimitive.content)
            assertEquals(client, identify.getValue("user_properties").jsonObject
                .getValue("\$set").jsonObject.getValue("client").jsonPrimitive.content)

            val zoom = opening.copy(time = 1_200, zoomType = "button")
            val payload = zoom.toAmplitudeEvent()
            assertEquals("zoom_started", payload.getValue("event_type").jsonPrimitive.content)
            assertEquals("button", payload.getValue("event_properties").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(client, payload.getValue("user_properties").jsonObject.getValue("client").jsonPrimitive.content)
            assertEquals(payload["insert_id"], zoom.copy(time = 1_300).toAmplitudeEvent()["insert_id"])
            assertNotEquals(payload["insert_id"], zoom.copy(zoomType = "finger_pinch").toAmplitudeEvent()["insert_id"])
            assertNotEquals(payload["insert_id"], zoom.copy(sessionId = 1_100).toAmplitudeEvent()["insert_id"])
        }
    }

    @Test
    fun mapsRouteSelectionAndKeepsSeparateConfirmationsDistinct() {
        val selection = AnalyticsRequest.RoutesSelection("batumi", 0, SelectionId)
        val request = AnalyticsRequest("ios", DeviceId, 1_000, 1_100, routesSelection = selection)
        val event = request.toAmplitudeEvent()
        val properties = event.getValue("event_properties").jsonObject

        assertEquals("routes_selected", event.getValue("event_type").jsonPrimitive.content)
        assertEquals(setOf("city", "route_count"), properties.keys)
        assertEquals("batumi", properties.getValue("city").jsonPrimitive.content)
        assertEquals(0, properties.getValue("route_count").jsonPrimitive.int)
        assertEquals(event["insert_id"], request.copy(time = 1_101).toAmplitudeEvent()["insert_id"])
        val next = request.copy(routesSelection = selection.copy(eventId = DeviceId))
        assertNotEquals(event["insert_id"], next.toAmplitudeEvent()["insert_id"])
    }

    @Test
    fun mapsRouteToolUsageWithoutPropertiesAndDeduplicatesEachBySession() {
        val request = AnalyticsRequest("ios", DeviceId, 1_000, 1_100)
        val requests = mapOf(
            "recent_routes_used" to request.copy(recentRoutesUsed = true),
            "route_search_used" to request.copy(routeSearchUsed = true),
        )
        val insertIds = requests.map { (name, usage) ->
            val event = usage.toAmplitudeEvent()
            assertEquals(name, event.getValue("event_type").jsonPrimitive.content)
            assertNull(event["event_properties"])
            assertEquals(event["insert_id"], usage.copy(time = 1_101).toAmplitudeEvent()["insert_id"])
            val nextSession = usage.copy(sessionId = 1_101, time = 1_101)
            assertNotEquals(event["insert_id"], nextSession.toAmplitudeEvent()["insert_id"])
            event["insert_id"]
        }
        assertEquals(requests.size, insertIds.toSet().size)
    }

    @Test
    fun mapsBothPlannerSourcesAndKeepsSeparateStartsDistinct() {
        val planning = AnalyticsRequest.JourneyPlanning("map_button", SelectionId)
        val request = AnalyticsRequest("ios", DeviceId, 1_000, 1_100, journeyPlanning = planning)
        val event = request.toAmplitudeEvent()
        val next = request.copy(journeyPlanning = planning.copy(source = "long_press", eventId = DeviceId))
        val nextEvent = next.toAmplitudeEvent()

        assertEquals("journey_planning_started", event.getValue("event_type").jsonPrimitive.content)
        assertEquals(setOf("source"), event.getValue("event_properties").jsonObject.keys)
        assertEquals("map_button", event.getValue("event_properties").jsonObject.getValue("source").jsonPrimitive.content)
        assertEquals("long_press", nextEvent.getValue("event_properties").jsonObject.getValue("source").jsonPrimitive.content)
        assertEquals(event["insert_id"], request.copy(time = 1_101).toAmplitudeEvent()["insert_id"])
        assertNotEquals(event["insert_id"], nextEvent["insert_id"])
    }

    @Test
    fun mapsStopOpeningWithOnlyItsCityAndDeduplicatesRetries() {
        val opening = AnalyticsRequest.StopOpening("kutaisi", SelectionId)
        val request = AnalyticsRequest("android", DeviceId, 1_000, 1_100, stopOpening = opening)
        val event = request.toAmplitudeEvent()
        val properties = event.getValue("event_properties").jsonObject

        assertEquals("stop_opened", event.getValue("event_type").jsonPrimitive.content)
        assertEquals(setOf("city"), properties.keys)
        assertEquals("kutaisi", properties.getValue("city").jsonPrimitive.content)
        assertEquals(event["insert_id"], request.copy(time = 1_101).toAmplitudeEvent()["insert_id"])
        val next = request.copy(stopOpening = opening.copy(eventId = DeviceId))
        assertNotEquals(event["insert_id"], next.toAmplitudeEvent()["insert_id"])
    }

    @Test
    fun mapsEachVehicleTapWithoutEventPropertiesAndDeduplicatesRetries() {
        val request = AnalyticsRequest("android", DeviceId, 1_000, 1_100, vehicleTapId = SelectionId)
        val event = request.toAmplitudeEvent()

        assertEquals("vehicle_tapped", event.getValue("event_type").jsonPrimitive.content)
        assertNull(event["event_properties"])
        assertEquals(event["insert_id"], request.copy(time = 1_101).toAmplitudeEvent()["insert_id"])
        assertNotEquals(event["insert_id"], request.copy(vehicleTapId = DeviceId).toAmplitudeEvent()["insert_id"])
    }

    private companion object {
        const val SelectionId = "12345678-90ab-4cde-8fab-0123456789ab"
        const val DeviceId = "01234567-89ab-4cde-8fab-0123456789ab"
    }
}
