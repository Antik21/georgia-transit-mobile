package com.denis.georgiatransit.shared.data.repository

import com.denis.georgiatransit.shared.domain.model.CityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewTransitRepositoryTest {
    private val repository = PreviewTransitRepository()

    @Test
    fun exposesTwoEnabledCitiesAndKeepsKutaisiBehindCapability() {
        val cities = repository.cities()

        assertEquals(listOf("tbilisi", "batumi", "kutaisi"), cities.map { it.id.value })
        assertTrue(cities.first { it.id == CityId("tbilisi") }.capabilities.stops)
        assertFalse(cities.first { it.id == CityId("kutaisi") }.capabilities.stops)
    }

    @Test
    fun routeCatalogueIsScopedToSelectedCity() {
        val routes = repository.routes(CityId("tbilisi"))

        assertEquals(listOf("301", "337", "395"), routes.map { it.shortName })
        assertTrue(routes.all { it.cityId == CityId("tbilisi") })
    }
}
