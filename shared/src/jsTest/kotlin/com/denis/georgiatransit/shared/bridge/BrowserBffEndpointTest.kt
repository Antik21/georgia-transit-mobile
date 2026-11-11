package com.denis.georgiatransit.shared.bridge

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BrowserBffEndpointTest {
    @Test
    fun loopbackHttpRequiresThePageOrigin() {
        val origin = "http://127.0.0.1:5173"
        assertNull(browserBffEndpoint(origin, origin, "127.0.0.1").validationFailure())
        assertNotNull(browserBffEndpoint("http://127.0.0.1:8080", origin, "127.0.0.1").validationFailure())
        assertNotNull(browserBffEndpoint("http://10.0.2.2:5173", "http://10.0.2.2:5173", "10.0.2.2").validationFailure())
        assertNotNull(browserBffEndpoint("http://192.168.1.2:5173", "http://192.168.1.2:5173", "192.168.1.2").validationFailure())
    }

    @Test
    fun httpsKeepsExistingEndpointSupport() {
        val origin = "https://web.example"
        val endpoint = browserBffEndpoint("https://bff.example", origin, "web.example")
        assertNull(endpoint.validationFailure())
        assertNotNull(endpoint.mapStyleUrlOrNull())
    }
}
