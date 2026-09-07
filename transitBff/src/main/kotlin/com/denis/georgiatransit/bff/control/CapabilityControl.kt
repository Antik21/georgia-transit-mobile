package com.denis.georgiatransit.bff.control

import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityNotFound
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.config.RuntimeMode
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
) : CapabilitySnapshotSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val snapshot = AtomicReference(initialSnapshot())
    private val history = config.capabilityControlStateDirectory?.let {
        CapabilityControlHistory(it, config.capabilityControlHistoryLimit)
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
        restoreLastKnownGood()
        reloadConfiguredDocument()
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

    @Synchronized
    private fun restoreLastKnownGood() {
        val historyStore = history ?: return
        val records = try {
            historyStore.load()
        } catch (_: Exception) {
            auditRejected(revision = "unavailable", reason = "history_unavailable", fingerprint = "history")
            return
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
            currentDigest = candidate.digest
            replaceSnapshot(candidate, transition = "restored")
        }
    }

    @Synchronized
    private fun reloadConfiguredDocument() {
        val controlPath = config.capabilityControlPath ?: return
        val rawDocument = try {
            readControlDocument(controlPath)
        } catch (_: Exception) {
            auditRejected(revision = "unavailable", reason = "unreadable", fingerprint = "unreadable")
            return
        }
        val documentFingerprint = fingerprint(rawDocument)
        if (documentFingerprint == lastStableInputDigest) return

        val document = try {
            parseDocument(rawDocument)
        } catch (_: Exception) {
            auditRejected(revision = "unavailable", reason = "invalid_document", fingerprint = documentFingerprint)
            return
        }
        val candidate = try {
            SnapshotCandidate(document, effectiveCities(document), documentFingerprint)
        } catch (_: Exception) {
            auditRejected(revision = document.revision, reason = "invalid_document", fingerprint = documentFingerprint)
            return
        }

        val current = snapshot.get()
        if (current.revision == document.revision && currentDigest == documentFingerprint) {
            lastStableInputDigest = documentFingerprint
            return
        }
        val previouslyAcceptedDigest = history?.digestFor(document.revision)
        if (previouslyAcceptedDigest != null && previouslyAcceptedDigest != documentFingerprint) {
            auditRejected(revision = document.revision, reason = "duplicate_revision", fingerprint = documentFingerprint)
            return
        }
        if (current.revision == document.revision && currentDigest != null) {
            auditRejected(revision = document.revision, reason = "duplicate_revision", fingerprint = documentFingerprint)
            return
        }
        val historyCleanupDeferred = try {
            history?.persist(document, rawDocument, documentFingerprint) == true
        } catch (_: Exception) {
            auditRejected(revision = document.revision, reason = "history_persist_failed", fingerprint = documentFingerprint)
            return
        }

        currentDigest = documentFingerprint
        lastStableInputDigest = documentFingerprint
        replaceSnapshot(
            candidate,
            transition = if (previouslyAcceptedDigest == documentFingerprint) "rollback" else "accepted",
        )
        if (historyCleanupDeferred) {
            audit("capability_control event=history_cleanup_deferred revision=${document.revision}")
        }
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
        val effective = linkedMapOf<String, EffectiveCity>()
        document.cities.forEach { control ->
            val adapter = adapters[control.id] ?: invalidControlDocument()
            val intrinsic = adapter.city
            if (control.availability != intrinsic.availability) invalidControlDocument()
            validateAllowedAvailability(control.availability)
            validateNoCapabilityExpansion(control.capabilities, intrinsic.capabilities)
            if (control.enabled) {
                val city = intrinsic.copy(capabilities = intrinsic.capabilities.intersect(control.capabilities))
                NormalizedResponseValidator.city(city)
                effective[city.id] = EffectiveCity(adapter, city)
            }
        }
        return effective.toSortedMap()
    }

    private fun validateAllowedAvailability(availability: CityAvailability) {
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
            CitySource.UNREVIEWED_ADAPTER -> invalidControlDocument()
        }
    }

    private fun replaceSnapshot(candidate: SnapshotCandidate, transition: String) {
        val nextGeneration = generation.incrementAndGet()
        snapshot.set(
            EffectiveCapabilitySnapshot(
                revision = candidate.document.revision,
                generation = nextGeneration,
                cities = candidate.cities,
            ),
        )
        audit(
            "capability_control event=accepted transition=$transition revision=${candidate.document.revision} " +
                "generation=$nextGeneration enabledCities=${candidate.cities.size}",
        )
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
}

@Serializable
private data class CapabilityControlDocument(
    val revision: String,
    val cities: List<CityCapabilityControl>,
)

@Serializable
private data class CityCapabilityControl(
    val id: String,
    val enabled: Boolean,
    val availability: CityAvailability,
    val capabilities: CityCapabilities,
)

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
        (requested.tripPlanning && !intrinsic.tripPlanning)
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
        SecureCapabilityFiles.requirePrivateDirectory(target.parent)
        SecureCapabilityFiles.requireWritablePrivateRegularFileIfPresent(target)
        val temporary = Files.createTempFile(
            target.parent,
            "capability-control-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PrivateFilePermissions),
        )
        try {
            SecureCapabilityFiles.requireWritablePrivateRegularFileIfPresent(temporary)
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
            SecureCapabilityFiles.requireWritablePrivateRegularFileIfPresent(target)
            SecureCapabilityFiles.forcePrivateDirectory(target.parent)
        } finally {
            SecureCapabilityFiles.deletePrivateRegularFileIfPresent(temporary)
        }
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
