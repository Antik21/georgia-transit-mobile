package com.denis.georgiatransit.shared.domain.repository

import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitCity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachedCitySnapshotTest {
    private val json = Json
    private val city = TransitCity(
        id = CityId("tbilisi"),
        name = "Tbilisi",
        center = GeoPoint(41.7, 44.8),
        capabilities = CityCapabilities(true, true, true, true, true),
    )

    @Test
    fun v1IsStrictlyCityOnlyEvenWhenItCarriesCraftedRouteIds() {
        val encoded = json.encodeToString(
            CachedCitySnapshot(
                schemaVersion = CachedCitySnapshot.LegacyCityOnlySchemaVersion,
                city = city,
                selectedRouteIds = setOf(RouteId("crafted:route")),
            ),
        )

        val decoded = assertNotNull(decodeCachedCitySnapshot(encoded, json))

        assertEquals(city, decoded.city)
        assertTrue(decoded.routeIdsForRestore().isEmpty())
    }

    @Test
    fun v2RestoresOnlyBoundedNonBlankOpaqueIdsAndKeepsCityOnRouteFieldFailures() {
        val valid = linkedSetOf(RouteId("opaque:1"), RouteId("opaque:2"))
        val oversized = (0..RouteSelectionPolicy.MaximumSelectedRoutes)
            .mapTo(linkedSetOf()) { RouteId("opaque:$it") }
        val malformedRouteField = """{
            "schemaVersion":2,
            "city":${json.encodeToString(city)},
            "selectedRouteIds":"not-an-array"
        }""".trimIndent()

        val validDecoded = assertNotNull(
            decodeCachedCitySnapshot(json.encodeToString(CachedCitySnapshot(city = city, selectedRouteIds = valid)), json),
        )
        assertEquals(valid, validDecoded.routeIdsForRestore())

        listOf(setOf(RouteId(" ")), oversized).forEach { invalidIds ->
            val decoded = assertNotNull(
                decodeCachedCitySnapshot(json.encodeToString(CachedCitySnapshot(city = city, selectedRouteIds = invalidIds)), json),
            )
            assertEquals(city, decoded.city)
            assertTrue(decoded.routeIdsForRestore().isEmpty())
        }

        val malformedDecoded = assertNotNull(decodeCachedCitySnapshot(malformedRouteField, json))
        assertEquals(city, malformedDecoded.city)
        assertTrue(malformedDecoded.routeIdsForRestore().isEmpty())
    }

    @Test
    fun malformedOrUnknownEnvelopeIsRejectedWhileUnknownSchemaCannotRestoreIds() {
        assertNull(decodeCachedCitySnapshot("""{"schemaVersion":"v2","city":{}}""", json))
        assertNull(
            decodeCachedCitySnapshot(
                """{"schemaVersion":2,"city":${json.encodeToString(city)},"unexpected":true}""",
                json,
            ),
        )

        val unknownSchema = assertNotNull(
            decodeCachedCitySnapshot(
                json.encodeToString(CachedCitySnapshot(schemaVersion = 99, city = city, selectedRouteIds = setOf(RouteId("opaque:1")))),
                json,
            ),
        )
        assertTrue(unknownSchema.routeIdsForRestore().isEmpty())
    }
}
