package xyz.nekruz.cast

import android.app.Application

class CastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
