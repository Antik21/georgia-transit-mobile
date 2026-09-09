package com.denis.georgiatransit.shared.domain.repository

import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitCity
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A deliberately narrow durable cache for the selected city snapshot.
 *
 * This is not a general persistence API. It retains the city's capability snapshot so the app
 * can restore a previously enabled city while a future BFF is temporarily unreachable.
 */
interface SelectedCityStore {
    fun read(): CachedCitySnapshot?

    /**
     * Persists the complete city-scoped selection in one adapter-owned snapshot.
     *
     * The city-only overload remains for source compatibility with narrow test/dummy adapters;
     * production adapters override this method so they never split city and route writes.
     */
    fun save(snapshot: CachedCitySnapshot) = save(snapshot.city)

    fun save(city: TransitCity)
    fun clear()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CachedCitySnapshot(
    /** Always encoded so a newly written v2 record cannot be mistaken for a missing-version v1. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SchemaVersion,
    val city: TransitCity,
    /** Absent in schema v1 and therefore decoded as the safe empty selection. */
    val selectedRouteIds: Set<RouteId> = emptySet(),
) {
    /**
     * A v1 record is city-only even when a crafted payload adds a future-looking field. Only the
     * v2 schema owns the bounded opaque-ID selection contract.
     */
    fun routeIdsForRestore(): Set<RouteId> = when (schemaVersion) {
        LegacyCityOnlySchemaVersion -> emptySet()
        SchemaVersion -> RouteSelectionPolicy.sanitized(selectedRouteIds)
        else -> emptySet()
    }

    companion object {
        const val LegacyCityOnlySchemaVersion = 1
        const val SchemaVersion = 2
    }
}

/**
 * Decodes the city independently from the optional v2 route field so a syntactically valid city
 * can still restore when only the bounded selection is malformed. The persisted record remains a
 * single serialized value; this is not a second persistence format or store.
 */
fun decodeCachedCitySnapshot(encoded: String, json: Json): CachedCitySnapshot? {
    val root = runCatching { json.parseToJsonElement(encoded) as? JsonObject }
        .getOrNull()
        ?: return null
    if (root.keys.any { it !in CachedCitySnapshotKeys }) return null

    val schemaVersion = if (SchemaVersionKey in root) {
        root[SchemaVersionKey]?.let { element ->
            runCatching { json.decodeFromJsonElement<Int>(element) }.getOrNull()
        } ?: return null
    } else {
        CachedCitySnapshot.LegacyCityOnlySchemaVersion
    }
    val city = root[CityKey]?.let { element ->
        runCatching { json.decodeFromJsonElement<TransitCity>(element) }.getOrNull()
    } ?: return null
    val selectedRouteIds = if (schemaVersion == CachedCitySnapshot.SchemaVersion) {
        root[SelectedRouteIdsKey]?.let { element ->
            runCatching { json.decodeFromJsonElement<Set<RouteId>>(element) }.getOrNull()
        }.orEmpty()
    } else {
        emptySet()
    }

    return CachedCitySnapshot(
        schemaVersion = schemaVersion,
        city = city,
        selectedRouteIds = selectedRouteIds,
    )
}

private const val SchemaVersionKey = "schemaVersion"
private const val CityKey = "city"
private const val SelectedRouteIdsKey = "selectedRouteIds"
private val CachedCitySnapshotKeys = setOf(SchemaVersionKey, CityKey, SelectedRouteIdsKey)
