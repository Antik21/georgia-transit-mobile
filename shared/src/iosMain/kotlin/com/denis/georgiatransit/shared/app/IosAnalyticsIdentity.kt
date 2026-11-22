package com.denis.georgiatransit.shared.app

import com.denis.georgiatransit.shared.domain.analytics.loadAnalyticsDeviceId
import platform.Foundation.NSUserDefaults

fun iosAnalyticsDeviceId(): String {
    val defaults = NSUserDefaults.standardUserDefaults
    val key = "product_analytics_device_id"
    return loadAnalyticsDeviceId({ defaults.stringForKey(key) }, { defaults.setObject(it, forKey = key) })
}
