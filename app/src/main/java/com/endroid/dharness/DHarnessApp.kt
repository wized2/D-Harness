package com.endroid.dharness

import android.app.Application

class DHarnessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeHelper.applyFromPrefs(this)
    }
}
