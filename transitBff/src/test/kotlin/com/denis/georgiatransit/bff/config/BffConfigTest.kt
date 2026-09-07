package com.denis.georgiatransit.bff.config

import com.denis.georgiatransit.bff.transitBffModule
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
    fun `production startup fails closed without a production adapter`() = testApplication {
        application {
            assertFailsWith<BffConfigurationException> {
                transitBffModule(
                    BffConfig.fromEnvironment(mapOf("BFF_MODE" to "production")),
                )
            }
        }
    }
}
