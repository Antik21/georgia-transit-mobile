package com.denis.georgiatransit.shared.data.cache

import platform.Foundation.NSUserDefaults

/** NSUserDefaults adapter for the small, schema-versioned BFF catalog cache. */
class IosTransitCacheStore : TransitCacheStore {
    override fun read(key: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)

    override fun write(key: String, value: String): Boolean = runCatching {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
        true
    }.getOrDefault(false)

    override fun remove(key: String) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    }

    override fun keys(prefix: String): List<String> =
        NSUserDefaults.standardUserDefaults.dictionaryRepresentation().keys
            .mapNotNull { it as? String }
            .filter { it.startsWith(prefix) }
}
