package com.denis.georgiatransit.shared.presentation.cityselection

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId

/** Fixed test contract for known BFF fixture and city IDs; unknown remote cities stay untagged. */
internal fun CityId.citySelectionAutomationId(): String? = when (value) {
    "demo" -> AutomationId.CityDemo
    "tbilisi" -> AutomationId.CityTbilisi
    "batumi" -> AutomationId.CityBatumi
    "kutaisi" -> AutomationId.CityKutaisi
    else -> null
}

/** Selects the BFF-provided localized city name, falling back to the mapped display name. */
internal fun LocalizedText.citySelectionDisplayName(languageTag: String, fallbackName: String): String = when (languageTag) {
    "ka" -> ka
    "ru" -> ru
    else -> en
}.ifBlank { fallbackName }
