package com.denis.georgiatransit.shared.domain.analytics.schema

/** Stable names used by the clients and the BFF's Amplitude publisher. */
object AnalyticsCatalog {
    const val Identify = "\$identify"
    const val WebAppOpened = "web_app_opened"
    const val ZoomStarted = "zoom_started"
    const val RoutesSelected = "routes_selected"
    const val RecentRoutesUsed = "recent_routes_used"
    const val RouteSearchUsed = "route_search_used"
    const val JourneyPlanningStarted = "journey_planning_started"
    const val StopOpened = "stop_opened"
    const val VehicleTapped = "vehicle_tapped"
    const val LandingPageViewed = "landing_page_viewed"
    const val LandingLanguageChanged = "landing_language_changed"
    const val LandingPlatformClicked = "landing_platform_clicked"

    const val Client = "client"
    const val City = "city"
    const val ZoomType = "type"
    const val RouteCount = "route_count"
    const val Source = "source"
    const val EntrySource = "entry_source"
    const val Language = "language"
    const val PreviousLanguage = "previous_language"
    const val TargetPlatform = "target_platform"

    const val Android = "android"
    const val Ios = "ios"
    const val Web = "web"
    const val TelegramMiniApp = "telegram_mini_app"
    const val Landing = "landing"
    const val Browser = "browser"
    const val Telegram = "telegram"
    const val FingerDoubleTap = "finger_double_tap"
    const val FingerPinch = "finger_pinch"
    const val Button = "button"
    const val MapButton = "map_button"
    const val LongPress = "long_press"
    const val SessionIdleMillis = 30 * 60 * 1_000L

    fun insertId(client: String, sessionId: Long, eventKey: String): String = "$client:$sessionId:$eventKey"
}
