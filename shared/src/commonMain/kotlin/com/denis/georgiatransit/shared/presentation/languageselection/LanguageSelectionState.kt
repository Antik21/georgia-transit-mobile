package com.denis.georgiatransit.shared.presentation.languageselection

internal sealed interface AppLanguageSettings {
    interface Picker : AppLanguageSettings {
        val languages: List<LanguageOption>

        fun currentLanguageTag(): String?
        fun applyLanguage(languageTag: String?)
    }

    class SystemSettings(val open: () -> Unit) : AppLanguageSettings
}

internal data class LanguageOption(val languageTag: String, val displayName: String)

internal data class ViewState(
    val languages: List<LanguageOption>,
    val appliedLanguageTag: String?,
    val selectedLanguageTag: String? = appliedLanguageTag,
    val isApplying: Boolean = false,
) {
    val canApply: Boolean get() = !isApplying && selectedLanguageTag != appliedLanguageTag
}

internal sealed interface Action {
    data class Select(val languageTag: String?) : Action
    data object Apply : Action
    data object Close : Action
}

internal sealed interface SideEffect

internal sealed interface NavigationEffect : SideEffect {
    data class Applied(val languageTag: String?) : NavigationEffect
    data object Closed : NavigationEffect
}
