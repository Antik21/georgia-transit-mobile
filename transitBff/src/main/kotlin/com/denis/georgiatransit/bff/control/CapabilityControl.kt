package com.denis.georgiatransit.bff.control

import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityNotFound
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.config.BffConfigurationException
import com.denis.georgiatransit.bff.config.RuntimeMode
import com.denis.georgiatransit.bff.observability.CapabilityTelemetrySnapshot
import com.denis.georgiatransit.bff.observability.CapabilityTelemetryState
import com.denis.georgiatransit.bff.observability.TelemetryCapability
import com.denis.georgiatransit.bff.provider.CityTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.NormalizedResponseValidator
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import java.nio.charset.StandardCharsets
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MaximumCapabilityDocumentBytes = 65_536L
private const val MaximumJsonNestingDepth = 64
private val revisionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val digestPattern = Regex("[0-9a-f]{64}")

private val controlJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    prettyPrint = false
}

/**
 * The immutable result used for one HTTP operation. The service takes a single snapshot before it
 * validates a request or invokes an adapter, so a reload cannot produce a partly old/partly new
 * decision for that request.
 */
data class EffectiveCapabilitySnapshot(
    val revision: String,
    val generation: Long,
    val cities: Map<String, EffectiveCity>,
) {
    val isReady: Boolean get() = cities.isNotEmpty()

    fun city(cityId: String): EffectiveCity =
        cities[cityId] ?: throw CityNotFound("No enabled transit provider serves city '$cityId'")
}

data class EffectiveCity(
    val adapter: CityTransitProviderAdapter,
    val city: City,
)

interface CapabilitySnapshotSource : AutoCloseable {
    fun current(): EffectiveCapabilitySnapshot

    fun isSchemaInterlocked(cityId: String, capability: TelemetryCapability): Boolean = false
}

/** Preserves intrinsic adapter behavior for local service construction and isolated tests. */
class IntrinsicCapabilitySnapshotSource(registry: ProviderRegistry) : CapabilitySnapshotSource {
    private val snapshot = EffectiveCapabilitySnapshot(
        revision = "intrinsic",
        generation = 0,
        cities = registry.registeredAdapters().values
            .associate { adapter -> adapter.city.id to EffectiveCity(adapter, adapter.city) }
            .toSortedMap(),
    )

    override fun current(): EffectiveCapabilitySnapshot = snapshot

    override fun close() = Unit
}

/**
 * Operator-managed, fail-closed capability control-plane.
 *
 * The configured document is a strictly parsed, normalized city/feature allow-list. Its accepted
 * result is derived from the intrinsic adapter capability rather than replacing it. A valid
 * document is persisted before it becomes current, and a filesystem update is visible only by an
 * atomic reference swap after all validation succeeds.
 */
