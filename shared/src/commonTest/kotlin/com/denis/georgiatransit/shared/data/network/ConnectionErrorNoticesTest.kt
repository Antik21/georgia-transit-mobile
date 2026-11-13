package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.repository.ConnectionErrorNotices
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionErrorNoticesTest {
    @Test
    fun repeatedFailuresInOneOutageProduceOneNoticeUntilRearmed() = runTest {
        val notices = ConnectionErrorNotices()
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notices.notices.toList(received) }

        repeat(3) { notices.onTransportFailure() }
        assertEquals(1, received.size)

        notices.rearm()
        notices.onTransportFailure()
        notices.onTransportFailure()
        assertEquals(2, received.size)
    }

    @Test
    fun aFailureBeforeTheMapCollectsIsDeliveredLaterUnlessTheBffRecoveredFirst() = runTest {
        val notices = ConnectionErrorNotices()
        notices.onTransportFailure()
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notices.notices.toList(received) }
        assertEquals(1, received.size)

        val recovered = ConnectionErrorNotices()
        recovered.onTransportFailure()
        recovered.rearm()
        val none = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recovered.notices.toList(none) }
        assertEquals(0, none.size)
    }

    @Test
    fun clientReportsOnlyTransportFailuresAndRearmsOnAnyBffResponse() = runTest {
        val notices = ConnectionErrorNotices()
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notices.notices.toList(received) }
        var online = false
        var status = HttpStatusCode.OK
        val client = TransitBffClient(
            httpClient = HttpClient(MockEngine { _ ->
                if (!online) throw IOException("offline")
                respond(
                    content = if (status == HttpStatusCode.OK) VehiclePageJson else ErrorJson,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }) { install(ContentNegotiation) { json() } },
            endpoint = BffEndpointConfiguration("https://bff.example"),
            connectionNotices = notices,
        )
        val city = CityId("demo")
        val route = RouteId("demo:fixture:route:blue")

        assertIs<TransitFailure.Transport>(assertIs<TransitLoadResult.Failure>(client.vehicles(city, route, null)).error)
        client.vehicles(city, route, null)
        assertEquals(1, received.size, "Polling within one outage must not repeat the notice.")

        online = true
        status = HttpStatusCode.ServiceUnavailable
        client.vehicles(city, route, null)
        assertEquals(1, received.size, "An HTTP error proves the BFF is reachable and is not a connection notice.")

        online = false
        client.vehicles(city, route, null)
        assertEquals(2, received.size, "A new outage after the BFF answered must notify again.")
    }

    private companion object {
        const val VehiclePageJson = """{"items":[],"observedAt":"2030-01-01T00:00:00Z","maxAgeSeconds":0,"stale":false}"""
        const val ErrorJson = """{"error":{"code":"UPSTREAM_UNAVAILABLE","message":"down","requestId":"req-1"}}"""
    }
}
