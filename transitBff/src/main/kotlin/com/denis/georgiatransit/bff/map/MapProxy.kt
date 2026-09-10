package com.denis.georgiatransit.bff.map

import com.denis.georgiatransit.bff.config.MapProxyActivationConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val MaximumTileBytes = 1_500_000
private const val MaximumCachedTiles = 256
private const val MaximumInFlightTiles = 64
private val TileTtl: Duration = Duration.ofHours(24)

/** Server-only bounded raster downloader; public callers can request coordinates, never URLs. */
internal class MapProxy(
    private val config: MapProxyActivationConfig,
    private val downloader: MapTileDownloader = HttpMapTileDownloader(),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val lock = Mutex()
    private val cache = LinkedHashMap<TileKey, CachedTile>(MaximumCachedTiles, 0.75f, true)
    private val inFlight = mutableMapOf<TileKey, CompletableDeferred<ByteArray>>()
    private val concurrentDownloads = Semaphore(8)

    fun styleJson(): String {
        val base = requireNotNull(config.publicBaseUrl).toString().trimEnd('/')
        return """{"version":8,"sources":{"bff-raster":{"type":"raster","tiles":["$base/v1/map/tiles/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":${json(config.attribution ?: "")}}},"layers":[{"id":"bff-raster","type":"raster","source":"bff-raster"}]}"""
    }

    suspend fun tile(z: Int, x: Int, y: Int): ByteArray {
        validate(z, x, y)
        val key = TileKey(z, x, y)
        val (waiter, owner) = lock.withLock {
            cache[key]?.takeIf { Duration.between(it.cachedAt, clock.instant()) < TileTtl }?.bytes?.let { return it }
            inFlight[key]?.let { return@withLock it to false }
            if (inFlight.size >= MaximumInFlightTiles) throw MapProxyFailure("tile request queue is at capacity")
            CompletableDeferred<ByteArray>().also { inFlight[key] = it } to true
        }
        if (!owner) return waiter.await()
        try {
            val address = requireNotNull(config.tileTemplate).replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
            val bytes = concurrentDownloads.withPermit { downloader.download(address) }
            lock.withLock {
                cache[key] = CachedTile(bytes, clock.instant())
                while (cache.size > MaximumCachedTiles) cache.entries.iterator().run { next(); remove() }
                inFlight.remove(key)
            }
            waiter.complete(bytes)
            return bytes
        } catch (exception: CancellationException) {
            waiter.completeExceptionally(exception); throw exception
        } catch (exception: Throwable) {
            waiter.completeExceptionally(exception); throw exception
        } finally {
            lock.withLock { inFlight.remove(key) }
        }
    }

    override fun close() = downloader.close()

    companion object {
        fun validate(z: Int, x: Int, y: Int) {
            if (z !in 0..19) throw IllegalArgumentException("tile z must be between 0 and 19")
            val edge = 1 shl z
            if (x !in 0 until edge || y !in 0 until edge) throw IllegalArgumentException("tile coordinates are out of range")
        }
        private fun json(value: String): String = buildString {
            append('"'); value.forEach { char -> when (char) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(char) } }; append('"')
        }
    }
}

private data class TileKey(val z: Int, val x: Int, val y: Int)
private data class CachedTile(val bytes: ByteArray, val cachedAt: Instant)

internal interface MapTileDownloader : AutoCloseable { suspend fun download(address: String): ByteArray }

private class HttpMapTileDownloader : MapTileDownloader {
    private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 4_000; connectTimeoutMillis = 2_000; socketTimeoutMillis = 4_000 } }
    override suspend fun download(address: String): ByteArray = try {
        val response = http.get { url(address) }
        if (response.status != HttpStatusCode.OK) throw MapProxyFailure("tile upstream returned ${response.status.value}")
        if (response.headers["Content-Type"]?.substringBefore(';') != "image/png") throw MapProxyFailure("tile upstream did not return PNG")
        response.bodyAsChannel().readBounded(MaximumTileBytes)
    } catch (exception: CancellationException) { throw exception
    } catch (exception: MapProxyFailure) { throw exception
    } catch (_: HttpRequestTimeoutException) { throw MapProxyFailure("tile upstream timed out")
    } catch (_: Exception) { throw MapProxyFailure("tile upstream unavailable") }
    override fun close() = http.cancel()
}

class MapProxyFailure(message: String) : RuntimeException(message)

private suspend fun io.ktor.utils.io.ByteReadChannel.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(); val buffer = ByteArray(8_192)
    while (true) { val read = readAvailable(buffer, 0, buffer.size); if (read <= 0) break; if (out.size() + read > limit) throw MapProxyFailure("tile response is too large"); out.write(buffer, 0, read) }
    return out.toByteArray()
}
