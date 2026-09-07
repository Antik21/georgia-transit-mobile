package com.denis.georgiatransit.shared.data.cache

import android.content.Context

/** SharedPreferences adapter for the small, schema-versioned BFF catalog cache. */
class AndroidTransitCacheStore(context: Context) : TransitCacheStore {
    private val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String): Boolean = preferences.edit().putString(key, value).commit()

    override fun remove(key: String) {
        preferences.edit().remove(key).commit()
    }

    override fun keys(prefix: String): List<String> = preferences.all.keys.filter { it.startsWith(prefix) }

    private companion object { const val PreferencesName = "georgia_transit.bff_cache" }
}
