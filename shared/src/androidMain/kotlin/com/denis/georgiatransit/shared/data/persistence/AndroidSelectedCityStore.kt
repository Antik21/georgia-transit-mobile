package com.denis.georgiatransit.shared.data.persistence

import android.content.Context
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AndroidSelectedCityStore(context: Context) : SelectedCityStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    override fun read(): CachedCitySnapshot? {
        val encoded = preferences.getString(SelectedCityKey, null) ?: return null
        return runCatching { json.decodeFromString<CachedCitySnapshot>(encoded) }
            .getOrElse {
                clear()
                null
            }
    }

    override fun save(city: TransitCity) {
        preferences.edit()
            .putString(SelectedCityKey, json.encodeToString(CachedCitySnapshot(city = city)))
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(SelectedCityKey).apply()
    }

    private companion object {
        const val PreferencesName = "georgia_transit.bootstrap"
        const val SelectedCityKey = "selected_city_snapshot"

        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}
