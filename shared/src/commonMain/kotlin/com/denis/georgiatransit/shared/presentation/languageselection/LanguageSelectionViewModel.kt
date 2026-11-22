package com.denis.georgiatransit.shared.presentation.languageselection

import androidx.lifecycle.ViewModel
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.viewmodel.container

internal class LanguageSelectionViewModel(picker: AppLanguageSettings.Picker) :
    ViewModel(), ContainerHost<ViewState, SideEffect> {

    override val container = container<ViewState, SideEffect>(
        ViewState(languages = picker.languages, appliedLanguageTag = picker.currentLanguageTag()),
    )

    fun dispatchAction(action: Action) = when (action) {
        is Action.Select -> intent {
            val tag = action.languageTag
            if (!state.isApplying && (tag == null || state.languages.any { it.languageTag == tag })) {
                reduce { state.copy(selectedLanguageTag = tag) }
            }
        }
        Action.Apply -> intent {
            if (state.canApply) {
                val selectedLanguageTag = state.selectedLanguageTag
                reduce { state.copy(isApplying = true) }
                postSideEffect(NavigationEffect.Applied(selectedLanguageTag))
            }
        }
        Action.Close -> intent {
            if (!state.isApplying) postSideEffect(NavigationEffect.Closed)
        }
    }
}
