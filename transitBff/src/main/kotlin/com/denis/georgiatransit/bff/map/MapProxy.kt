package com.denis.georgiatransit.bff.map

import com.denis.georgiatransit.bff.config.MapProxyActivationConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

private const val MaximumTileBytes = 1_500_000
private const val MaximumCachedTiles = 2_048
private const val MaximumCacheBytes = 48 * 1_024 * 1_024
private const val MaximumInFlightTiles = 16
private const val MaximumConcurrentDownloads = 4
private const val MaximumUpstreamRequestsPerMinute = 120
private const val MinimumMapZoom = 8
private const val MaximumMapZoom = 19
private const val BatumiWestLongitude = 41.35
private const val BatumiSouthLatitude = 41.30
private const val BatumiEastLongitude = 42.20
private const val BatumiNorthLatitude = 41.95
internal const val MapTileCacheSeconds = 604_800
private const val MapTileUserAgent =
    "GeorgiaTransit/1.0 (+https://github.com/Antik21/georgia-transit-mobile)"
private val TileTtl: Duration = Duration.ofSeconds(MapTileCacheSeconds.toLong())

/** Server-only bounded raster downloader; public callers can request coordinates, never URLs. */
internal class MapProxy(
    private val config: MapProxyActivationConfig,
    private val downloader: MapTileDownloader = HttpMapTileDownloader(),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val lock = Mutex()
    private val cache = LinkedHashMap<TileKey, CachedTile>(MaximumCachedTiles, 0.75f, true)
    private var cachedBytes = 0
    private val inFlight = mutableMapOf<TileKey, CompletableDeferred<ByteArray>>()
    private val concurrentDownloads = Semaphore(MaximumConcurrentDownloads)
    private val upstreamRequestTimes = ArrayDeque<Instant>()
    private val upstreamRateLock = Mutex()

    fun styleJson(): String {
        val base = requireNotNull(config.publicBaseUrl).toString().trimEnd('/')
        return """{"version":8,"sources":{"bff-raster":{"type":"raster","tiles":["$base/v1/map/tiles/{z}/{x}/{y}.png"],"tileSize":256,"minzoom":$MinimumMapZoom,"maxzoom":$MaximumMapZoom,"bounds":[$BatumiWestLongitude,$BatumiSouthLatitude,$BatumiEastLongitude,$BatumiNorthLatitude],"attribution":${json(config.attribution ?: "")}}},"layers":[{"id":"bff-raster","type":"raster","source":"bff-raster"}]}"""
    }

    suspend fun tile(z: Int, x: Int, y: Int): ByteArray {
        validate(z, x, y)
        val key = TileKey(z, x, y)
        val acquisition = lock.withLock {
            val cached = cache[key]
            cached?.takeIf { Duration.between(it.cachedAt, clock.instant()) < TileTtl }?.bytes?.let { return it }
            inFlight[key]?.let { return@withLock TileAcquisition(it, owner = false, cached = null) }
            if (inFlight.size >= MaximumInFlightTiles) throw MapProxyFailure("tile request queue is at capacity")
            val waiter = CompletableDeferred<ByteArray>().also { inFlight[key] = it }
            TileAcquisition(waiter, owner = true, cached = cached)
        }
        if (!acquisition.owner) return acquisition.waiter.await()
        try {
            val address = requireNotNull(config.tileTemplate).replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
            val download = concurrentDownloads.withPermit {
                reserveUpstreamRequest()
                downloader.download(
                    address,
                    acquisition.cached?.let { MapTileValidators(it.eTag, it.lastModified) },
                )
            }
            val refreshedAt = clock.instant()
            val refreshed = download.bytes?.let {
                CachedTile(it, refreshedAt, download.eTag, download.lastModified)
            } ?: acquisition.cached?.copy(
                cachedAt = refreshedAt,
                eTag = download.eTag ?: acquisition.cached.eTag,
                lastModified = download.lastModified ?: acquisition.cached.lastModified,
            ) ?: throw MapProxyFailure("tile upstream returned not modified without a cached tile")
            lock.withLock {
                cache.put(key, refreshed)?.let { cachedBytes -= it.bytes.size }
                cachedBytes += refreshed.bytes.size
                while (cache.size > MaximumCachedTiles || cachedBytes > MaximumCacheBytes) {
                    val iterator = cache.entries.iterator()
                    val evicted = iterator.next()
                    cachedBytes -= evicted.value.bytes.size
                    iterator.remove()
                }
                inFlight.remove(key)
            }
            acquisition.waiter.complete(refreshed.bytes)
            return refreshed.bytes
        } catch (exception: CancellationException) {
            acquisition.waiter.completeExceptionally(exception); throw exception
        } catch (exception: Throwable) {
            acquisition.waiter.completeExceptionally(exception); throw exception
        } finally {
            withContext(NonCancellable) {
                lock.withLock { inFlight.remove(key) }
            }
        }
    }

    private suspend fun reserveUpstreamRequest() {
        upstreamRateLock.withLock {
            val now = clock.instant()
            val cutoff = now.minus(Duration.ofMinutes(1))
            while (upstreamRequestTimes.firstOrNull()?.isAfter(cutoff) == false) {
                upstreamRequestTimes.removeFirst()
            }
            if (upstreamRequestTimes.size >= MaximumUpstreamRequestsPerMinute) {
                throw MapProxyFailure("tile upstream request budget is exhausted")
            }
            upstreamRequestTimes.addLast(now)
        }
    }

    override fun close() = downloader.close()

    companion object {
        fun validate(z: Int, x: Int, y: Int) {
            if (z !in MinimumMapZoom..MaximumMapZoom) {
                throw IllegalArgumentException("tile z must be between $MinimumMapZoom and $MaximumMapZoom")
            }
            val edge = 1 shl z
            if (x !in 0 until edge || y !in 0 until edge) throw IllegalArgumentException("tile coordinates are out of range")
            val allowedX = longitudeTileX(BatumiWestLongitude, z)..longitudeTileX(BatumiEastLongitude, z)
            val allowedY = latitudeTileY(BatumiNorthLatitude, z)..latitudeTileY(BatumiSouthLatitude, z)
            if (x !in allowedX || y !in allowedY) {
                throw IllegalArgumentException("tile coordinates are outside the Batumi map area")
            }
        }

        private fun longitudeTileX(longitude: Double, zoom: Int): Int {
            val edge = 1 shl zoom
            return floor((longitude + 180.0) / 360.0 * edge).toInt().coerceIn(0, edge - 1)
        }

        private fun latitudeTileY(latitude: Double, zoom: Int): Int {
            val edge = 1 shl zoom
            val radians = latitude * PI / 180.0
            return floor((1.0 - asinh(tan(radians)) / PI) / 2.0 * edge).toInt().coerceIn(0, edge - 1)
        }
        private fun json(value: String): String = buildString {
            append('"'); value.forEach { char -> when (char) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(char) } }; append('"')
        }
    }
}

