package com.denis.georgiatransit.bff.provider.zugdidi

import com.denis.georgiatransit.bff.api.LocalizedText
import kotlinx.serialization.json.Json

internal object ZugdidiNames {
    private val labels = requireNotNull(javaClass.getResourceAsStream("/transit/zugdidi/names.json"))
        .bufferedReader().use { Json.decodeFromString<Map<String, LocalizedText>>(it.readText()) }
        .onEach { (key, label) -> requireComplete(key, label) }

    val city get() = label("@city")
    val attribution get() = label("@attribution")
    val loop get() = label("@route-2")

    fun label(source: String): LocalizedText =
        requireNotNull(labels[source]) { "Zugdidi translation missing for '$source'" }

    fun direction(origin: LocalizedText, destination: LocalizedText): LocalizedText = LocalizedText(
        ru = "${origin.ru} → ${destination.ru}",
        en = "${origin.en} → ${destination.en}",
        ka = "${origin.ka} → ${destination.ka}",
        uk = "${origin.uk} → ${destination.uk}",
        de = "${origin.de} → ${destination.de}",
        fr = "${origin.fr} → ${destination.fr}",
        ar = "${origin.ar} ← ${destination.ar}",
        hi = "${origin.hi} → ${destination.hi}",
        zh = "${origin.zh} → ${destination.zh}",
    )

    private fun requireComplete(key: String, label: LocalizedText) {
        val values = listOf(label.ru, label.en, label.ka, label.uk, label.de, label.fr, label.ar, label.hi, label.zh)
        require(values.none { it.isNullOrBlank() }) { "Incomplete Zugdidi translation for '$key'" }
    }
}
