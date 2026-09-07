package com.denis.georgiatransit.shared.data.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Kotlin/Native has no separate I/O pool; Default is still off the main/UI dispatcher. */
internal actual val transitCacheDispatcher: CoroutineDispatcher = Dispatchers.Default
