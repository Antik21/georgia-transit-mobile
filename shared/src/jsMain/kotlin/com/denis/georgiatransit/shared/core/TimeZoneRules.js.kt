package com.denis.georgiatransit.shared.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.JsModule
import kotlin.js.JsNonModule

@JsModule("@js-joda/timezone")
@JsNonModule
external object JsJodaTimeZoneModule

// Keep the module import in optimized browser builds. Importing it registers IANA rules.
@OptIn(ExperimentalJsExport::class)
@JsExport
val jsJodaTimeZoneRules = JsJodaTimeZoneModule
