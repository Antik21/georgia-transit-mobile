package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.api.InvalidArgument
import com.denis.georgiatransit.bff.api.UpstreamBadGateway
import com.denis.georgiatransit.bff.map.MapProxy
import com.denis.georgiatransit.bff.map.MapProxyFailure
import com.denis.georgiatransit.bff.map.MapTileCacheSeconds
import com.denis.georgiatransit.bff.observability.HttpOperation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.mapRoutes(mapProxy: MapProxy, kutaisiMapEnabled: () -> Boolean) {
    get("/v1/map/style.json") {
        call.markHttpOperation(HttpOperation.MAP_STYLE)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.respondText(mapProxy.styleJson(kutaisiEnabled = kutaisiMapEnabled()), ContentType.Application.Json)
    }
    get("/v1/map/tiles/{z}/{x}/{y}.png") {
        call.markHttpOperation(HttpOperation.MAP_TILE)
        val z = call.parameters["z"]?.toIntOrNull()
        val x = call.parameters["x"]?.toIntOrNull()
        val y = call.parameters["y"]?.toIntOrNull()
        if (z == null || x == null || y == null) throw InvalidArgument("tile coordinates must be integers")
        try {
            val bytes = mapProxy.tile(z, x, y, kutaisiEnabled = kutaisiMapEnabled())
            call.response.header(HttpHeaders.CacheControl, "public, max-age=$MapTileCacheSeconds")
            call.respondBytes(bytes, ContentType.Image.PNG)
        } catch (failure: IllegalArgumentException) {
            throw InvalidArgument(failure.message ?: "invalid tile coordinates")
        } catch (_: MapProxyFailure) {
            throw UpstreamBadGateway("Map tile is unavailable")
        }
    }
}