class RuntimeCapabilityControl(
    private val config: BffConfig,
    private val registry: ProviderRegistry,
    private val audit: (String) -> Unit,
    private val capabilityTelemetryObserver: (CapabilityTelemetrySnapshot) -> Unit = {},
) : CapabilitySnapshotSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val initialSnapshot = initialSnapshot()
    private val baseCities = AtomicReference(initialSnapshot.cities)
    private val snapshot = AtomicReference(initialSnapshot)
    private val history = config.capabilityControlStateDirectory?.let {
        CapabilityControlHistory(it, config.capabilityControlHistoryLimit)
    }
    private val schemaInterlocks = if (config.schemaInterlockEnabled) {
        SchemaDriftInterlocks(
            stateDirectory = config.capabilityControlStateDirectory,
            failureThreshold = config.schemaDriftThreshold,
            windowSeconds = config.schemaDriftWindowSeconds,
        )
    } else {
        null
    }

    @Volatile
    private var currentDigest: String? = null

    @Volatile
    private var lastStableInputDigest: String? = null

    @Volatile
    private var lastRejectedInputFingerprint: String? = null

    /** Loads persisted state first, then polls the operator-owned document at a bounded interval. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            schemaInterlocks?.load()
        } catch (_: Exception) {
            throw BffConfigurationException("Schema interlock durable state is unavailable")
        }
        // Loading durable latches can change the effective initial/closed snapshot. Build its
        // generation-tagged telemetry state before restore/reload so a no-history startup still
        // publishes coherent disabled/latch gauges without waiting for traffic.
        val startupTelemetrySnapshot = publishSnapshot(snapshot.get().revision, baseCities.get())
        val restored = restoreLastKnownGood()
        val reloaded = reloadConfiguredDocument()
        if (!restored && !reloaded) publishCapabilityTelemetry(startupTelemetrySnapshot)
        if (config.capabilityControlPath != null) {
            scope.launch {
                while (isActive) {
                    delay(config.capabilityControlPollSeconds * 1_000)
                    reloadConfiguredDocument()
                }
            }
        }
    }

    override fun current(): EffectiveCapabilitySnapshot = snapshot.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }

    /**
     * Synthetic-probe-only safety hook. A durable latch is committed before its reduced snapshot
     * is published, so no request can call the affected provider capability after observation.
     */
    fun observeSchemaDrift(cityId: String, capability: TelemetryCapability): Boolean {
        val outcome = synchronized(this) {
            val interlocks = schemaInterlocks ?: return@synchronized SchemaDriftOutcome(latched = false)
            val city = baseCities.get()[cityId] ?: return@synchronized SchemaDriftOutcome(latched = false)
            if (!city.city.capabilities.capabilityEnabled(capability)) return@synchronized SchemaDriftOutcome(latched = false)
            val revision = snapshot.get().revision
            val latched = try {
                interlocks.observe(cityId, capability, revision)
            } catch (_: Exception) {
                baseCities.set(emptyMap())
                val telemetrySnapshot = publishSnapshot(revision, emptyMap())
                audit("capability_control event=schema_interlock_persist_failed classification=durability_failure")
                return@synchronized SchemaDriftOutcome(latched = false, telemetrySnapshot = telemetrySnapshot)
            }
            if (!latched) return@synchronized SchemaDriftOutcome(latched = false)
            val telemetrySnapshot = publishSnapshot(revision, baseCities.get())
            audit(
                "capability_control event=schema_interlock_latched city=$cityId capability=${capability.wireValue}",
            )
            SchemaDriftOutcome(latched = true, telemetrySnapshot = telemetrySnapshot)
        }
        outcome.telemetrySnapshot?.let(::publishCapabilityTelemetry)
        return outcome.latched
    }

    override fun isSchemaInterlocked(cityId: String, capability: TelemetryCapability): Boolean =
        schemaInterlocks?.isLatched(cityId, capability) == true

    private fun restoreLastKnownGood(): Boolean {
        val telemetrySnapshot = synchronized(this) {
            val historyStore = history ?: return@synchronized null
            val records = try {
                historyStore.load()
            } catch (_: Exception) {
                auditRejected(revision = "unavailable", reason = "history_unavailable", fingerprint = "history")
                return@synchronized null
            }
            records.asReversed().firstNotNullOfOrNull { record ->
                try {
                    val document = parseDocument(record.document)
                    if (document.revision != record.revision || fingerprint(record.document) != record.digest) {
                        null
                    } else {
                        SnapshotCandidate(document, effectiveCities(document), record.digest)
                    }
                } catch (_: Exception) {
                    null
                }
            }?.let { candidate ->
                try {
                    replaceSnapshot(candidate, transition = SnapshotTransition.RESTORED).also {
                        currentDigest = candidate.digest
                    }
                } catch (_: Exception) {
                    baseCities.set(emptyMap())
                    audit("capability_control event=schema_interlock_persist_failed classification=durability_failure")
                    publishSnapshot("closed", emptyMap())
                }
            }
        }
        telemetrySnapshot?.let(::publishCapabilityTelemetry)
        return telemetrySnapshot != null
    }

    private fun reloadConfiguredDocument(): Boolean {
        val telemetrySnapshot = synchronized(this) {
            val controlPath = config.capabilityControlPath ?: return@synchronized null
            val rawDocument = try {
                readControlDocument(controlPath)
            } catch (_: Exception) {
                auditRejected(revision = "unavailable", reason = "unreadable", fingerprint = "unreadable")
                return@synchronized null
            }
            val documentFingerprint = fingerprint(rawDocument)
            if (documentFingerprint == lastStableInputDigest) return@synchronized null

            val document = try {
                parseDocument(rawDocument)
            } catch (_: Exception) {
                auditRejected(revision = "unavailable", reason = "invalid_document", fingerprint = documentFingerprint)
                return@synchronized null
            }
            val candidate = try {
                SnapshotCandidate(document, effectiveCities(document), documentFingerprint)
            } catch (_: Exception) {
                auditRejected(revision = document.revision, reason = "invalid_document", fingerprint = documentFingerprint)
                return@synchronized null
            }

            val current = snapshot.get()
            if (current.revision == document.revision && currentDigest == documentFingerprint) {
                lastStableInputDigest = documentFingerprint
                return@synchronized null
            }
            val previouslyAcceptedDigest = history?.digestFor(document.revision)
            if (previouslyAcceptedDigest != null && previouslyAcceptedDigest != documentFingerprint) {
                auditRejected(revision = document.revision, reason = "duplicate_revision", fingerprint = documentFingerprint)
                return@synchronized null
            }
            if (current.revision == document.revision && currentDigest != null) {
                auditRejected(revision = document.revision, reason = "duplicate_revision", fingerprint = documentFingerprint)
                return@synchronized null
            }
            val historyCleanupDeferred = try {
                history?.persist(document, rawDocument, documentFingerprint) == true
            } catch (_: Exception) {
                auditRejected(revision = document.revision, reason = "history_persist_failed", fingerprint = documentFingerprint)
                return@synchronized null
            }

            val transition = if (previouslyAcceptedDigest == documentFingerprint) {
                SnapshotTransition.ROLLBACK
            } else {
                SnapshotTransition.ACCEPTED
            }
            val published = try {
                replaceSnapshot(candidate, transition)
            } catch (_: Exception) {
                baseCities.set(emptyMap())
                audit("capability_control event=schema_interlock_persist_failed classification=durability_failure")
                return@synchronized publishSnapshot("closed", emptyMap())
            }
            currentDigest = documentFingerprint
            lastStableInputDigest = documentFingerprint
            if (historyCleanupDeferred) {
                audit("capability_control event=history_cleanup_deferred revision=${document.revision}")
            }
            published
        }
        telemetrySnapshot?.let(::publishCapabilityTelemetry)
        return telemetrySnapshot != null
    }

    private fun initialSnapshot(): EffectiveCapabilitySnapshot {
        val isExplicitFixtureMode =
            config.mode == RuntimeMode.DEVELOPMENT && config.fixturesEnabled && config.capabilityControlPath == null
        val cities = if (isExplicitFixtureMode) {
            registry.registeredAdapters().values
                .filter { it.city.availability == developmentFixtureAvailability }
                .associate { adapter -> adapter.city.id to EffectiveCity(adapter, adapter.city) }
        } else {
            emptyMap()
        }
        return EffectiveCapabilitySnapshot(
            revision = if (isExplicitFixtureMode) "development-fixture" else "closed",
            generation = 0,
            cities = cities,
        )
    }

    private fun parseDocument(rawDocument: String): CapabilityControlDocument {
        DuplicateJsonKeyDetector(rawDocument).validate()
        return controlJson.decodeFromString<CapabilityControlDocument>(rawDocument).also { document ->
            if (!revisionPattern.matches(document.revision)) invalidControlDocument()
            if (document.cities.map(CityCapabilityControl::id).distinct().size != document.cities.size) {
                invalidControlDocument()
            }
        }
    }

    private fun effectiveCities(document: CapabilityControlDocument): Map<String, EffectiveCity> {
        val adapters = registry.registeredAdapters()
        validateSchemaInterlockAcknowledgements(document, adapters)
        val effective = linkedMapOf<String, EffectiveCity>()
        document.cities.forEach { control ->
            val adapter = adapters[control.id] ?: invalidControlDocument()
            val intrinsic = adapter.city
            if (control.availability != intrinsic.availability) invalidControlDocument()
            validateAllowedAvailability(control.id, control.availability)
            validateNoCapabilityExpansion(control.capabilities, intrinsic.capabilities)
            if (control.enabled) {
                val city = intrinsic.copy(capabilities = intrinsic.capabilities.intersect(control.capabilities))
                NormalizedResponseValidator.city(city)
                effective[city.id] = EffectiveCity(adapter, city)
            }
        }
        return effective.toSortedMap()
    }

    private fun validateAllowedAvailability(cityId: String, availability: CityAvailability) {
        when (availability.source) {
            CitySource.FIXTURE -> {
                if (
                    availability.readiness != CityReadiness.DEVELOPMENT_FIXTURE ||
                    config.mode != RuntimeMode.DEVELOPMENT ||
                    !config.fixturesEnabled
                ) {
                    invalidControlDocument()
                }
            }
            CitySource.REVIEWED_ADAPTER -> {
                if (availability.readiness != CityReadiness.PRODUCTION_READY) invalidControlDocument()
            }
            // Theta is explicitly development-only. Keeping the source/readiness unreviewed is
            // intentional: a local operator document can expose it for manual smoke checks but
            // cannot make it look production-ready or enable any other unreviewed adapter.
            CitySource.UNREVIEWED_ADAPTER -> if (
                config.mode != RuntimeMode.DEVELOPMENT || cityId != "batumi" || !config.batumiTheta.isActivated
            ) invalidControlDocument()
        }
    }

    private fun replaceSnapshot(
        candidate: SnapshotCandidate,
        transition: SnapshotTransition,
    ): CapabilityTelemetrySnapshot {
        if (transition == SnapshotTransition.ACCEPTED) {
            schemaInterlocks?.recoverAcknowledgedOnAcceptedRevision(
                revision = candidate.document.revision,
                acknowledgements = candidate.document.schemaInterlockAcknowledgements,
            )
        } else if (schemaInterlocks != null) {
            // Replaying a persisted document must retain every current durable latch, even if
            // that older document contains an acknowledgement intended for an earlier state.
            audit(
                "capability_control event=schema_interlock_recovery_skipped " +
                    "transition=${transition.auditValue} revision=${candidate.document.revision}",
            )
        }
        baseCities.set(candidate.cities)
        val telemetrySnapshot = publishSnapshot(candidate.document.revision, candidate.cities)
        audit(
            "capability_control event=accepted transition=${transition.auditValue} revision=${candidate.document.revision} " +
                "generation=${snapshot.get().generation} enabledCities=${snapshot.get().cities.size}",
        )
        return telemetrySnapshot
    }

    private fun publishSnapshot(
        revision: String,
        cities: Map<String, EffectiveCity>,
    ): CapabilityTelemetrySnapshot {
        val nextGeneration = generation.incrementAndGet()
        val overlay = schemaInterlocks?.overlay(cities) ?: SchemaInterlockOverlay(cities, emptySet())
        val effectiveSnapshot = EffectiveCapabilitySnapshot(
            revision = revision,
            generation = nextGeneration,
            cities = overlay.cities,
        )
        val telemetrySnapshot = capabilityTelemetrySnapshot(effectiveSnapshot, overlay.latches)
        snapshot.set(effectiveSnapshot)
        return telemetrySnapshot
    }

    private fun capabilityTelemetrySnapshot(
        effectiveSnapshot: EffectiveCapabilitySnapshot,
        latches: Set<SchemaInterlockKey>,
    ): CapabilityTelemetrySnapshot {
        val states = linkedMapOf<CapabilityTelemetryKey, CapabilityTelemetryState>()
        effectiveSnapshot.cities.values.forEach { effectiveCity ->
            TelemetryCapability.entries.forEach { capability ->
                val key = CapabilityTelemetryKey(
                    city = effectiveCity.city.id,
                    provider = effectiveCity.adapter.telemetryProvider,
                    capability = capability,
                )
                states[key] = CapabilityTelemetryState(
                    city = key.city,
                    provider = key.provider,
                    capability = capability,
                    enabled = effectiveCity.city.capabilities.capabilityEnabled(capability),
                    schemaInterlocked = SchemaInterlockKey(key.city, capability) in latches,
                )
            }
        }
        latches.forEach { key ->
            val adapter = registry.registeredAdapters()[key.cityId] ?: return@forEach
            val telemetryKey = CapabilityTelemetryKey(key.cityId, adapter.telemetryProvider, key.capability)
            val existing = states[telemetryKey]
            states[telemetryKey] = CapabilityTelemetryState(
                city = telemetryKey.city,
                provider = telemetryKey.provider,
                capability = telemetryKey.capability,
                enabled = existing?.enabled ?: false,
                schemaInterlocked = true,
            )
        }
        return CapabilityTelemetrySnapshot(
            generation = effectiveSnapshot.generation,
            states = states.values.toList(),
        )
    }

    private fun publishCapabilityTelemetry(telemetrySnapshot: CapabilityTelemetrySnapshot) {
        try {
            capabilityTelemetryObserver(telemetrySnapshot)
        } catch (_: Exception) {
            audit("capability_control event=telemetry_publish_failed classification=observer_failure")
        }
    }

    private fun auditRejected(revision: String, reason: String, fingerprint: String) {
        if (lastRejectedInputFingerprint == fingerprint) return
        lastRejectedInputFingerprint = fingerprint
        audit("capability_control event=rejected revision=$revision reason=$reason")
    }

    private data class SnapshotCandidate(
        val document: CapabilityControlDocument,
        val cities: Map<String, EffectiveCity>,
        val digest: String,
    )

    private data class SchemaDriftOutcome(
        val latched: Boolean,
        val telemetrySnapshot: CapabilityTelemetrySnapshot? = null,
    )

    private data class CapabilityTelemetryKey(
        val city: String,
        val provider: com.denis.georgiatransit.bff.observability.TelemetryProvider,
        val capability: TelemetryCapability,
    )

    private enum class SnapshotTransition(val auditValue: String) {
        RESTORED("restored"),
        ACCEPTED("accepted"),
        ROLLBACK("rollback"),
    }
}

