package com.denis.georgiatransit.shared.app

import android.app.LocaleConfig
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.denis.georgiatransit.shared.presentation.languageselection.AppLanguageSettings
import com.denis.georgiatransit.shared.presentation.languageselection.LanguageOption
import java.util.Locale

@Composable
internal actual fun rememberAppLanguageSettings(): AppLanguageSettings? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@remember null
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return@remember null
        val supportedLocales = LocaleConfig(context).supportedLocales ?: return@remember null
        if (supportedLocales.isEmpty) return@remember null
        AndroidLanguagePicker(localeManager, List(supportedLocales.size()) { supportedLocales[it] })
    }
}

private class AndroidLanguagePicker(
    private val localeManager: LocaleManager,
    private val supportedLocales: List<Locale>,
) : AppLanguageSettings.Picker {
    override val languages = supportedLocales.map { locale ->
        LanguageOption(locale.toLanguageTag(), locale.getDisplayName(locale))
    }

    override fun currentLanguageTag(): String? {
        val current = localeManager.applicationLocales[0] ?: return null
        val supported = supportedLocales.firstOrNull { LocaleList.matchesLanguageAndScript(it, current) }
        return (supported ?: current).toLanguageTag()
    }

    override fun applyLanguage(languageTag: String?) {
        localeManager.applicationLocales = if (languageTag == null) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(languageTag)
        }
    }
}
