package com.denis.georgiatransit.shared.data.cache

import com.denis.georgiatransit.shared.data.persistence.BrowserSelectedCityStore
import com.denis.georgiatransit.shared.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.browser.window

class BrowserStorageTest {
    @Test
    fun persistsAcrossInstances() {
        val key = "georgia_transit.test.storage"
        try {
            BrowserStorageBackend().write(key, "snapshot")
            assertEquals("snapshot", BrowserStorageBackend().read(key))
        } finally {
            window.localStorage.removeItem(key)
        }
    }

    @Test
    fun selectedCitySurvivesNewStoreInstance() {
        val city = TransitCity(
            id = CityId("test"), name = "Test", center = GeoPoint(41.7, 44.8),
            capabilities = CityCapabilities(stops = true, vehicles = true, arrivals = true, routeShapes = true, journeyPlanning = true),
        )
        try {
            BrowserSelectedCityStore().save(city)
            assertEquals(city, BrowserSelectedCityStore().read()?.city)
        } finally {
            BrowserSelectedCityStore().clear()
        }
    }

    @Test
    fun fallsBackToMemoryWhenStorageThrows() {
        val store = BrowserStorageBackend { error("storage unavailable or quota exceeded") }
        store.write("city", "snapshot")
        assertEquals("snapshot", store.read("city"))
        assertEquals(listOf("city"), store.keys("ci"))
        store.remove("city")
        assertNull(store.read("city"))
    }
}
