package com.denis.georgiatransit.shared.data.cache

import kotlinx.coroutines.CoroutineDispatcher

/** Native cache work must not run on the UI dispatcher. */
internal expect val transitCacheDispatcher: CoroutineDispatcher