@Serializable
private data class CapabilityControlDocument(
    val revision: String,
    val cities: List<CityCapabilityControl>,
    val schemaInterlockAcknowledgements: List<SchemaInterlockAcknowledgement> = emptyList(),
)

@Serializable
private data class CityCapabilityControl(
    val id: String,
    val enabled: Boolean,
    val availability: CityAvailability,
    val capabilities: CityCapabilities,
)

@Serializable
private data class SchemaInterlockAcknowledgement(
    val cityId: String,
    val capability: String,
)

private fun validateSchemaInterlockAcknowledgements(
    document: CapabilityControlDocument,
    adapters: Map<String, CityTransitProviderAdapter>,
) {
    val acknowledged = document.schemaInterlockAcknowledgements.map { acknowledgement ->
        val capability = acknowledgement.capability.toTelemetryCapabilityOrNull() ?: invalidControlDocument()
        if (acknowledgement.cityId !in adapters) invalidControlDocument()
        acknowledgement.cityId to capability
    }
    if (acknowledged.distinct().size != acknowledged.size) invalidControlDocument()
}

private fun String.toTelemetryCapabilityOrNull(): TelemetryCapability? =
    TelemetryCapability.entries.firstOrNull { it.wireValue == this }

/**
 * Durable, bounded overlay owned by the capability-control authority. Only synthetic probe
 * schema classifications are allowed to add a latch. Operators clear one only in a newer
 * capability-control revision carrying an explicit matching acknowledgement.
 */
