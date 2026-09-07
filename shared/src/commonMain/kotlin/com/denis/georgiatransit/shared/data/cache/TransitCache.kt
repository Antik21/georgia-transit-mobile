package com.denis.georgiatransit.shared.data.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

/** Narrow native key-value contract for the DEN-49 durable BFF cache only. */
interface TransitCacheStore {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun remove(key: String)
    fun keys(prefix: String): List<String>
}

@Serializable
data class TransitCacheEntry<T>(
    val schemaVersion: Int = SchemaVersion,
    val fetchedAtEpochMillis: Long,
    val validatedAtEpochMillis: Long,
    val eTag: String? = null,
    val payload: T,
) {
    companion object { const val SchemaVersion = 1 }
}

fun interface TransitClock { fun nowEpochMillis(): Long }

/**
 * Enforces the entry schema and size limits in common code so platform stores cannot retain
 * corrupt/unbounded payloads. Each payload is one serialized preference value, making a replace
 * of data, ETag, and validation time a single storage operation.
 */
class TransitCache(
    @PublishedApi internal val store: TransitCacheStore,
    @PublishedApi internal val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    inline fun <reified T> read(
        key: String,
        maxEncodedChars: Int,
        nowEpochMillis: Long? = null,
    ): TransitCacheEntry<T>? {
        val encoded = store.read(key) ?: return null
        if (encoded.length > maxEncodedChars) {
            remove(key)
            return null
        }
        val decoded = try {
            json.decodeFromString<TransitCacheEntry<T>>(encoded)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            null
        }
        return decoded?.takeIf {
            it.schemaVersion == TransitCacheEntry.SchemaVersion && it.hasSaneTimestamps(nowEpochMillis)
        } ?: run {
            remove(key)
            null
        }
    }

    inline fun <reified T> write(key: String, entry: TransitCacheEntry<T>, maxEncodedChars: Int): Boolean {
        val encoded = try {
            json.encodeToString(entry)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            return false
        }
        if (encoded.length > maxEncodedChars) return false
        return try {
            store.write(key, encoded)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            false
        }
    }

    fun remove(key: String) {
        try {
            store.remove(key)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // Cache eviction must not hide a usable network response.
        }
    }

    fun keys(prefix: String): List<String> = try {
        store.keys(prefix)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        emptyList()
    }
}

@PublishedApi
internal fun TransitCacheEntry<*>.hasSaneTimestamps(nowEpochMillis: Long?): Boolean =
    fetchedAtEpochMillis >= 0L &&
        validatedAtEpochMillis >= fetchedAtEpochMillis &&
        (nowEpochMillis == null ||
            (nowEpochMillis >= 0L && fetchedAtEpochMillis <= nowEpochMillis && validatedAtEpochMillis <= nowEpochMillis))
