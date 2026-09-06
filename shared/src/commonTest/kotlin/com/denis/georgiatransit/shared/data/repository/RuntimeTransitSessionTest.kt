package com.denis.georgiatransit.shared.data.repository

import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeTransitSessionTest {
    @Test
    fun changingCityClearsSelectedRoutes() {
        val session = RuntimeTransitSession()
        val tbilisi = city("tbilisi")
        val batumi = city("batumi")

        session.selectCity(tbilisi)
        session.selectRoutes(setOf(RouteId("tbilisi:preview:route:301")))
        session.selectCity(batumi)

        assertEquals(batumi, session.selectedCity.value)
        assertTrue(session.selectedRouteIds.value.isEmpty())
    }

    private fun city(id: String) = TransitCity(
        id = CityId(id),
        name = id,
        center = GeoPoint(0.0, 0.0),
        capabilities = CityCapabilities(true, true, true, true, true),
    )
}

