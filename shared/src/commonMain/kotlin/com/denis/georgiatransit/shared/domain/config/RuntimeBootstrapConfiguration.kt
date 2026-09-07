package com.denis.georgiatransit.shared.domain.config

import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot

/**
 * Non-secret launch settings supplied by the platform host.
 *
 * This contract deliberately contains no network endpoint, provider identifier, or credential.
 * It only bounds bootstrap work and describes which durable selected-city records this app build
 * can read.
 */
data class RuntimeBootstrapConfiguration(
    val bootstrapTimeoutMillis: Long,
    val compatibleCachedCitySchemaVersions: Set<Int>,
) {
    fun validationFailure(): ValidationFailure? = when {
        bootstrapTimeoutMillis !in MinBootstrapTimeoutMillis..MaxBootstrapTimeoutMillis ->
            ValidationFailure.InvalidTimeout

        compatibleCachedCitySchemaVersions.isEmpty() ||
            compatibleCachedCitySchemaVersions.any { it <= 0 } ||
            CachedCitySnapshot.SchemaVersion !in compatibleCachedCitySchemaVersions ->
            ValidationFailure.InvalidCacheSchemaCompatibility

        else -> null
    }

    enum class ValidationFailure {
        InvalidTimeout,
        InvalidCacheSchemaCompatibility,
    }

    companion object {
        const val MinBootstrapTimeoutMillis = 1_000L
        const val MaxBootstrapTimeoutMillis = 10_000L

        val default = RuntimeBootstrapConfiguration(
            bootstrapTimeoutMillis = 5_000L,
            compatibleCachedCitySchemaVersions = setOf(CachedCitySnapshot.SchemaVersion),
        )
    }
}

/** A narrow adapter seam for non-secret platform runtime settings. */
interface RuntimeBootstrapConfigurationSource {
    /** Returns null when the host cannot supply launch settings. */
    suspend fun load(): RuntimeBootstrapConfiguration?
}
