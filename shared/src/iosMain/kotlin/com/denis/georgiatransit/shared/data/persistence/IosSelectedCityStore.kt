package com.denis.georgiatransit.shared.data.persistence

import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

class IosSelectedCityStore : SelectedCityStore {
    override fun read(): CachedCitySnapshot? {
        val encoded = NSUserDefaults.standardUserDefaults.stringForKey(SelectedCityKey) ?: return null
        return runCatching { json.decodeFromString<CachedCitySnapshot>(encoded) }
            .getOrElse {
                clear()
                null
            }
    }

    override fun save(city: TransitCity) {
        NSUserDefaults.standardUserDefaults.setObject(
            json.encodeToString(CachedCitySnapshot(city = city)),
            forKey = SelectedCityKey,
        )
    }

    override fun clear() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(SelectedCityKey)
    }

    private companion object {
        const val SelectedCityKey = "georgia_transit.bootstrap.selected_city_snapshot"

        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}
