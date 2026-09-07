package com.denis.georgiatransit.bff.control

import com.denis.georgiatransit.bff.FakeAdapter
import com.denis.georgiatransit.bff.transitBffModule
import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityNotFound
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.capabilities
import com.denis.georgiatransit.bff.direction
import com.denis.georgiatransit.bff.route
import com.denis.georgiatransit.bff.stop
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.service.TransitService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Collections
import java.util.Comparator
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private val fixtureAvailability = CityAvailability(
    readiness = CityReadiness.DEVELOPMENT_FIXTURE,
    source = CitySource.FIXTURE,
)

private val privateFilePermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)
private val privateDirectoryPermissions = privateFilePermissions + PosixFilePermission.OWNER_EXECUTE

class RuntimeCapabilityControlTest {
    @Test
    fun `explicit development fixture is the only no-document activation path`() {
        val adapter = fixtureAdapter()
        val developmentConfig = BffConfig.fromEnvironment(mapOf("BFF_FIXTURES_ENABLED" to "true"))
        RuntimeCapabilityControl(developmentConfig, ProviderRegistry(listOf(adapter)), {}).use { control ->
            control.start()
            assertEquals("development-fixture", control.current().revision)
            assertEquals(listOf("test"), control.current().cities.keys.toList())
        }

        val closedConfig = BffConfig.fromEnvironment(emptyMap())
        RuntimeCapabilityControl(closedConfig, ProviderRegistry(listOf(adapter)), {}).use { control ->
            control.start()
            assertClosed(control)
            assertFailsWith<CityNotFound> { control.current().city("kutaisi") }
        }
    }

    @Test
    fun `valid configured document atomically activates normalized fixture city and private history`() =
        withControlFiles { files ->
            files.writeControl(capabilityDocument(revision = "fixture-1"), readOnly = true)
            val audits = synchronizedAudits()
            RuntimeCapabilityControl(files.config(), ProviderRegistry(listOf(fixtureAdapter())), audits::add).use { control ->
                control.start()

                val snapshot = control.current()
                assertEquals("fixture-1", snapshot.revision)
                assertEquals(listOf("test"), snapshot.cities.keys.toList())
                assertEquals(fixtureAvailability, snapshot.city("test").city.availability)
                assertEquals(capabilities(), snapshot.city("test").city.capabilities)
                assertTrue(audits.any { it.contains("event=accepted transition=accepted revision=fixture-1") })
            }

            assertEquals(setOf(PosixFilePermission.OWNER_READ), Files.getPosixFilePermissions(files.controlPath))
            assertEquals(privateDirectoryPermissions, Files.getPosixFilePermissions(files.stateDirectory))
            assertEquals(
                privateDirectoryPermissions,
                Files.getPosixFilePermissions(files.stateDirectory.resolve("documents")),
            )
            assertEquals(
                privateFilePermissions,
                Files.getPosixFilePermissions(files.stateDirectory.resolve("history-index.json")),
            )
            assertEquals(
                privateFilePermissions,
                Files.getPosixFilePermissions(files.stateDirectory.resolve("documents/fixture-1.json")),
            )
        }

    @Test
    fun `invalid unsafe oversized and deeply nested documents fail closed without a crash`() {
        val cases = listOf(
            "unknown key" to validDocumentWithUnknownKey(),
            "duplicate JSON key" to validDocumentWithDuplicateRevision(),
            "duplicate city" to capabilityDocument(
                revision = "duplicate-city",
                cityEntries = listOf(cityDocument("test"), cityDocument("test")),
            ),
            "unknown Kutaisi city" to capabilityDocument(
                revision = "unknown-city",
                cityEntries = listOf(cityDocument("kutaisi")),
            ),
            "unsafe availability metadata" to capabilityDocument(
                revision = "unsafe-availability",
                availability = CityAvailability(CityReadiness.PRODUCTION_READY, CitySource.REVIEWED_ADAPTER),
            ),
            "capability expansion" to capabilityDocument(revision = "expanded", capabilityValues = capabilities()),
            "maximum byte overflow" to " ".repeat(65_537),
            "more than 64 nested values" to "[".repeat(65) + "0" + "]".repeat(65),
        )

        cases.forEach { (name, document) ->
            withControlFiles { files ->
                val adapter = if (name == "capability expansion") {
                    fixtureAdapter(capabilityValues = capabilities(routes = false))
                } else {
                    fixtureAdapter()
                }
                val audits = synchronizedAudits()
                files.writeControl(document)
                RuntimeCapabilityControl(files.config(), ProviderRegistry(listOf(adapter)), audits::add).use { control ->
                    control.start()
                    assertClosed(control)
                    assertTrue(audits.any { it.contains("event=rejected") }, name)
                }
            }
        }
    }