private class SchemaDriftInterlocks(
    private val stateDirectory: Path?,
    private val failureThreshold: Int,
    private val windowSeconds: Long,
) {
    private val lock = Any()
    private val observations = mutableMapOf<SchemaInterlockKey, ArrayDeque<Instant>>()
    private var latches: Map<SchemaInterlockKey, SchemaInterlockLatch> = emptyMap()
    private val statePath: Path? = stateDirectory?.resolve("schema-interlocks.json")

    fun load() = synchronized(lock) {
        val path = statePath ?: return@synchronized
        SecureCapabilityFiles.requirePrivateDirectory(requireNotNull(path.parent))
        if (!Files.exists(path, NOFOLLOW_LINKS)) return@synchronized
        val rawState = SecureCapabilityFiles.readPrivateRegularFile(path, MaximumCapabilityDocumentBytes)
        // Serialized state is operator-impacting durable control data too: reject duplicate
        // member names before kotlinx.serialization can collapse one and silently lose a latch.
        DuplicateJsonKeyDetector(rawState).validate()
        val stored = controlJson.decodeFromString<SchemaInterlockState>(rawState)
        if (stored.latches.size > MaximumSchemaInterlockLatches) invalidControlDocument()
        val loaded = stored.latches.associate { record ->
            val key = SchemaInterlockKey(record.cityId, record.capability.toTelemetryCapabilityOrNull() ?: invalidControlDocument())
            if (!revisionPattern.matches(record.latchedRevision)) invalidControlDocument()
            key to SchemaInterlockLatch(record.latchedRevision)
        }
        if (loaded.size != stored.latches.size) invalidControlDocument()
        latches = loaded.toSortedMap()
    }

    fun observe(cityId: String, capability: TelemetryCapability, revision: String): Boolean = synchronized(lock) {
        val key = SchemaInterlockKey(cityId, capability)
        if (key in latches) return@synchronized false
        val now = Instant.now()
        val timestamps = observations.getOrPut(key, ::ArrayDeque)
        while (timestamps.firstOrNull()?.let { java.time.Duration.between(it, now).seconds >= windowSeconds } == true) {
            timestamps.removeFirst()
        }
        timestamps.addLast(now)
        if (timestamps.size < failureThreshold) return@synchronized false
        val updated = (latches + (key to SchemaInterlockLatch(revision))).toSortedMap()
        persist(updated)
        latches = updated
        observations.remove(key)
        true
    }

    /**
     * Recovery is callable only for a newly accepted control document. Startup restore and
     * historical rollback intentionally retain latches, regardless of acknowledgements they hold.
     */
    fun recoverAcknowledgedOnAcceptedRevision(
        revision: String,
        acknowledgements: List<SchemaInterlockAcknowledgement>,
    ) = synchronized(lock) {
        val acknowledgementKeys = acknowledgements.map { acknowledgement ->
            SchemaInterlockKey(
                acknowledgement.cityId,
                acknowledgement.capability.toTelemetryCapabilityOrNull() ?: invalidControlDocument(),
            )
        }.toSet()
        val updated = latches.filter { (key, latch) ->
            key !in acknowledgementKeys || latch.latchedRevision == revision
        }.toSortedMap()
        if (updated != latches) {
            persist(updated)
            latches = updated
        }
    }

    fun overlay(cities: Map<String, EffectiveCity>): SchemaInterlockOverlay = synchronized(lock) {
        val currentLatches = latches.keys.toSet()
        val overlaidCities = currentLatches.fold(cities) { current, key ->
            val city = current[key.cityId] ?: return@fold current
            current + (key.cityId to city.copy(city = city.city.copy(capabilities = city.city.capabilities.disabled(key.capability))))
        }.toSortedMap()
        SchemaInterlockOverlay(overlaidCities, currentLatches)
    }

    fun isLatched(cityId: String, capability: TelemetryCapability): Boolean = synchronized(lock) {
        SchemaInterlockKey(cityId, capability) in latches
    }

    private fun persist(updated: Map<SchemaInterlockKey, SchemaInterlockLatch>) {
        val path = statePath ?: return
        SecureCapabilityFiles.requirePrivateDirectory(requireNotNull(path.parent))
        SecureCapabilityFiles.atomicWritePrivateFile(
            target = path,
            content = controlJson.encodeToString(
                SchemaInterlockState(
                    latches = updated.entries.map { (key, latch) ->
                        SchemaInterlockRecord(key.cityId, key.capability.wireValue, latch.latchedRevision)
                    },
                ),
            ),
            temporaryPrefix = "schema-interlock-",
        )
    }

    private companion object {
        const val MaximumSchemaInterlockLatches = 64
    }
}

