package com.denis.georgiatransit.bff

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml

class AlertRulesContractTest {
    @Test
    fun `alerts distinguish scrape availability from enabled probe freshness with exact five minute semantics`() {
        val rules = alertRules()
        val scrapeDown = rules.getValue("TransitBffScrapeDown")
        assertEquals("up{job=\"transit-bff\"} == 0", scrapeDown.string("expr").trim())
        assertEquals("5m", scrapeDown.string("for"))
        assertEquals("page", scrapeDown.map("labels").string("severity"))

        listOf("TransitBffProbeNoFreshSuccess", "TransitBffRealtimeFreshnessMissing").forEach { alert ->
            val rule = rules.getValue(alert)
            val expression = rule.string("expr")
            assertTrue(expression.contains("bff_probe_target_enabled == 1"), "$alert must not page disabled capabilities")
            assertTrue(expression.contains("bff_probe_target_enabled_since_timestamp_seconds > 300"), alert)
            assertTrue(expression.contains("unless on (city, provider, capability, operation)"), alert)
            assertFalse(rule.containsKey("for"), "$alert already measures the full >300 seconds window")
        }
        assertTrue(
            rules.getValue("TransitBffRealtimeFreshnessMissing").string("expr")
                .contains("bff_probe_realtime_expected == 1"),
        )
    }

    @Test
    fun `alert rules retain bounded safety signals and page schema latches`() {
        val rules = alertRules()
        assertEquals("bff_provider_schema_interlock_latched == 1", rules.getValue("TransitBffSchemaInterlockLatched").string("expr"))
        assertEquals("page", rules.getValue("TransitBffSchemaInterlockLatched").map("labels").string("severity"))
        assertEquals("2m", rules.getValue("TransitBffCircuitOpen").string("for"))
        assertTrue(rules.getValue("TransitBffRateBudgetRejected").string("expr").contains("rate_budget_rejected"))
        assertTrue(rules.getValue("TransitBffElevatedProvider5xxOrParse").string("expr").contains("normalized_schema"))
    }
}

private fun alertRules(): Map<String, Map<String, Any?>> {
    val candidates = listOf(Path.of("deploy/ops/transit-bff-alerts.yaml"), Path.of("../deploy/ops/transit-bff-alerts.yaml"))
    val path = assertNotNull(candidates.firstOrNull(Files::isRegularFile), "Alert rules were not found")
    @Suppress("UNCHECKED_CAST")
    val document = Yaml().load<Map<String, Any?>>(Files.readString(path))
    val groups = assertNotNull(document["groups"] as? List<*>, "Expected alert groups")
    val rules = groups.flatMap { group ->
        @Suppress("UNCHECKED_CAST")
        (assertNotNull(group as? Map<String, Any?>, "Expected alert group")["rules"] as? List<*>) ?: emptyList()
    }
    return rules.associate { rule ->
        @Suppress("UNCHECKED_CAST")
        val values = assertNotNull(rule as? Map<String, Any?>, "Expected alert rule")
        assertNotNull(values["alert"] as? String, "Expected alert name") to values
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.map(name: String): Map<String, Any?> =
    assertNotNull(this[name] as? Map<String, Any?>, "Expected mapping at $name")

private fun Map<String, Any?>.string(name: String): String =
    assertNotNull(this[name] as? String, "Expected string at $name")
