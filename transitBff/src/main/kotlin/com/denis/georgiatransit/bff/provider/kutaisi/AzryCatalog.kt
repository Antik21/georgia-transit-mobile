package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.Direction
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.TransitModeValues
import com.denis.georgiatransit.bff.api.formatPublicId
import com.denis.georgiatransit.bff.cache.BoundedKeyedTtlCache
import com.denis.georgiatransit.bff.provider.ProviderNormalizedSchemaFailure
import com.denis.georgiatransit.bff.provider.ProviderRouteNotFound
import com.denis.georgiatransit.bff.provider.ProviderStopNotFound
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.hours

private const val Provider = "azry"
private val RawId = Regex("[A-Za-z0-9][A-Za-z0-9:_-]{0,71}")
private val AzryColor = Regex("[0-9a-fA-F]{6}")
private val Encoder = Base64.getUrlEncoder().withoutPadding()

internal fun azryPublicId(type: PublicEntityType, rawId: String): String =
    formatPublicId(KnownCityIds.Kutaisi, Provider, type, azrySuffix(rawId))

private fun azrySuffix(value: String): String {
    if (!RawId.matches(value)) invalidAzry()
    return Encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
}

internal fun azryDirectionId(route: String, pattern: String): String =
    formatPublicId(KnownCityIds.Kutaisi, Provider, PublicEntityType.Direction, "${azrySuffix(route)}~${azrySuffix(pattern)}")

internal fun azryLocale(locale: String): String = if (locale == "ka") "ka" else "en"

internal fun azryText(value: String): LocalizedText = LocalizedText(ru = value, en = value, ka = value)

internal fun invalidAzry(): Nothing = throw ProviderNormalizedSchemaFailure("AZRY returned invalid transit data")

internal class AzryCatalog(private val feed: AzryFeed) : AutoCloseable {
    private val routeLists = BoundedKeyedTtlCache<String, Map<String, AzryRoute>>(2.hours, 2)
    private val routeDetails = BoundedKeyedTtlCache<Pair<String, String>, AzryRoute>(2.hours, 128)
    private val stopLists = BoundedKeyedTtlCache<String, Map<String, AzryStop>>(2.hours, 2)
    private val stopRoutes = BoundedKeyedTtlCache<Pair<String, String>, List<AzryRoute>>(2.hours, 1_200)

    suspend fun routes(locale: String): List<Route> = coroutineScope {
        routeList(locale).values.map { summary -> async { detail(summary, locale).normalized(summary) } }.awaitAll()
    }

    suspend fun route(publicId: String, locale: String): Route {
        val summary = routeSummary(publicId, locale)
        return detail(summary, locale).normalized(summary)
    }

    suspend fun rawRoute(publicId: String, locale: String): AzryRoute = detail(routeSummary(publicId, locale), locale)

    suspend fun stops(locale: String): List<Stop> = stopList(locale).values.map { it.normalized(emptyList()) }

    suspend fun stop(publicId: String, locale: String): AzryStop =
        stopList(locale)[publicId] ?: throw ProviderStopNotFound("The requested stop was not found")

    suspend fun servingRoutes(rawStop: String, locale: String): List<AzryRoute> =
        stopRoutes.getOrLoad(rawStop to locale) {
            val knownRoutes = routeList(locale)
            feed.stopRoutes(rawStop, locale).mapNotNull { row ->
                knownRoutes[azryPublicId(PublicEntityType.Route, row.id)]
            }
        }

    private suspend fun routeSummary(publicId: String, locale: String): AzryRoute =
        routeList(locale)[publicId] ?: throw ProviderRouteNotFound("The requested route was not found")

    private suspend fun routeList(locale: String): Map<String, AzryRoute> = routeLists.getOrLoad(locale) {
        val entries = feed.routes(locale).filter { it.mode == "BUS" }
        if (entries.size > 100) invalidAzry()
        entries.associateBy { azryPublicId(PublicEntityType.Route, it.id) }.also {
            if (entries.size != it.size) invalidAzry()
        }
    }

    private suspend fun stopList(locale: String): Map<String, AzryStop> = stopLists.getOrLoad(locale) {
        val entries = feed.stops(locale).filter { it.vehicleMode == "BUS" }
        entries.associateBy { azryPublicId(PublicEntityType.Stop, it.id) }.also {
            if (it.size != entries.size) invalidAzry()
        }
    }

    private suspend fun detail(summary: AzryRoute, locale: String): AzryRoute = routeDetails.getOrLoad(summary.id to locale) {
        feed.route(summary.id, locale).also { detail ->
            if (detail.id != summary.id || detail.patterns.size !in 1..16) invalidAzry()
            val keys = detail.patterns.map { azryDirectionId(detail.id, it.patternSuffix) }
            if (keys.distinct().size != keys.size) invalidAzry()
        }
    }

    override fun close() {
        routeLists.close()
        routeDetails.close()
        stopLists.close()
        stopRoutes.close()
    }
}

internal fun AzryRoute.requirePattern(directionId: String): AzryPattern =
    patterns.firstOrNull { azryDirectionId(id, it.patternSuffix) == directionId }
        ?: throw ProviderRouteNotFound("The requested direction was not found")

private fun AzryRoute.normalized(summary: AzryRoute): Route {
    val directions = patterns.sortedBy { it.patternSuffix != defaultPatternSuffix }.map { pattern ->
        val terminal = pattern.lastStop.name.ifBlank { summary.shortName }
        val origin = pattern.firstStop.name.ifBlank { summary.shortName }
        Direction(
            id = azryDirectionId(id, pattern.patternSuffix),
            name = azryText("$origin → $terminal"),
            headsign = azryText(pattern.headsign.orEmpty().ifBlank { terminal }),
        )
    }
    return Route(
        id = azryPublicId(PublicEntityType.Route, id),
        providerId = azrySuffix(id),
        shortName = summary.shortName,
        longName = azryText(summary.longName.orEmpty().ifBlank { summary.shortName }),
        color = color?.takeIf(AzryColor::matches)?.let { "#$it" } ?: "#11518A",
        textColor = "#FFFFFF",
        mode = TransitModeValues.Bus,
        directions = directions,
    )
}

internal fun AzryStop.normalized(routes: List<String>): Stop {
    val publicId = azryPublicId(PublicEntityType.Stop, id)
    val stopCode = code.orEmpty().ifBlank { id }
    val position = GeoPoint(lat, lon)
    val label = KutaisiStopNames.find(publicId, stopCode, position)
        ?: name.takeIf(String::isNotBlank)?.let(::azryText)
        ?: LocalizedText(ru = "Автобусная остановка", en = "Bus stop", ka = "ავტობუსის გაჩერება")
    return Stop(
        id = publicId,
        providerId = azrySuffix(id),
        code = stopCode,
        name = label,
        position = position,
        routeIds = routes,
        mode = TransitModeValues.Bus,
    )
}
