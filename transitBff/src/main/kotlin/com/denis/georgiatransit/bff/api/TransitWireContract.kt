package com.denis.georgiatransit.bff.api

internal const val MaximumPublicIdLength = 256
internal const val EncodedPolylineShapeFormat = "encoded_polyline"

internal object KnownCityIds {
    const val Demo = "demo"
    const val Tbilisi = "tbilisi"
    const val Batumi = "batumi"
}

internal object TransitModeValues {
    const val Bus = "bus"
    const val Metro = "metro"
    const val Tram = "tram"
    const val Ferry = "ferry"

    val All = setOf(Bus, Metro, Tram, Ferry)
}

internal enum class PublicEntityType(val wireValue: String) {
    Route("route"),
    Stop("stop"),
    Direction("direction"),
    Vehicle("vehicle"),
    Journey("journey"),
    Trip("trip"),
}

internal data class ParsedPublicId(
    val city: String,
    val provider: String,
    val entityType: PublicEntityType,
    val suffix: String,
)

private val CityIdPattern = Regex("[a-z][a-z0-9-]{1,31}")
private val PublicIdPattern = Regex("([^:\\s]+):([^:\\s]+):([^:\\s]+):([^:\\s]+)")
private val AttributionIdPattern = Regex("[a-z][a-z0-9-]{0,31}")

internal fun isValidCityId(value: String): Boolean = CityIdPattern.matches(value)

internal fun isValidAttributionId(value: String): Boolean = AttributionIdPattern.matches(value)

internal fun parsePublicId(
    value: String,
    expectedCityId: String,
    expectedEntityType: PublicEntityType,
): ParsedPublicId? {
    if (value.length > MaximumPublicIdLength) return null
    val match = PublicIdPattern.matchEntire(value) ?: return null
    val (city, provider, entity, suffix) = match.destructured
    if (city != expectedCityId || entity != expectedEntityType.wireValue) return null
    return ParsedPublicId(city, provider, expectedEntityType, suffix)
}

internal fun formatPublicId(
    cityId: String,
    providerId: String,
    entityType: PublicEntityType,
    suffix: String,
): String = "$cityId:$providerId:${entityType.wireValue}:$suffix"
