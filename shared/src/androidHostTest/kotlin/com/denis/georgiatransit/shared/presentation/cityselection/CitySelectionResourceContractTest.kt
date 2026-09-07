package com.denis.georgiatransit.shared.presentation.cityselection

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
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
            AutomationId.CityBatumi,
            AutomationId.CityKutaisi,
            AutomationId.CityContinue,
            AutomationId.CityLocation,
        )

        assertEquals(cityIds.size, cityIds.toSet().size)
        assertTrue(cityIds.all { it.startsWith("city-selection.") })
        assertEquals("city-selection.demo", AutomationId.CityDemo)

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

    private fun stringKeys(path: Path): Set<String> =
        StringKey.findAll(path.readText()).map { it.groupValues[1] }.toSet()

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
        )
        val MaestroFlows = listOf(
            "ui-tests/maestro/flows/shell-smoke.yaml",
            "ui-tests/maestro/flows/location-granted-smoke.yaml",
            "ui-tests/maestro/flows/location-settings-fallback-smoke.yaml",
            "ui-tests/maestro/flows/startup-persistence-smoke.yaml",
        )
    }
}
