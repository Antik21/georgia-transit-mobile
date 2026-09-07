package com.denis.georgiatransit.bff

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.spi.SLF4JServiceProvider

class LoggingRuntimeTest {
    @Test
    fun `runtime contains exactly one SLF4J provider`() {
        val providers = ServiceLoader.load(SLF4JServiceProvider::class.java).toList()

        assertEquals(1, providers.size, "Expected one unambiguous SLF4J runtime provider: $providers")
    }
}
