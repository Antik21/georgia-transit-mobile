package com.denis.georgiatransit.shared.presentation.cityselection

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CitySelectionResourceContractTest {
    @Test
    fun englishGeorgianAndRussianStringKeysStayInParity() {
        val resourceRoot = projectRoot().resolve("shared/src/commonMain/composeResources")
        val english = stringKeys(resourceRoot.resolve("values/strings.xml"))
        val georgian = stringKeys(resourceRoot.resolve("values-ka/strings.xml"))
        val russian = stringKeys(resourceRoot.resolve("values-ru/strings.xml"))

        assertEquals(english, georgian, "Georgian string keys must match the default resource set")
        assertEquals(english, russian, "Russian string keys must match the default resource set")
        assertTrue(CityCatalogKeys.all { it in english })
    }

    @Test
    fun cityAutomationIdsAreFixedAndMatchTheMaestroFixtureSelection() {
        val cityIds = listOf(
            AutomationId.CityScreen,
            AutomationId.CityLoading,
            AutomationId.CityEmpty,
            AutomationId.CityError,
            AutomationId.CityRetry,
            AutomationId.CityStaleOffline,
            AutomationId.CityDemo,
            AutomationId.CityTbilisi,
            AutomationId.CityTbilisiAttribution,
            AutomationId.CityTbilisiAttributionTransitous,
            AutomationId.CityTbilisiAttributionOpenStreetMap,
            AutomationId.CityBatumi,
            AutomationId.CityKutaisi,
            AutomationId.CityContinue,
            AutomationId.CityLocation,
        )

        assertEquals(cityIds.size, cityIds.toSet().size)
        assertTrue(cityIds.all { it.startsWith("city-selection.") })
        assertEquals("city-selection.demo", AutomationId.CityDemo)
        assertEquals("city-selection.attribution.tbilisi", AutomationId.CityTbilisiAttribution)
        assertEquals(
            "city-selection.attribution.tbilisi.link.transitous",
            AutomationId.CityTbilisiAttributionTransitous,
        )
        assertEquals(
            "city-selection.attribution.tbilisi.link.openstreetmap",
            AutomationId.CityTbilisiAttributionOpenStreetMap,
        )

        val projectRoot = projectRoot()
        MaestroFlows.forEach { flow ->
            assertTrue(
                projectRoot.resolve(flow).readText().contains("id: ${AutomationId.CityDemo}"),
                "$flow must select the development fixture through its fixed automation ID",
            )
        }
    }

    @Test
    fun cityAutomationMapperUsesExplicitKnownTagsAndNeverBuildsUnknownTags() {
        assertEquals(AutomationId.CityDemo, CityId("demo").citySelectionAutomationId())
        assertEquals(AutomationId.CityTbilisi, CityId("tbilisi").citySelectionAutomationId())
        assertEquals(AutomationId.CityBatumi, CityId("batumi").citySelectionAutomationId())
        assertEquals(AutomationId.CityKutaisi, CityId("kutaisi").citySelectionAutomationId())
        assertNull(CityId("operator-added-city").citySelectionAutomationId())

        assertEquals(AutomationId.CityTbilisiAttribution, CityId("tbilisi").cityAttributionAutomationId())
        assertNull(CityId("demo").cityAttributionAutomationId())
        assertEquals(
            AutomationId.CityTbilisiAttributionTransitous,
            CityId("tbilisi").cityAttributionLinkAutomationId("transitous"),
        )
        assertEquals(
            AutomationId.CityTbilisiAttributionOpenStreetMap,
            CityId("tbilisi").cityAttributionLinkAutomationId("openstreetmap"),
        )
        assertNull(CityId("tbilisi").cityAttributionLinkAutomationId("operator-added-credit"))
        assertNull(CityId("operator-added-city").cityAttributionLinkAutomationId("transitous"))
    }

    @Test
    fun cityNameMapperUsesBffLocalizedValuesAndFallsBackWhenTheChosenValueIsBlank() {
        val localizedName = LocalizedText(ru = "Русский", en = "English", ka = "ქართული")

        assertEquals("English", localizedName.citySelectionDisplayName("en", fallbackName = "Fallback"))
        assertEquals("ქართული", localizedName.citySelectionDisplayName("ka", fallbackName = "Fallback"))
        assertEquals("Русский", localizedName.citySelectionDisplayName("ru", fallbackName = "Fallback"))
        assertEquals(
            "Fallback",
            localizedName.copy(en = "").citySelectionDisplayName("en", fallbackName = "Fallback"),
        )
    }

    @Test
    fun disabledTbilisiAttributionSmokeUsesStableIdsWithoutNavigatingThroughTheCity() {
        val flow = projectRoot().resolve(TbilisiAttributionSmokeFlow).readText()

        listOf(
            AutomationId.CityScreen,
            AutomationId.CityTbilisi,
            AutomationId.CityTbilisiAttribution,
            AutomationId.CityTbilisiAttributionTransitous,
            AutomationId.CityTbilisiAttributionOpenStreetMap,
        ).forEach { id ->
            assertTrue(flow.contains("id: $id"), "$TbilisiAttributionSmokeFlow must assert $id")
        }
        assertTrue(flow.contains("tapOn:\n    id: ${AutomationId.CityTbilisi}"))
        assertTrue(
            flow.contains("- assertNotVisible:\n    id: ${AutomationId.CityContinue}\n    enabled: true"),
            "$TbilisiAttributionSmokeFlow must prove that Continue never becomes enabled",
        )
        assertTrue(
            flow.contains("- extendedWaitUntil:\n    notVisible:\n        id: ${AutomationId.CityScreen}"),
            "$TbilisiAttributionSmokeFlow must wait for external-link activation",
        )
        assertTrue(flow.contains("tapOn:\n    id: ${AutomationId.CityTbilisiAttributionTransitous}"))
        assertTrue(flow.contains("tapOn:\n    id: ${AutomationId.CityTbilisiAttributionOpenStreetMap}"))
        assertEquals(2, flow.split("- launchApp:").size - 1)
        assertFalse(flow.contains("id: map.screen"))
        assertFalse(flow.contains("text:"))
        assertFalse(flow.contains("point:"))
    }

    @Test
    fun cityAttributionResourcesAndComposableKeepLinksOutsideTheDisabledSelectableRow() {
        val projectRoot = projectRoot()
        val resources = projectRoot.resolve("shared/src/commonMain/composeResources")
        listOf(
            resources.resolve("values/strings.xml"),
            resources.resolve("values-ka/strings.xml"),
            resources.resolve("values-ru/strings.xml"),
        ).forEach { resource ->
            assertTrue(resource.readText().contains("name=\"city_attribution_title\""), "$resource is missing attribution title")
        }

        // Compose UI test APIs are not on this host source set. Parse the Row call's balanced
        // delimiters rather than stopping at the first ')': onClick may be a lambda expression.
        // This keeps the enabled binding tied to .selectable and proves attribution is outside
        // that Row's content lambda.
        val screen = projectRoot.resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/cityselection/CitySelectionScreen.kt",
        ).readText()
        assertTrue(screen.contains("withLink(LinkAnnotation.Url(item.url))"))
        val cityRowStart = screen.indexOf("private fun CityRow").also { check(it >= 0) }
        val cityRowEnd = screen.indexOf("/** Credits are not nested", cityRowStart).also { check(it >= 0) }
        val cityRow = screen.substring(cityRowStart, cityRowEnd)
        val rowOpenParenthesis = Regex("""(?m)^\s*Row\(""").find(cityRow)?.range?.last
            ?: error("CityRow must contain a Row invocation")
        val rowCloseParenthesis = cityRow.matchingDelimiter(rowOpenParenthesis, '(', ')')
        val selectableOpenParenthesis = cityRow.indexOf(".selectable(").also { check(it >= 0) } + ".selectable".length
        val selectableCloseParenthesis = cityRow.matchingDelimiter(selectableOpenParenthesis, '(', ')')
        val selectable = cityRow.substring(selectableOpenParenthesis, selectableCloseParenthesis + 1)
        assertTrue(selectableOpenParenthesis < rowCloseParenthesis)
        assertTrue(selectable.contains("selected = selected"))
        assertTrue(selectable.contains("enabled = city.isEnabled"))
        assertTrue(selectable.contains("role = Role.RadioButton"))

        val rowContentStart = cityRow.indexOf('{', rowCloseParenthesis).also { check(it >= 0) }
        val rowContentEnd = cityRow.matchingDelimiter(rowContentStart, '{', '}')
        val attributionCall = cityRow.indexOf("CityAttribution(city)").also { check(it >= 0) }
        assertTrue(attributionCall > rowContentEnd)
    }

    @Test
    fun disabledTbilisiAttributionHarnessesResetOnlyExplicitSimulatorTargets() {
        val projectRoot = projectRoot()
        val androidHarness = projectRoot.resolve(AndroidTbilisiAttributionHarness).readText()
        val iosHarness = projectRoot.resolve(IosTbilisiAttributionHarness).readText()

        assertTrue(androidHarness.contains("android_serial != emulator-*"))
        assertTrue(androidHarness.contains("adb -s \"\$android_serial\" install -r \"\$apk_path\""))
        assertTrue(androidHarness.contains("adb -s \"\$android_serial\" shell pm clear \"\$app_id\""))
        assertTrue(androidHarness.contains("flows/tbilisi-attribution-disabled-smoke.yaml"))
        assertTrue(androidHarness.contains("maestro --device \"\$android_serial\" test \"\$flow_path\""))

        assertTrue(iosHarness.contains("simctl list devices booted"))
        assertTrue(iosHarness.contains("simctl uninstall \"\$simulator_udid\" \"\$app_id\""))
        assertTrue(iosHarness.contains("simctl install \"\$simulator_udid\" \"\$app_path\""))
        assertTrue(iosHarness.contains("flows/tbilisi-attribution-disabled-smoke.yaml"))
        assertTrue(iosHarness.contains("maestro --device \"\$simulator_udid\" test \"\$flow_path\""))

        val readme = projectRoot.resolve("ui-tests/maestro/README.md").readText()
        assertTrue(readme.contains(AndroidTbilisiAttributionHarness))
        assertTrue(readme.contains(IosTbilisiAttributionHarness))
        assertTrue(readme.contains("Tbilisi with `stops=false`"))
    }

    private fun stringKeys(path: Path): Set<String> =
        StringKey.findAll(path.readText()).map { it.groupValues[1] }.toSet()

    private fun String.matchingDelimiter(openIndex: Int, open: Char, close: Char): Int {
        check(this[openIndex] == open)
        var depth = 0
        for (index in openIndex until length) {
            when (this[index]) {
                open -> depth += 1
                close -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        error("No closing '$close' for '$open' at $openIndex")
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("shared/src/commonMain/composeResources")) }
            ?: error("Could not locate the project root from ${Path.of("").toAbsolutePath()}")

    private companion object {
        val StringKey = Regex("""<string\s+name="([^"]+)">""")
        val CityCatalogKeys = setOf(
            "city_catalog_loading",
            "city_catalog_empty",
            "city_catalog_error",
            "city_catalog_retry",
            "city_catalog_offline",
            "city_attribution_title",
        )
        val MaestroFlows = listOf(
            "ui-tests/maestro/flows/shell-smoke.yaml",
            "ui-tests/maestro/flows/location-granted-smoke.yaml",
            "ui-tests/maestro/flows/location-settings-fallback-smoke.yaml",
            "ui-tests/maestro/flows/startup-persistence-smoke.yaml",
        )
        const val TbilisiAttributionSmokeFlow = "ui-tests/maestro/flows/tbilisi-attribution-disabled-smoke.yaml"
        const val AndroidTbilisiAttributionHarness = "ui-tests/maestro/run-android-tbilisi-attribution-disabled.sh"
        const val IosTbilisiAttributionHarness = "ui-tests/maestro/run-ios-tbilisi-attribution-disabled.sh"
    }
}
