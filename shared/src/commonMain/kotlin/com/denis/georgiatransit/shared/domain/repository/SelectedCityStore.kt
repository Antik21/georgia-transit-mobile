package com.denis.georgiatransit.shared.domain.repository

import com.denis.georgiatransit.shared.domain.model.TransitCity
import kotlinx.serialization.Serializable

/**
 * A deliberately narrow durable cache for the selected city snapshot.
 *
 * This is not a general persistence API. It retains the city's capability snapshot so the app
 * can restore a previously enabled city while a future BFF is temporarily unreachable.
 */
interface SelectedCityStore {
    fun read(): CachedCitySnapshot?
    fun save(city: TransitCity)
    fun clear()
}

@Serializable
data class CachedCitySnapshot(
    val schemaVersion: Int = SchemaVersion,
    val city: TransitCity,
) {
    companion object {
        const val SchemaVersion = 1
    }
}
