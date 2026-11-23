package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.api.InvalidArgument
import com.denis.georgiatransit.bff.api.UpstreamBadGateway
import com.denis.georgiatransit.bff.map.MapLabelLanguage
import com.denis.georgiatransit.bff.map.MapProxy
import com.denis.georgiatransit.bff.map.MapProxyFailure
import com.denis.georgiatransit.bff.map.GlyphCacheSeconds
import com.denis.georgiatransit.bff.map.VectorTileCacheSeconds
import com.denis.georgiatransit.bff.observability.HttpOperation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val VectorTileType = ContentType.parse("application/vnd.mapbox-vector-tile")
private val GlyphType = ContentType.parse("application/x-protobuf")

internal fun Route.mapRoutes(mapProxy: MapProxy, kutaisiMapEnabled: () -> Boolean) {
    get("/v1/map/style.json") {
        call.markHttpOperation(HttpOperation.MAP_STYLE)
        val language = call.request.queryParameters["lang"]?.let {
            MapLabelLanguage.fromCode(it) ?: throw InvalidArgument("map language must be ka, ru, or en")
        } ?: MapLabelLanguage.Georgian
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.respondText(mapProxy.styleJson(language, kutaisiMapEnabled()), ContentType.Application.Json)
    }
    get("/v1/map/vector/{z}/{x}/{y}.pbf") {
        call.markHttpOperation(HttpOperation.MAP_TILE)
        val (z, x, y) = call.tileCoordinates()
        call.respondMapAsset(VectorTileType, VectorTileCacheSeconds) {
            mapProxy.tile(z, x, y, kutaisiEnabled = kutaisiMapEnabled())
        }
    }
    get("/v1/map/fonts/{font}/{range}.pbf") {
        call.markHttpOperation(HttpOperation.MAP_TILE)
        call.respondMapAsset(GlyphType, GlyphCacheSeconds) {
            mapProxy.glyphs(call.parameters["font"].orEmpty(), call.parameters["range"].orEmpty())
        }
    }
}

private fun ApplicationCall.tileCoordinates(): Triple<Int, Int, Int> {
    val z = parameters["z"]?.toIntOrNull()
    val x = parameters["x"]?.toIntOrNull()
    val y = parameters["y"]?.toIntOrNull()
    if (z == null || x == null || y == null) throw InvalidArgument("tile coordinates must be integers")
    return Triple(z, x, y)
}

private suspend inline fun ApplicationCall.respondMapAsset(
    type: ContentType,
    cacheSeconds: Int,
    fetch: () -> ByteArray,
) {
    val bytes = try {
        fetch()
    } catch (failure: IllegalArgumentException) {
        throw InvalidArgument(failure.message ?: "invalid map asset")
    } catch (_: MapProxyFailure) {
        throw UpstreamBadGateway("Map asset is unavailable")
    }
    response.header(HttpHeaders.CacheControl, "public, max-age=$cacheSeconds")
    respondBytes(bytes, type)
}