    @Test
    fun `malformed or missing update retains persisted last known good after restart`() = withControlFiles { files ->
        val audits = synchronizedAudits()
        files.writeControl(capabilityDocument(revision = "known-good"))
        RuntimeCapabilityControl(files.config(), ProviderRegistry(listOf(fixtureAdapter())), audits::add).use { control ->
            control.start()
            assertEquals("known-good", control.current().revision)

            files.writeControl("{not-json")
            awaitAudit(audits, "reason=invalid_document")
            assertEquals("known-good", control.current().revision)
        }

        Files.deleteIfExists(files.controlPath)
        RuntimeCapabilityControl(files.config(), ProviderRegistry(listOf(fixtureAdapter())), audits::add).use { restored ->
            restored.start()
            assertEquals("known-good", restored.current().revision)
            assertTrue(audits.any { it.contains("transition=restored revision=known-good") })
        }
    }

    @Test
    fun `exact historical rollback is accepted while mutated reused revision is rejected`() = withControlFiles { files ->
        val first = capabilityDocument(revision = "revision-1")
        val second = capabilityDocument(revision = "revision-2", capabilityValues = capabilities(routes = false))
        val mutatedFirst = capabilityDocument(revision = "revision-1", capabilityValues = capabilities(stops = false))
        val audits = synchronizedAudits()
        files.writeControl(first)

        RuntimeCapabilityControl(files.config(historyLimit = 3), ProviderRegistry(listOf(fixtureAdapter())), audits::add).use { control ->
            control.start()
            awaitRevision(control, "revision-1")

            files.writeControl(second)
            awaitRevision(control, "revision-2")
            assertFalse(control.current().city("test").city.capabilities.routes)

            files.writeControl(first)
            awaitRevision(control, "revision-1")
            assertTrue(control.current().city("test").city.capabilities.routes)
            assertTrue(audits.any { it.contains("transition=rollback revision=revision-1") })

            val acceptedGeneration = control.current().generation
            files.writeControl(mutatedFirst)
            awaitAudit(audits, "reason=duplicate_revision")
            assertEquals("revision-1", control.current().revision)
            assertEquals(acceptedGeneration, control.current().generation)
        }
    }

    @Test
    fun `history is bounded and its active index references complete recent documents`() = withControlFiles { files ->
        files.writeControl(capabilityDocument(revision = "history-1"))
        RuntimeCapabilityControl(files.config(historyLimit = 2), ProviderRegistry(listOf(fixtureAdapter())), {}).use { control ->
            control.start()
            files.writeControl(capabilityDocument(revision = "history-2"))
            awaitRevision(control, "history-2")
            files.writeControl(capabilityDocument(revision = "history-3"))
            awaitRevision(control, "history-3")
        }

        val index = Files.readString(files.stateDirectory.resolve("history-index.json"))
        assertFalse(index.contains("history-1"))
        assertTrue(index.contains("history-2"))
        assertTrue(index.contains("history-3"))
        assertFalse(Files.exists(files.stateDirectory.resolve("documents/history-1.json")))
        assertTrue(Files.isRegularFile(files.stateDirectory.resolve("documents/history-2.json")))
        assertTrue(Files.isRegularFile(files.stateDirectory.resolve("documents/history-3.json")))
    }

