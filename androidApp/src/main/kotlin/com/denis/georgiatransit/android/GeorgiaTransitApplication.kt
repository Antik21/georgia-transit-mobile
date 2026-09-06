package com.denis.georgiatransit.android

import android.app.Application
import com.denis.georgiatransit.shared.di.initGeorgiaTransitKoin

class GeorgiaTransitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initGeorgiaTransitKoin()
    }
}