private data class SchemaInterlockOverlay(
    val cities: Map<String, EffectiveCity>,
    val latches: Set<SchemaInterlockKey>,
)

private data class SchemaInterlockKey(
    val cityId: String,
    val capability: TelemetryCapability,
) : Comparable<SchemaInterlockKey> {
    override fun compareTo(other: SchemaInterlockKey): Int =
        compareValuesBy(this, other, SchemaInterlockKey::cityId, { it.capability.wireValue })
}

private data class SchemaInterlockLatch(val latchedRevision: String)

@Serializable
private data class SchemaInterlockState(val latches: List<SchemaInterlockRecord>)

@Serializable
private data class SchemaInterlockRecord(
    val cityId: String,
    val capability: String,
    val latchedRevision: String,
)

private fun CityCapabilities.capabilityEnabled(capability: TelemetryCapability): Boolean = when (capability) {
    TelemetryCapability.ROUTES -> routes
    TelemetryCapability.STOPS -> stops
    TelemetryCapability.ROUTE_GEOMETRY -> routeGeometry
    TelemetryCapability.VEHICLE_POSITIONS -> vehiclePositions
    TelemetryCapability.ARRIVALS -> arrivals
    TelemetryCapability.TRIP_PLANNING -> tripPlanning
}

private fun CityCapabilities.disabled(capability: TelemetryCapability): CityCapabilities = when (capability) {
    TelemetryCapability.ROUTES -> copy(routes = false)
    TelemetryCapability.STOPS -> copy(stops = false)
    TelemetryCapability.ROUTE_GEOMETRY -> copy(routeGeometry = false)
    TelemetryCapability.VEHICLE_POSITIONS -> copy(vehiclePositions = false)
    TelemetryCapability.ARRIVALS -> copy(arrivals = false, officialArrivals = false)
    TelemetryCapability.TRIP_PLANNING -> copy(tripPlanning = false)
}