    @Test
    fun `reload disables city immediately for new calls without stale directory cache`() = withControlFiles { files ->
        val adapter = fixtureAdapter()
        val audits = synchronizedAudits()
        files.writeControl(capabilityDocument(revision = "enabled"))
        val control = RuntimeCapabilityControl(files.config(), ProviderRegistry(listOf(adapter)), audits::add)
        val service = service(control)
        try {
            control.start()
            assertEquals(listOf("test"), runBlocking { service.cities().map { it.id } })
            assertEquals(listOf(route), runBlocking { service.routes("test", "en", null) })
            assertEquals(1, adapter.routesCalls.get())

            files.writeControl(capabilityDocument(revision = "disabled", enabled = false))
            awaitRevision(control, "disabled")
            assertTrue(runBlocking { service.cities() }.isEmpty())
            assertFailsWith<CityNotFound> { runBlocking { service.routes("test", "en", null) } }
            assertEquals(1, adapter.routesCalls.get())
        } finally {
            service.close()
            control.close()
        }
    }

    @Test
    fun `configured cities endpoint and scoped endpoint share one effective capability snapshot`() =
        withControlFiles { files ->
            val disabledRoutes = capabilities(routes = false)
            files.writeControl(
                capabilityDocument(
                    revision = "endpoint-snapshot",
                    cityEntries = listOf(cityDocument("demo", capabilityValues = disabledRoutes)),
                ),
            )
            testApplication {
                application {
                    transitBffModule(files.config())
                }

                val city = Json.parseToJsonElement(client.get("/v1/cities").bodyAsText())
                    .jsonArray
                    .single()
                    .jsonObject
                assertEquals("DEVELOPMENT_FIXTURE", city.getValue("availability").jsonObject.getValue("readiness").jsonPrimitive.content)
                assertEquals("FIXTURE", city.getValue("availability").jsonObject.getValue("source").jsonPrimitive.content)
                assertFalse(city.getValue("capabilities").jsonObject.getValue("routes").jsonPrimitive.boolean)

                assertEquals(HttpStatusCode.NotImplemented, client.get("/v1/cities/demo/routes").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/v1/cities/kutaisi/routes").status)
            }
        }

    @Test
    fun `each capability flag returns 501 before its provider operation`() {
        featureCases.forEach { feature ->
            withControlFiles { files ->
                val adapter = fixtureAdapter()
                val disabled = feature.disable(capabilities())
                files.writeControl(capabilityDocument(revision = "disable-${feature.name}", capabilityValues = disabled))
                val control = RuntimeCapabilityControl(files.config(), ProviderRegistry(listOf(adapter)), {})
                val service = service(control)
                try {
                    control.start()
                    assertEquals(disabled, runBlocking { service.cities().single().capabilities }, feature.name)
                    assertFailsWith<CapabilityNotAvailable> { runBlocking { feature.invoke(service) } }
                    assertEquals(0, feature.providerCalls(adapter), feature.name)
                } finally {
                    service.close()
                    control.close()
                }
            }
        }
    }

    @Test
    fun `in flight request retains captured snapshot while later generation clears route cache`() = runTest {
        val adapter = fixtureAdapter()
        val initial = snapshot(generation = 1, adapter = adapter)
        val source = MutableSnapshotSource(initial)
        val service = service(source)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        adapter.routesResult = {
            started.complete(Unit)
            release.await()
            listOf(route)
        }
        try {
            val first = async { service.routes("test", "en", null) }
            started.await()
            source.update(snapshot(generation = 2, adapter = adapter, capabilityValues = capabilities(routes = false)))

            release.complete(Unit)
            assertEquals(listOf(route), first.await())
            assertFailsWith<CapabilityNotAvailable> { service.routes("test", "en", null) }

            source.update(snapshot(generation = 3, adapter = adapter))
            assertEquals(listOf(route), service.routes("test", "en", null))
            assertEquals(2, adapter.routesCalls.get())
        } finally {
            service.close()
            source.close()
        }
    }

    @Test
    fun `audit records contain only fixed fields and close stops polling work`() = withControlFiles { files ->
        val audits = synchronizedAudits()
        files.writeControl(capabilityDocument(revision = "audit-safe"))
        val control = RuntimeCapabilityControl(files.config(), ProviderRegistry(listOf(fixtureAdapter())), audits::add)
        control.start()
        files.writeControl("{\"secret-provider-url\":\"https://unsafe.example\"}")
        awaitAudit(audits, "reason=invalid_document")
        val beforeClose = audits.toList()
        control.close()

        files.writeControl(capabilityDocument(revision = "after-close"))
        Thread.sleep(5_250)
        assertEquals(beforeClose, audits.toList())
        val auditText = audits.joinToString("\n")
        assertFalse(auditText.contains("secret-provider-url"))
        assertFalse(auditText.contains("unsafe.example"))
        assertFalse(auditText.contains(files.controlPath.toString()))
        assertTrue(audits.all { it.startsWith("capability_control event=") })
    }
}

private data class FeatureCase(
    val name: String,
    val disable: (CityCapabilities) -> CityCapabilities,
    val invoke: suspend (TransitService) -> Unit,
    val providerCalls: (FakeAdapter) -> Int,
)

private val featureCases = listOf(
    FeatureCase(
        name = "routes",
        disable = { it.copy(routes = false) },
        invoke = { it.routes("test", "en", null) },
        providerCalls = { it.routesCalls.get() },
    ),
    FeatureCase(
        name = "stops",
        disable = { it.copy(stops = false) },
        invoke = { it.nearbyStops("test", GeoPoint(41.7, 44.8), 100, 1, "en") },
        providerCalls = { it.stopDirectoryCalls.get() },
    ),
    FeatureCase(
        name = "routeGeometry",
        disable = { it.copy(routeGeometry = false) },
        invoke = { it.shape("test", route.id, direction.id) },
        providerCalls = { it.shapeCalls.get() },
    ),
    FeatureCase(
        name = "vehiclePositions",
        disable = { it.copy(vehiclePositions = false) },
        invoke = { it.vehicles("test", route.id, null) },
        providerCalls = { it.vehiclesCalls.get() },
    ),
    FeatureCase(
        name = "arrivals",
        disable = { it.copy(arrivals = false, officialArrivals = false) },
        invoke = { it.arrivals("test", stop.id, 1, "en") },
        providerCalls = { it.arrivalsCalls.get() },
    ),
    FeatureCase(
        name = "tripPlanning",
        disable = { it.copy(tripPlanning = false) },
        invoke = {
            it.journeys(
                "test",
                JourneyQuery(
                    from = GeoPoint(41.7, 44.8),
                    to = GeoPoint(41.8, 44.9),
                    departureAt = Instant.parse("2030-01-01T00:00:00Z"),
                    locale = "en",
                    maxTransfers = 0,
                ),
            )
        },
        providerCalls = { it.journeysCalls.get() },
    ),
)

private fun fixtureAdapter(capabilityValues: CityCapabilities = capabilities()): FakeAdapter = FakeAdapter(
    city = FakeAdapter().city.copy(capabilities = capabilityValues, availability = fixtureAvailability),
)

private fun service(capabilitySnapshots: CapabilitySnapshotSource): TransitService = TransitService(
    capabilitySnapshots = capabilitySnapshots,
    directoryCacheTtlSeconds = 3_600,
    shapeCacheTtlSeconds = 3_600,
    realtimeSingleFlightSeconds = 15,
)

private fun snapshot(
    generation: Long,
    adapter: FakeAdapter,
    capabilityValues: CityCapabilities = capabilities(),
): EffectiveCapabilitySnapshot = EffectiveCapabilitySnapshot(
    revision = "generation-$generation",
    generation = generation,
    cities = mapOf(
        "test" to EffectiveCity(adapter, adapter.city.copy(capabilities = capabilityValues)),
    ),
)

private class MutableSnapshotSource(initial: EffectiveCapabilitySnapshot) : CapabilitySnapshotSource {
    private val snapshot = AtomicReference(initial)

