package com.denis.georgiatransit.shared.data.persistence

import com.denis.georgiatransit.shared.data.cache.BrowserStorage
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.domain.repository.decodeCachedCitySnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BrowserSelectedCityStore : SelectedCityStore {
    override fun read(): CachedCitySnapshot? {
        val encoded = BrowserStorage.read(SelectedCityKey) ?: return null
        return decodeCachedCitySnapshot(encoded, json) ?: run { clear(); null }
    }

    override fun save(snapshot: CachedCitySnapshot) = BrowserStorage.write(SelectedCityKey, json.encodeToString(snapshot))
    override fun save(city: TransitCity) = save(CachedCitySnapshot(city = city))
    override fun clear() = BrowserStorage.remove(SelectedCityKey)

    private companion object {
        const val SelectedCityKey = "georgia_transit.bootstrap.selected_city_snapshot"
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    }
}
