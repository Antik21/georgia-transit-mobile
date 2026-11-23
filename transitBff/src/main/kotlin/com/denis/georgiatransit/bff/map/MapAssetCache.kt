package com.denis.georgiatransit.bff.map

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val MaximumAssetBytes = 1_500_000
private const val MaximumCachedTiles = 2_048
private const val MaximumCacheBytes = 48 * 1_024 * 1_024
private const val MaximumInFlightTiles = 16
private const val MaximumConcurrentDownloads = 4
private const val MaximumUpstreamRequestsPerMinute = 120
private const val MapTileUserAgent =
    "GeorgiaTransit/1.0 (+https://github.com/Antik21/georgia-transit-mobile)"

/** Bounds downloaded assets and coalesces concurrent cache misses for the same address. */
internal class MapAssetCache(
    private val downloader: MapTileDownloader,
    private val clock: Clock,
) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, CachedTile>(MaximumCachedTiles, 0.75f, true)
    private var cachedBytes = 0
    private val inFlight = mutableMapOf<String, CompletableDeferred<ByteArray>>()
    private val concurrentDownloads = Semaphore(MaximumConcurrentDownloads)
    private val upstreamRequestTimes = ArrayDeque<Instant>()
    private val upstreamRateLock = Mutex()

    suspend fun get(address: String, format: MapAssetFormat, ttl: Duration): ByteArray {
        val acquisition = lock.withLock {
            val cached = cache[address]
            cached?.takeIf { Duration.between(it.cachedAt, clock.instant()) < ttl }?.bytes?.let { return it }
            inFlight[address]?.let { return@withLock TileAcquisition(it, owner = false, cached = null) }
            if (inFlight.size >= MaximumInFlightTiles) throw MapProxyFailure("tile request queue is at capacity")
            val waiter = CompletableDeferred<ByteArray>().also { inFlight[address] = it }
            TileAcquisition(waiter, owner = true, cached = cached)
        }
        if (!acquisition.owner) return acquisition.waiter.await()
        try {
            val download = concurrentDownloads.withPermit {
                reserveUpstreamRequest()
                downloader.download(
                    address,
                    format,
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
                cache.put(address, refreshed)?.let { cachedBytes -= it.bytes.size }
                cachedBytes += refreshed.bytes.size
                while (cache.size > MaximumCachedTiles || cachedBytes > MaximumCacheBytes) {
                    val iterator = cache.entries.iterator()
                    val evicted = iterator.next()
                    cachedBytes -= evicted.value.bytes.size
                    iterator.remove()
                }
            }
            acquisition.waiter.complete(refreshed.bytes)
            return refreshed.bytes
        } catch (exception: CancellationException) {
            acquisition.waiter.completeExceptionally(exception); throw exception
        } catch (exception: Throwable) {
            acquisition.waiter.completeExceptionally(exception); throw exception
        } finally {
            withContext(NonCancellable) {
                lock.withLock { inFlight.remove(address) }
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
}

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

internal enum class MapAssetFormat(vararg val mediaTypes: String) {
    Vector("application/vnd.mapbox-vector-tile", "application/x-protobuf"),
    Glyph("application/x-protobuf"),
    TileJson("application/json"),
}

internal interface MapTileDownloader : AutoCloseable {
    suspend fun download(address: String, format: MapAssetFormat, validators: MapTileValidators?): MapTileDownload
}

internal class HttpMapTileDownloader : MapTileDownloader {
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 4_000
            connectTimeoutMillis = 2_000
            socketTimeoutMillis = 4_000
        }
    }
    override suspend fun download(address: String, format: MapAssetFormat, validators: MapTileValidators?): MapTileDownload {
        return try {
            http.prepareGet {
                url(address)
                header(HttpHeaders.UserAgent, MapTileUserAgent)
                validators?.eTag?.let { header(HttpHeaders.IfNoneMatch, it) }
                validators?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }.execute { response ->
                if (response.status == HttpStatusCode.NotModified) {
                    MapTileDownload(
                        bytes = null,
                        eTag = response.headers[HttpHeaders.ETag],
                        lastModified = response.headers[HttpHeaders.LastModified],
                    )
                } else {
                    if (response.status != HttpStatusCode.OK) throw MapProxyFailure("tile upstream returned ${response.status.value}")
                    val mediaType = response.headers[HttpHeaders.ContentType]?.substringBefore(';')
                    if (mediaType !in format.mediaTypes) throw MapProxyFailure("map upstream returned an unexpected content type")
                    val bytes = response.bodyAsChannel().readBounded(MaximumAssetBytes)
                    val body = if (response.headers[HttpHeaders.ContentEncoding] == "gzip") {
                        GZIPInputStream(bytes.inputStream()).use { it.readNBytes(MaximumAssetBytes + 1) }
                    } else {
                        bytes
                    }
                    if (body.size > MaximumAssetBytes) throw MapProxyFailure("map upstream response is too large")
                    MapTileDownload(
                        bytes = body,
                        eTag = response.headers[HttpHeaders.ETag],
                        lastModified = response.headers[HttpHeaders.LastModified],
                    )
                }
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
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read <= 0) return out.toByteArray()
        if (out.size() + read > limit) throw MapProxyFailure("map upstream response is too large")
        out.write(buffer, 0, read)
    }
}
