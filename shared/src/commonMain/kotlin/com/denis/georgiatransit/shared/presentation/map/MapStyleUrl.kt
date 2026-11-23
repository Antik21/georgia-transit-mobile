package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitLocale

internal fun localizedMapStyleUrl(styleUrl: String?, locale: TransitLocale): String? {
    if (styleUrl == null) return null
    val language = when (locale) {
        TransitLocale.English, TransitLocale.Russian -> locale.languageTag
        else -> TransitLocale.Georgian.languageTag
    }
    return "$styleUrl?lang=$language"
}