private val developmentFixtureAvailability = CityAvailability(
    readiness = CityReadiness.DEVELOPMENT_FIXTURE,
    source = CitySource.FIXTURE,
)

private fun validateNoCapabilityExpansion(requested: CityCapabilities, intrinsic: CityCapabilities) {
    if (
        (requested.routes && !intrinsic.routes) ||
        (requested.stops && !intrinsic.stops) ||
        (requested.routeGeometry && !intrinsic.routeGeometry) ||
        (requested.vehiclePositions && !intrinsic.vehiclePositions) ||
        (requested.officialArrivals && !intrinsic.officialArrivals) ||
        (requested.tripPlanning && !intrinsic.tripPlanning) ||
        (requested.arrivals && !intrinsic.arrivals)
    ) {
        invalidControlDocument()
    }
}

private fun CityCapabilities.intersect(other: CityCapabilities): CityCapabilities = CityCapabilities(
    routes = routes && other.routes,
    stops = stops && other.stops,
    routeGeometry = routeGeometry && other.routeGeometry,
    vehiclePositions = vehiclePositions && other.vehiclePositions,
    officialArrivals = officialArrivals && other.officialArrivals,
    tripPlanning = tripPlanning && other.tripPlanning,
    arrivals = arrivals && other.arrivals,
)

private fun readControlDocument(path: Path): String =
    SecureCapabilityFiles.readPrivateRegularFile(path, MaximumCapabilityDocumentBytes)

private fun fingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun invalidControlDocument(): Nothing = throw CapabilityControlException()

private class CapabilityControlException : IllegalArgumentException("Invalid capability control document")

/**
 * kotlinx.serialization deliberately represents an object as a map, so duplicate JSON member
 * names would otherwise be collapsed before validation. This small parser rejects them at every
 * nesting level before the strict serializer decodes the allowed schema.
 */
private class DuplicateJsonKeyDetector(private val source: String) {
    private var index = 0

    fun validate() {
        skipWhitespace()
        parseValue(depth = 0)
        skipWhitespace()
        if (index != source.length) invalidControlDocument()
    }

    private fun parseValue(depth: Int) {
        if (depth > MaximumJsonNestingDepth) invalidControlDocument()
        skipWhitespace()
        when (nextOrNull()) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()
            null -> invalidControlDocument()
            else -> parsePrimitive()
        }
    }

    private fun parseObject(depth: Int) {
        expect('{')
        skipWhitespace()
        if (consume('}')) return
        val keys = mutableSetOf<String>()
        while (true) {
            skipWhitespace()
            if (nextOrNull() != '"') invalidControlDocument()
            if (!keys.add(parseString())) invalidControlDocument()
            skipWhitespace()
            expect(':')
            parseValue(depth + 1)
            skipWhitespace()
            if (consume('}')) return
            expect(',')
        }
    }

    private fun parseArray(depth: Int) {
        expect('[')
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue(depth + 1)
            skipWhitespace()
            if (consume(']')) return
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (true) {
            val character = nextOrNull() ?: invalidControlDocument()
            index += 1
            when (character) {
                '"' -> return value.toString()
                '\\' -> value.append(parseEscape())
                in '\u0000'..'\u001F' -> invalidControlDocument()
                else -> value.append(character)
            }
        }
    }

    private fun parseEscape(): Char {
        val escape = nextOrNull() ?: invalidControlDocument()
        index += 1
        return when (escape) {
            '"', '\\', '/' -> escape
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> invalidControlDocument()
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > source.length) invalidControlDocument()
        val hexadecimal = source.substring(index, index + 4)
        index += 4
        return hexadecimal.toIntOrNull(16)?.toChar() ?: invalidControlDocument()
    }

    private fun parsePrimitive() {
        val start = index
        while (nextOrNull() != null && nextOrNull() !in setOf(',', '}', ']', ' ', '\n', '\r', '\t')) {
            index += 1
        }
        if (start == index) invalidControlDocument()
    }

    private fun skipWhitespace() {
        while (nextOrNull() in setOf(' ', '\n', '\r', '\t')) index += 1
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) invalidControlDocument()
    }

    private fun consume(expected: Char): Boolean =
        if (nextOrNull() == expected) {
            index += 1
            true
        } else {
            false
        }

    private fun nextOrNull(): Char? = source.getOrNull(index)
}