private data class TileKey(val z: Int, val x: Int, val y: Int)
private data class CachedTile(
    val bytes: ByteArray,
    val cachedAt: Instant,
    val eTag: String?,
    val lastModified: String?,
)
private data class TileAcquisition(
    val waiter: CompletableDeferred<ByteArray>,
    val owner: Boolean,
    val cached: CachedTile?,
)

internal data class MapTileValidators(val eTag: String?, val lastModified: String?)
internal data class MapTileDownload(
    val bytes: ByteArray?,
    val eTag: String?,
    val lastModified: String?,
)

internal interface MapTileDownloader : AutoCloseable {
    suspend fun download(address: String, validators: MapTileValidators?): MapTileDownload
}

private class HttpMapTileDownloader : MapTileDownloader {
    private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 4_000; connectTimeoutMillis = 2_000; socketTimeoutMillis = 4_000 } }
    override suspend fun download(address: String, validators: MapTileValidators?): MapTileDownload {
        return try {
            val response = http.get {
                url(address)
                header(HttpHeaders.UserAgent, MapTileUserAgent)
                validators?.eTag?.let { header(HttpHeaders.IfNoneMatch, it) }
                validators?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }
            if (response.status == HttpStatusCode.NotModified) {
                MapTileDownload(
                    bytes = null,
                    eTag = response.headers[HttpHeaders.ETag],
                    lastModified = response.headers[HttpHeaders.LastModified],
                )
            } else {
                if (response.status != HttpStatusCode.OK) throw MapProxyFailure("tile upstream returned ${response.status.value}")
                if (response.headers["Content-Type"]?.substringBefore(';') != "image/png") throw MapProxyFailure("tile upstream did not return PNG")
                MapTileDownload(
                    bytes = response.bodyAsChannel().readBounded(MaximumTileBytes),
                    eTag = response.headers[HttpHeaders.ETag],
                    lastModified = response.headers[HttpHeaders.LastModified],
                )
            }
        } catch (exception: CancellationException) { throw exception
        } catch (exception: MapProxyFailure) { throw exception
        } catch (_: HttpRequestTimeoutException) { throw MapProxyFailure("tile upstream timed out")
        } catch (_: Exception) { throw MapProxyFailure("tile upstream unavailable")
        }
    }
    override fun close() = http.cancel()
}

class MapProxyFailure(message: String) : RuntimeException(message)

private suspend fun io.ktor.utils.io.ByteReadChannel.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(); val buffer = ByteArray(8_192)
    while (true) { val read = readAvailable(buffer, 0, buffer.size); if (read <= 0) break; if (out.size() + read > limit) throw MapProxyFailure("tile response is too large"); out.write(buffer, 0, read) }
    return out.toByteArray()
}
