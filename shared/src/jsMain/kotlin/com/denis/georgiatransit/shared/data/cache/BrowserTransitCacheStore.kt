package com.denis.georgiatransit.shared.data.cache

import kotlinx.browser.window

/** Uses memory for the current session when browser storage is unavailable. */
internal class BrowserStorageBackend(
    private val storage: () -> org.w3c.dom.Storage = { window.localStorage },
) {
    private val memory = mutableMapOf<String, String>()
    private var storageAvailable = true

    fun read(key: String): String? {
        if (key in memory) return memory[key]
        return withStorage { getItem(key) }
    }

    fun write(key: String, value: String) {
        memory[key] = value
        withStorage { setItem(key, value) }
    }

    fun remove(key: String) {
        memory.remove(key)
        withStorage { removeItem(key) }
    }

    fun keys(prefix: String): List<String> {
        val persisted = withStorage {
            (0 until length).mapNotNull { key(it) }.filter { it.startsWith(prefix) }
        }.orEmpty()
        return (persisted + memory.keys.filter { it.startsWith(prefix) }).distinct()
    }

    private inline fun <T> withStorage(block: org.w3c.dom.Storage.() -> T): T? {
        if (!storageAvailable) return null
        return try {
            storage().block()
        } catch (_: Throwable) {
            storageAvailable = false
            null
        }
    }
}

internal val BrowserStorage = BrowserStorageBackend()

class BrowserTransitCacheStore : TransitCacheStore {
    override fun read(key: String): String? = BrowserStorage.read(key)
    override fun write(key: String, value: String): Boolean {
        BrowserStorage.write(key, value)
        return true
    }
    override fun remove(key: String) = BrowserStorage.remove(key)
    override fun keys(prefix: String): List<String> = BrowserStorage.keys(prefix)
}
