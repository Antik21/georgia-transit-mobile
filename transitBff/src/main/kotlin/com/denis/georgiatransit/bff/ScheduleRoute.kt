package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.api.InvalidArgument
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.locale
import com.denis.georgiatransit.bff.api.pathCityId
import com.denis.georgiatransit.bff.api.requiredPathPublicId
import com.denis.georgiatransit.bff.observability.HttpOperation
import com.denis.georgiatransit.bff.service.TransitService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal fun Route.transitScheduleRoute(service: TransitService) {
    get("/routes/{routeId}/directions/{directionId}/schedule") {
        call.markHttpOperation(HttpOperation.SCHEDULE)
        val cityId = call.pathCityId()
        val routeId = call.requiredPathPublicId("routeId", cityId, PublicEntityType.Route)
        val directionId = call.requiredPathPublicId("directionId", cityId, PublicEntityType.Direction)
        val value = call.request.queryParameters["date"] ?: throw InvalidArgument("date is required")
        val date = try { LocalDate.parse(value) } catch (_: DateTimeParseException) {
            throw InvalidArgument("date must be an ISO service date")
        }
        if (date.year !in 1970..2100) throw InvalidArgument("date is outside the supported range")
        call.respond(service.schedule(cityId, routeId, directionId, date, call.locale()))
    }
}
