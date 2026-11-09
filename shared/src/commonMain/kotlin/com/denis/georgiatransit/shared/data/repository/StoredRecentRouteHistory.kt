package com.denis.georgiatransit.shared.data.repository

import com.denis.georgiatransit.shared.data.cache.TransitCache
import com.denis.georgiatransit.shared.data.cache.TransitCacheEntry
import com.denis.georgiatransit.shared.data.cache.transitCacheDispatcher
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.repository.RecentRouteHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal class StoredRecentRouteHistory(
    private val cache: TransitCache,
    private val dispatcher: CoroutineDispatcher = transitCacheDispatcher,
) : RecentRouteHistory {
    private val mutex = Mutex()

    override suspend fun read(cityId: CityId): List<RouteId> = withContext(dispatcher) {
        mutex.withLock { readStored(cityId) }
    }

    override suspend fun record(cityId: CityId, selectedRoutes: Set<RouteId>) {
        if (selectedRoutes.isEmpty()) return
        // The selection is already committed when this is called.
        withContext(dispatcher + NonCancellable) {
            mutex.withLock {
                val updated = (selectedRoutes.toList().asReversed() + readStored(cityId)).bounded()
                val now = Clock.System.now().toEpochMilliseconds()
                cache.write(key(cityId), TransitCacheEntry(fetchedAtEpochMillis = now, validatedAtEpochMillis = now,
                    payload = updated), MaxEncodedChars)
            }
        }
    }

    private fun readStored(cityId: CityId): List<RouteId> = try {
        cache.read<List<RouteId>>(key(cityId), MaxEncodedChars)?.payload.orEmpty().bounded()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private fun List<RouteId>.bounded(): List<RouteId> =
        filter { it.value.isNotBlank() }.distinct().take(MaxEntries)

    private fun key(cityId: CityId) = "georgia-transit.recent-routes.v1.${cityId.value}"

    private companion object {
        const val MaxEntries = 8
        const val MaxEncodedChars = 16_384
    }
}
