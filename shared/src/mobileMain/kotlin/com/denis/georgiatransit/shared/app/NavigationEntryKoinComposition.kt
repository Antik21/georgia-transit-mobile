package com.denis.georgiatransit.shared.app

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal inline fun <reified ViewModelType : ViewModel> navigationEntryViewModel(key: String): ViewModelType =
    koinViewModel(key = key)

