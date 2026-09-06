package com.denis.georgiatransit.shared.presentation.ui.automation

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

actual fun Modifier.enableAutomationResourceIds(): Modifier = semantics {
    testTagsAsResourceId = true
}

