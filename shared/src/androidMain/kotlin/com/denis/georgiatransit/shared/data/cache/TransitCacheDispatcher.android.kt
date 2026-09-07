package com.denis.georgiatransit.shared.data.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val transitCacheDispatcher: CoroutineDispatcher = Dispatchers.IO
