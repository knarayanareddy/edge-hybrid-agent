package com.edgehybrid.agent

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class EdgeHybridApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
