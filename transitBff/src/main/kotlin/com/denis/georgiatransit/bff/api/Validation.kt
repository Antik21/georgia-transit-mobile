package com.denis.georgiatransit.bff.api

import io.ktor.server.application.ApplicationCall
import java.time.Instant

private val cityIdPattern = Regex("[a-z][a-z0-9-]{1,31}")
private val publicIdPattern = Regex("([^:\\s]+):([^:\\s]+):([^:\\s]+):([^:\\s]+)")
private val modes = setOf("bus", "metro", "tram", "ferry")
private val locales = setOf("ka", "en", "ru")

fun ApplicationCall.pathCityId(): String =
    parameters["cityId"]?.takeIf(cityIdPattern::matches)
        ?: throw InvalidArgument("cityId must be a lowercase public city identifier")

fun ApplicationCall.requiredPathPublicId(name: String, cityId: String, entityType: String): String =
    parameters[name]?.also { validatePublicId(it, cityId, entityType, name) }
        ?: throw InvalidArgument("$name is required")

fun ApplicationCall.requiredQueryPublicId(name: String, cityId: String, entityType: String): String =
    request.queryParameters[name]?.also { validatePublicId(it, cityId, entityType, name) }
        ?: throw InvalidArgument("$name is required")

fun validatePublicId(value: String, cityId: String, entityType: String, name: String) {
    val match = publicIdPattern.matchEntire(value)
        ?: throw InvalidArgument("$name must use the public identifier format")
    val (idCity, provider, entity, suffix) = match.destructured
    if (value.length > 256 || idCity != cityId || provider.isBlank() || entity != entityType || suffix.isBlank()) {
        throw InvalidArgument("$name must use the public identifier format")
    }
}

fun ApplicationCall.locale(): String {
    val locale = request.queryParameters["locale"] ?: return "en"
    return locale.takeIf(locales::contains)
        ?: throw InvalidArgument("locale must be one of ka, en, ru")
}

fun ApplicationCall.mode(): String? {
    val mode = request.queryParameters["mode"] ?: return null
    return mode.takeIf(modes::contains)
        ?: throw InvalidArgument("mode must be one of bus, metro, tram, ferry")
}

fun ApplicationCall.requiredQueryDouble(name: String, minimum: Double, maximum: Double): Double {
    val raw = request.queryParameters[name] ?: throw InvalidArgument("$name is required")
    val value = raw.toDoubleOrNull()?.takeIf(Double::isFinite)
        ?: throw InvalidArgument("$name must be a finite number")
    return value.takeIf { it in minimum..maximum }
        ?: throw InvalidArgument("$name must be between $minimum and $maximum")
}

fun ApplicationCall.requiredQueryInt(name: String, minimum: Int, maximum: Int): Int {
    val raw = request.queryParameters[name] ?: throw InvalidArgument("$name is required")
    val value = raw.toIntOrNull() ?: throw InvalidArgument("$name must be a whole number")
    return value.takeIf { it in minimum..maximum }
        ?: throw InvalidArgument("$name must be between $minimum and $maximum")
}

fun ApplicationCall.optionalQueryInt(name: String, default: Int, minimum: Int, maximum: Int): Int {
    val raw = request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: throw InvalidArgument("$name must be a whole number")
    return value.takeIf { it in minimum..maximum }
        ?: throw InvalidArgument("$name must be between $minimum and $maximum")
}

fun ApplicationCall.requiredInstant(name: String): Instant {
    val raw = request.queryParameters[name] ?: throw InvalidArgument("$name is required")
    return try {
        Instant.parse(raw)
    } catch (_: Exception) {
        throw InvalidArgument("$name must be an ISO-8601 UTC timestamp")
    }
}
