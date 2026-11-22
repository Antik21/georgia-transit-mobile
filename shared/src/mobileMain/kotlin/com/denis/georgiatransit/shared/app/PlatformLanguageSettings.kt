package com.denis.georgiatransit.shared.app

import androidx.compose.runtime.Composable
import com.denis.georgiatransit.shared.presentation.languageselection.AppLanguageSettings

@Composable
internal expect fun rememberAppLanguageSettings(): AppLanguageSettings?