    override fun current(): EffectiveCapabilitySnapshot = snapshot.get()

    fun update(next: EffectiveCapabilitySnapshot) {
        snapshot.set(next)
    }

    override fun close() = Unit
}

private data class ControlFiles(
    val rootDirectory: Path,
    val controlPath: Path,
    val stateDirectory: Path,
) {
    fun config(historyLimit: Int = 20): BffConfig = BffConfig.fromEnvironment(
        mapOf(
            "BFF_FIXTURES_ENABLED" to "true",
            "BFF_CAPABILITY_CONTROL_PATH" to controlPath.toString(),
            "BFF_CAPABILITY_CONTROL_STATE_DIR" to stateDirectory.toString(),
            "BFF_CAPABILITY_CONTROL_POLL_SECONDS" to "5",
            "BFF_CAPABILITY_CONTROL_HISTORY_LIMIT" to historyLimit.toString(),
        ),
    )

    fun writeControl(content: String, readOnly: Boolean = false) {
        val temporary = Files.createTempFile(
            rootDirectory,
            "control-update-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(privateFilePermissions),
        )
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            Files.move(
                temporary,
                controlPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Files.setPosixFilePermissions(
                controlPath,
                if (readOnly) setOf(PosixFilePermission.OWNER_READ) else privateFilePermissions,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun withControlFiles(block: (ControlFiles) -> Unit) {
    val parent = Path.of("build/capability-control-tests").toAbsolutePath()
    Files.createDirectories(parent)
    val root = Files.createTempDirectory(parent, "case-", PosixFilePermissions.asFileAttribute(privateDirectoryPermissions))
    val files = ControlFiles(
        rootDirectory = root,
        controlPath = root.resolve("control.json"),
        stateDirectory = root.resolve("state"),
    )
    Files.createDirectory(files.stateDirectory, PosixFilePermissions.asFileAttribute(privateDirectoryPermissions))
    try {
        block(files)
    } finally {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }
}

private fun capabilityDocument(
    revision: String,
    enabled: Boolean = true,
    availability: CityAvailability = fixtureAvailability,
    capabilityValues: CityCapabilities = capabilities(),
    cityEntries: List<String>? = null,
): String =
    """
    {
      "revision": "$revision",
      "cities": [${cityEntries?.joinToString(",") ?: cityDocument("test", enabled, availability, capabilityValues)}]
    }
    """.trimIndent()

private fun cityDocument(
    cityId: String,
    enabled: Boolean = true,
    availability: CityAvailability = fixtureAvailability,
    capabilityValues: CityCapabilities = capabilities(),
): String =
    """{
    "id":"$cityId",
    "enabled":$enabled,
    "availability":{"readiness":"${availability.readiness}","source":"${availability.source}"},
    "capabilities":{
      "routes":${capabilityValues.routes},
      "stops":${capabilityValues.stops},
      "routeGeometry":${capabilityValues.routeGeometry},
      "vehiclePositions":${capabilityValues.vehiclePositions},
      "officialArrivals":${capabilityValues.officialArrivals},
      "tripPlanning":${capabilityValues.tripPlanning},
      "arrivals":${capabilityValues.arrivals}
    }
    }""".trimIndent()

private fun validDocumentWithUnknownKey(): String = capabilityDocument(revision = "unknown-key")
    .replace("\"cities\":", "\"unrecognized\":true,\"cities\":")

private fun validDocumentWithDuplicateRevision(): String = capabilityDocument(revision = "duplicate-key")
    .replace("\"revision\": \"duplicate-key\",", "\"revision\": \"duplicate-key\",\"revision\": \"duplicate-key\",")

private fun assertClosed(control: RuntimeCapabilityControl) {
    assertEquals("closed", control.current().revision)
    assertTrue(control.current().cities.isEmpty())
}

private fun synchronizedAudits(): MutableList<String> = Collections.synchronizedList(mutableListOf())

private fun awaitRevision(control: RuntimeCapabilityControl, expected: String) {
    repeat(160) {
        if (control.current().revision == expected) return
        Thread.sleep(50)
    }
    fail("Timed out waiting for capability revision $expected; was ${control.current().revision}")
}

private fun awaitAudit(audits: List<String>, expectedFragment: String) {
    repeat(160) {
        if (audits.any { it.contains(expectedFragment) }) return
        Thread.sleep(50)
    }
    fail("Timed out waiting for audit containing $expectedFragment; events=$audits")
}
