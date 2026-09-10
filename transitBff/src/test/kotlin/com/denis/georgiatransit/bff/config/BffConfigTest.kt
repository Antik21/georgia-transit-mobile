package com.denis.georgiatransit.bff.config

import com.denis.georgiatransit.bff.transitBffModule
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BffConfigTest {
    @Test
    fun `defaults are safe local development values`() {
        val config = BffConfig.fromEnvironment(emptyMap())

        assertEquals(RuntimeMode.DEVELOPMENT, config.mode)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals(14_400, config.directoryCacheTtlSeconds)
        assertEquals(86_400, config.shapeCacheTtlSeconds)
        assertEquals(15, config.realtimeSingleFlightSeconds)
        assertFalse(config.fixturesEnabled)
        assertFalse(config.transitous.enabled)
        assertFalse(config.transitous.isActivated)
        assertFalse(config.transitous.isRoutingApproved)
        assertFalse(config.ttc.enabled)
        assertFalse(config.ttc.isActivated)
        assertTrue(config.metricsEnabled)
        assertFalse(config.probesEnabled)
        assertEquals(60, config.probeIntervalSeconds)
        assertEquals(3, config.circuitFailureThreshold)
        assertEquals(300, config.schemaDriftWindowSeconds)
    }

    @Test
    fun `inclusive boundary values are accepted`() {
        val config = BffConfig.fromEnvironment(
            mapOf(
                "BFF_MODE" to "PRODUCTION",
                "BFF_HOST" to "0.0.0.0",
                "BFF_PORT" to "65535",
                "BFF_DIRECTORY_CACHE_TTL_SECONDS" to "3600",
                "BFF_SHAPE_CACHE_TTL_SECONDS" to "3600",
                "BFF_REALTIME_SINGLE_FLIGHT_SECONDS" to "60",
                "BFF_FIXTURES_ENABLED" to "false",
            ),
        )

        assertEquals(RuntimeMode.PRODUCTION, config.mode)
        assertEquals(65_535, config.port)
    }

    @Test
    fun `invalid environment values fail with configuration error`() {
        val invalid = listOf(
            mapOf("BFF_MODE" to "test"),
            mapOf("BFF_HOST" to ""),
            mapOf("BFF_PORT" to "0"),
            mapOf("BFF_PORT" to "abc"),
            mapOf("BFF_DIRECTORY_CACHE_TTL_SECONDS" to "3599"),
            mapOf("BFF_DIRECTORY_CACHE_TTL_SECONDS" to "21601"),
            mapOf("BFF_SHAPE_CACHE_TTL_SECONDS" to "3599"),
            mapOf("BFF_SHAPE_CACHE_TTL_SECONDS" to "86401"),
            mapOf("BFF_REALTIME_SINGLE_FLIGHT_SECONDS" to "0"),
            mapOf("BFF_REALTIME_SINGLE_FLIGHT_SECONDS" to "61"),
            mapOf("BFF_FIXTURES_ENABLED" to "yes"),
        )

        invalid.forEach { environment -> assertFailsWith<BffConfigurationException> { BffConfig.fromEnvironment(environment) } }
    }

    @Test
    fun `production rejects fixture adapter`() {
        assertFailsWith<BffConfigurationException> {
            BffConfig.fromEnvironment(mapOf("BFF_MODE" to "production", "BFF_FIXTURES_ENABLED" to "true"))
        }
    }

    @Test
    fun `observability and interlock settings have strict bounds and production activation fails closed`() {
        val invalid = listOf(
            mapOf("BFF_METRICS_ENABLED" to "yes"),
            mapOf("BFF_PROBES_ENABLED" to "yes"),
            mapOf("BFF_PROBE_INTERVAL_SECONDS" to "59"),
            mapOf("BFF_PROBE_INTERVAL_SECONDS" to "301"),
            mapOf("BFF_CIRCUIT_FAILURE_THRESHOLD" to "1"),
            mapOf("BFF_CIRCUIT_FAILURE_THRESHOLD" to "11"),
            mapOf("BFF_CIRCUIT_WINDOW_SECONDS" to "9"),
            mapOf("BFF_CIRCUIT_WINDOW_SECONDS" to "3601"),
            mapOf("BFF_CIRCUIT_OPEN_SECONDS" to "4"),
            mapOf("BFF_CIRCUIT_OPEN_SECONDS" to "601"),
            mapOf("BFF_SCHEMA_DRIFT_THRESHOLD" to "1"),
            mapOf("BFF_SCHEMA_DRIFT_THRESHOLD" to "11"),
            mapOf("BFF_SCHEMA_DRIFT_WINDOW_SECONDS" to "59"),
            mapOf("BFF_SCHEMA_DRIFT_WINDOW_SECONDS" to "3601"),
        )
        invalid.forEach { environment ->
            assertFailsWith<BffConfigurationException> { BffConfig.fromEnvironment(environment) }
        }

        val productionTransitous = transitousEnvironment() + mapOf(
            "BFF_MODE" to "production",
            "BFF_FIXTURES_ENABLED" to "false",
            "BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json",
            "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state",
        )
        assertFailsWith<BffConfigurationException> { BffConfig.fromEnvironment(productionTransitous) }
        assertTrue(
            BffConfig.fromEnvironment(productionTransitous + ("BFF_SCHEMA_INTERLOCK_ENABLED" to "true"))
                .schemaInterlockEnabled,
        )
    }

    @Test
    fun `capability control requires a complete nonblank path pair and bounded settings`() {
        val valid = BffConfig.fromEnvironment(
            mapOf(
                "BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json",
                "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state",
                "BFF_CAPABILITY_CONTROL_POLL_SECONDS" to "5",
                "BFF_CAPABILITY_CONTROL_HISTORY_LIMIT" to "50",
            ),
        )
        assertEquals(5, valid.capabilityControlPollSeconds)
        assertEquals(50, valid.capabilityControlHistoryLimit)

        val invalid = listOf(
            mapOf("BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json"),
            mapOf("BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state"),
            mapOf("BFF_CAPABILITY_CONTROL_PATH" to " ", "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state"),
            mapOf("BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json", "BFF_CAPABILITY_CONTROL_STATE_DIR" to " "),
            mapOf("BFF_CAPABILITY_CONTROL_PATH" to "\u0000", "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state"),
            mapOf(
                "BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json",
                "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state",
                "BFF_CAPABILITY_CONTROL_POLL_SECONDS" to "4",
            ),
            mapOf(
                "BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json",
                "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state",
                "BFF_CAPABILITY_CONTROL_POLL_SECONDS" to "301",
            ),
            mapOf(
                "BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json",
                "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state",
                "BFF_CAPABILITY_CONTROL_HISTORY_LIMIT" to "1",
            ),
            mapOf(
                "BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json",
                "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state",
                "BFF_CAPABILITY_CONTROL_HISTORY_LIMIT" to "51",
            ),
        )
        invalid.forEach { environment ->
            assertFailsWith<BffConfigurationException> { BffConfig.fromEnvironment(environment) }
        }
    }

    @Test
    fun `production startup fails closed without a production adapter`() = testApplication {
        application {
            assertFailsWith<BffConfigurationException> {
                transitBffModule(
                    BffConfig.fromEnvironment(mapOf("BFF_MODE" to "production")),
                )
            }
        }
    }

    @Test
    fun `TTC activation is opt in fail closed and redacts the server credential`() {
        val disabledWithValues = BffConfig.fromEnvironment(
            mapOf(
                "TTC_BASE_URL" to "https://ttc.example/api/v2",
                "TTC_API_KEY" to "disabled-secret",
            ),
        ).ttc
        assertFalse(disabledWithValues.enabled)
        assertFalse(disabledWithValues.isActivated)

        val active = BffConfig.fromEnvironment(ttcEnvironment()).ttc
        assertTrue(active.enabled)
        assertTrue(active.isActivated)
        assertEquals("https://ttc.example/api/v2", active.baseUrl.toString())
        assertFalse(active.toString().contains("test-only-ttc-key"))
        assertFalse(active.toString().contains("ttc.example"))
        assertTrue(active.toString().contains("activated=true"))

        listOf(
            ttcEnvironment() - "TTC_BASE_URL",
            ttcEnvironment() - "TTC_API_KEY",
            ttcEnvironment() + ("TTC_BASE_URL" to " "),
            ttcEnvironment() + ("TTC_API_KEY" to " "),
            ttcEnvironment() + ("TTC_API_KEY" to "bad\nsecret"),
            ttcEnvironment() + ("TTC_API_KEY" to "x".repeat(513)),
            ttcEnvironment() + ("TTC_PROBE_REALTIME_EXPECTED" to "true"),
        ).forEach { environment ->
            assertFailsWith<BffConfigurationException> { BffConfig.fromEnvironment(environment) }
        }
    }

    @Test
    fun `TTC accepts only explicit HTTPS URL and bounded transport settings`() {
        listOf(
            "http://ttc.example/api/v2",
            "//ttc.example/api/v2",
            "https:///api/v2",
            "https://user:password@ttc.example/api/v2",
            "https://ttc.example/api/v2?key=secret",
            "https://ttc.example/api/v2#fragment",
        ).forEach { url ->
            assertFailsWith<BffConfigurationException>(url) {
                BffConfig.fromEnvironment(ttcEnvironment() + ("TTC_BASE_URL" to url))
            }
        }

        listOf(
            "TTC_REQUEST_TIMEOUT_MILLIS" to "499",
            "TTC_REQUEST_TIMEOUT_MILLIS" to "10001",
            "TTC_CONNECT_TIMEOUT_MILLIS" to "249",
            "TTC_CONNECT_TIMEOUT_MILLIS" to "5001",
            "TTC_SOCKET_TIMEOUT_MILLIS" to "499",
            "TTC_SOCKET_TIMEOUT_MILLIS" to "10001",
            "TTC_MAXIMUM_RETRIES" to "3",
            "TTC_MAXIMUM_CONCURRENT_REQUESTS" to "0",
            "TTC_MAXIMUM_CONCURRENT_REQUESTS" to "5",
            "TTC_MAXIMUM_STARTS_PER_MINUTE" to "0",
            "TTC_MAXIMUM_STARTS_PER_MINUTE" to "121",
            "TTC_PROBE_STOP_ID" to "unsafe stop",
        ).forEach { (name, value) ->
            assertFailsWith<BffConfigurationException>(name) {
                BffConfig.fromEnvironment(ttcEnvironment() + (name to value))
            }
        }
        assertFailsWith<BffConfigurationException> {
            BffConfig.fromEnvironment(
                ttcEnvironment() + mapOf(
                    "TTC_REQUEST_TIMEOUT_MILLIS" to "500",
                    "TTC_SOCKET_TIMEOUT_MILLIS" to "501",
                ),
            )
        }
    }

    @Test
    fun `production TTC requires schema interlock state and excludes Transitous`() {
        val production = ttcEnvironment() + mapOf(
            "BFF_MODE" to "production",
            "BFF_CAPABILITY_CONTROL_PATH" to "/private/control.json",
            "BFF_CAPABILITY_CONTROL_STATE_DIR" to "/private/state",
        )
        assertFailsWith<BffConfigurationException> { BffConfig.fromEnvironment(production) }
        assertTrue(
            BffConfig.fromEnvironment(production + ("BFF_SCHEMA_INTERLOCK_ENABLED" to "true"))
                .ttc.isActivated,
        )
        assertFailsWith<BffConfigurationException> {
            BffConfig.fromEnvironment(ttcEnvironment() + transitousEnvironment())
        }
    }

    @Test
    fun `Transitous activation is fail closed until policy contact and version evidence are complete`() {
        val valid = BffConfig.fromEnvironment(transitousEnvironment())

        assertTrue(valid.transitous.isActivated)
        assertFalse(valid.transitous.isRoutingApproved)
        assertEquals("GeorgiaTransitBff/2026.9.8 (contact: transit-ops@example.com)", valid.transitous.userAgent())

        listOf(
            transitousEnvironment() - "BFF_RELEASE_VERSION",
            transitousEnvironment() - "TRANSITOUS_CONTACT",
            transitousEnvironment() - "TRANSITOUS_ELIGIBILITY_ACKNOWLEDGED",
            transitousEnvironment() - "TRANSITOUS_ELIGIBILITY_REFERENCE",
            transitousEnvironment() - "TRANSITOUS_CONTACT_ACKNOWLEDGED",
            transitousEnvironment() + ("TRANSITOUS_ELIGIBILITY_ACKNOWLEDGED" to "false"),
            transitousEnvironment() + ("BFF_RELEASE_VERSION" to "unsafe version"),
        ).forEach { environment ->
            assertFailsWith<BffConfigurationException> { BffConfig.fromEnvironment(environment) }
        }
    }

    @Test
    fun `Transitous accepts only official HTTPS origin and meaningful operator contacts`() {
        val contacts = listOf("transit-ops@example.com", "https://status.example.com/transitous", "http://ops.example.com/contact")
        contacts.forEach { contact ->
            assertTrue(BffConfig.fromEnvironment(transitousEnvironment() + ("TRANSITOUS_CONTACT" to contact)).transitous.isActivated)
        }

        listOf(
            "http://api.transitous.org",
            "https://api.transitous.org.evil.example",
            "https://staging.api.transitous.org",
            "https://api.transitous.org/path",
            "https://api.transitous.org?redirect=https://evil.example",
        ).forEach { origin ->
            assertFailsWith<BffConfigurationException> {
                BffConfig.fromEnvironment(transitousEnvironment() + ("TRANSITOUS_BASE_URL" to origin))
            }
        }
        listOf("ops", "mailto:ops@example.com", "/contact", "https://", "ops @example.com").forEach { contact ->
            assertFailsWith<BffConfigurationException> {
                BffConfig.fromEnvironment(transitousEnvironment() + ("TRANSITOUS_CONTACT" to contact))
            }
        }
    }

    @Test
    fun `Transitous routing remains off without a separately acknowledged safe approval reference`() {
        val activated = transitousEnvironment()
        assertFalse(BffConfig.fromEnvironment(activated + ("TRANSITOUS_ROUTING_APPROVAL_ACKNOWLEDGED" to "true")).transitous.isRoutingApproved)

        val approved = BffConfig.fromEnvironment(
            activated + mapOf(
                "TRANSITOUS_ROUTING_APPROVAL_ACKNOWLEDGED" to "true",
                "TRANSITOUS_ROUTING_APPROVAL_REFERENCE" to "approval/DEN-56",
            ),
        )
        assertTrue(approved.transitous.isRoutingApproved)
        assertFailsWith<BffConfigurationException> {
            BffConfig.fromEnvironment(
                activated + mapOf(
                    "TRANSITOUS_ROUTING_APPROVAL_ACKNOWLEDGED" to "true",
                    "TRANSITOUS_ROUTING_APPROVAL_REFERENCE" to "approval with a secret",
                ),
            )
        }
    }

    private fun transitousEnvironment(): Map<String, String> = mapOf(
        "TRANSITOUS_ENABLED" to "true",
        "BFF_RELEASE_VERSION" to "2026.9.8",
        "TRANSITOUS_CONTACT" to "transit-ops@example.com",
        "TRANSITOUS_ELIGIBILITY_ACKNOWLEDGED" to "true",
        "TRANSITOUS_ELIGIBILITY_REFERENCE" to "policy/DEN-56",
        "TRANSITOUS_CONTACT_ACKNOWLEDGED" to "true",
    )

    private fun ttcEnvironment(): Map<String, String> = mapOf(
        "TTC_ENABLED" to "true",
        "TTC_BASE_URL" to "https://ttc.example/api/v2",
        "TTC_API_KEY" to "test-only-ttc-key",
    )
}