private class CapabilityControlHistory(
    private val stateDirectory: Path,
    private val maximumDocuments: Int,
) {
    private val indexPath = stateDirectory.resolve("history-index.json")
    private val documentsDirectory = stateDirectory.resolve("documents")

    @Volatile
    private var records: List<StoredCapabilityDocument> = emptyList()

    fun load(): List<StoredCapabilityDocument> {
        SecureCapabilityFiles.requirePrivateDirectory(stateDirectory)
        if (!Files.exists(indexPath, NOFOLLOW_LINKS)) return emptyList()
        val index = controlJson.decodeFromString<CapabilityHistoryIndex>(
            SecureCapabilityFiles.readPrivateRegularFile(indexPath, MaximumCapabilityDocumentBytes),
        )
        if (index.documents.size > maximumDocuments || index.documents.map(HistoryDocument::revision).distinct().size != index.documents.size) {
            invalidControlDocument()
        }
        val loaded = index.documents.map { entry ->
            if (!revisionPattern.matches(entry.revision) || !digestPattern.matches(entry.digest)) invalidControlDocument()
            val documentPath = documentsDirectory.resolve("${entry.revision}.json")
            SecureCapabilityFiles.requirePrivateDirectory(documentsDirectory)
            val document = readControlDocument(documentPath)
            if (fingerprint(document) != entry.digest) invalidControlDocument()
            StoredCapabilityDocument(entry.revision, entry.digest, document)
        }
        records = loaded
        return loaded
    }

    fun digestFor(revision: String): String? = records.firstOrNull { it.revision == revision }?.digest

    /**
     * Writes a new document before a replacement index and only then tries garbage collection.
     * A crash or write failure before the index swap leaves the previous index and its documents
     * intact; cleanup failure after the swap retains harmless extra private files for a later run.
     */
    fun persist(document: CapabilityControlDocument, rawDocument: String, digest: String): Boolean {
        require(revisionPattern.matches(document.revision))
        require(digestPattern.matches(digest))
        SecureCapabilityFiles.requirePrivateDirectory(stateDirectory)
        SecureCapabilityFiles.createPrivateDirectoryIfMissing(documentsDirectory)
        val documentPath = documentsDirectory.resolve("${document.revision}.json")
        atomicWrite(documentPath, rawDocument)
        val replacement = StoredCapabilityDocument(document.revision, digest, rawDocument)
        val updated = (records.filterNot { it.revision == document.revision } + replacement).takeLast(maximumDocuments)
        atomicWrite(
            indexPath,
            controlJson.encodeToString(
                CapabilityHistoryIndex(
                    documents = updated.map { record -> HistoryDocument(record.revision, record.digest) },
                ),
            ),
        )
        records = updated
        return !garbageCollect(updated.map(StoredCapabilityDocument::revision).toSet())
    }

    private fun garbageCollect(retainedRevisions: Set<String>): Boolean =
        try {
            SecureCapabilityFiles.requirePrivateDirectory(documentsDirectory)
            Files.newDirectoryStream(documentsDirectory).use { paths ->
                for (path in paths) {
                    val name = path.fileName.toString()
                    val revision = name.removeSuffix(".json")
                    when {
                        name.endsWith(".json") && revisionPattern.matches(revision) -> {
                            if (revision !in retainedRevisions && !SecureCapabilityFiles.deletePrivateRegularFileIfPresent(path)) {
                                return false
                            }
                        }
                        name.startsWith("capability-control-") && name.endsWith(".tmp") -> {
                            if (!SecureCapabilityFiles.deletePrivateRegularFileIfPresent(path)) return false
                        }
                        else -> return false
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }

    private fun atomicWrite(target: Path, content: String) {
        SecureCapabilityFiles.atomicWritePrivateFile(target, content, "capability-control-")
    }
}

private val PrivateFilePermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)
private val PrivateDirectoryPermissions = PrivateFilePermissions + PosixFilePermission.OWNER_EXECUTE
private val WritableByGroupOrOther = setOf(
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.OTHERS_WRITE,
)

/**
 * The control plane is intentionally supported only on a POSIX filesystem with ownership and
 * permission metadata. Filesystem security is a deployment boundary, so unavailable metadata or
 * a symlink is treated as an invalid control update rather than as a permissive fallback.
 */
private object SecureCapabilityFiles {
    private val processOwner = try {
        FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    } catch (_: Exception) {
        null
    }

    fun readPrivateRegularFile(path: Path, maximumBytes: Long): String {
        val before = privateRegularAttributes(path, writable = false)
        val byteCount = before.size()
        if (byteCount !in 1..maximumBytes || before.fileKey() == null) invalidControlDocument()
        val bytes = Files.newByteChannel(
            path,
            setOf<OpenOption>(StandardOpenOption.READ, NOFOLLOW_LINKS),
        ).use { channel ->
            Channels.newInputStream(channel).use { input -> input.readNBytes(byteCount.toInt() + 1) }
        }
        val after = privateRegularAttributes(path, writable = false)
        if (
            bytes.size != byteCount.toInt() ||
            before.fileKey() != after.fileKey() ||
            before.size() != after.size()
        ) {
            invalidControlDocument()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun requirePrivateDirectory(path: Path) {
        requireSafeParents(path)
        val attributes = posixAttributes(path)
        if (!attributes.isDirectory || Files.isSymbolicLink(path)) invalidControlDocument()
        requireProcessOwner(attributes)
        if (attributes.permissions() != PrivateDirectoryPermissions) invalidControlDocument()
    }

    fun createPrivateDirectoryIfMissing(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            requirePrivateDirectory(path)
            return
        }
        requireSafeParents(path)
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PrivateDirectoryPermissions))
        requirePrivateDirectory(path)
    }

    fun requireWritablePrivateRegularFileIfPresent(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) privateRegularAttributes(path, writable = true)
    }

    /**
     * A directory fsync makes the preceding same-directory atomic rename durable. POSIX metadata
     * is already mandatory for this control plane, so an unsupported or failed force is a hard
     * persistence failure and the candidate snapshot is not accepted.
     */
    fun forcePrivateDirectory(path: Path) {
        requirePrivateDirectory(path)
        FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }

    fun deletePrivateRegularFileIfPresent(path: Path): Boolean =
        try {
            if (!Files.exists(path, NOFOLLOW_LINKS)) {
                true
            } else {
                privateRegularAttributes(path, writable = true)
                Files.delete(path)
                true
            }
        } catch (_: Exception) {
            false
        }

    fun atomicWritePrivateFile(target: Path, content: String, temporaryPrefix: String) {
        require(temporaryPrefix.matches(Regex("[a-z-]{3,32}"))) { "Invalid private temporary prefix" }
        requirePrivateDirectory(target.parent)
        requireWritablePrivateRegularFileIfPresent(target)
        val temporary = Files.createTempFile(
            target.parent,
            temporaryPrefix,
            ".tmp",
            PosixFilePermissions.asFileAttribute(PrivateFilePermissions),
        )
        try {
            requireWritablePrivateRegularFileIfPresent(temporary)
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            requireWritablePrivateRegularFileIfPresent(target)
            forcePrivateDirectory(target.parent)
        } finally {
            deletePrivateRegularFileIfPresent(temporary)
        }
    }

    private fun privateRegularAttributes(path: Path, writable: Boolean): PosixFileAttributes {
        requireSafeParents(path)
        val attributes = posixAttributes(path)
        if (!attributes.isRegularFile || Files.isSymbolicLink(path)) invalidControlDocument()
        requireProcessOwner(attributes)
        val permissions = attributes.permissions()
        if (!permissions.all { it in PrivateFilePermissions } || PosixFilePermission.OWNER_READ !in permissions) {
            invalidControlDocument()
        }
        if (writable && PosixFilePermission.OWNER_WRITE !in permissions) invalidControlDocument()
        return attributes
    }

    private fun requireSafeParents(path: Path) {
        if (!path.isAbsolute) invalidControlDocument()
        val parent = path.parent ?: invalidControlDocument()
        var current = path.root ?: invalidControlDocument()
        requireSafeParentDirectory(current)
        path.forEach { element ->
            if (current == parent) return
            current = current.resolve(element)
            requireSafeParentDirectory(current)
        }
    }

    private fun requireSafeParentDirectory(path: Path) {
        val attributes = posixAttributes(path)
        if (!attributes.isDirectory || Files.isSymbolicLink(path)) invalidControlDocument()
        if (attributes.permissions().any { it in WritableByGroupOrOther }) invalidControlDocument()
    }

    private fun posixAttributes(path: Path): PosixFileAttributes =
        try {
            Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: Exception) {
            invalidControlDocument()
        }

    private fun requireProcessOwner(attributes: PosixFileAttributes) {
        val owner = processOwner ?: invalidControlDocument()
        if (attributes.owner() != owner) invalidControlDocument()
    }
}

@Serializable
private data class CapabilityHistoryIndex(
    val documents: List<HistoryDocument>,
)

@Serializable
private data class HistoryDocument(
    val revision: String,
    val digest: String,
)

private data class StoredCapabilityDocument(
    val revision: String,
    val digest: String,
    val document: String,
)
