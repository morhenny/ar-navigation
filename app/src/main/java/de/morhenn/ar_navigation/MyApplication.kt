package de.morhenn.ar_navigation


import android.app.Application
import de.morhenn.ar_navigation.persistance.AppDatabase
import de.morhenn.ar_navigation.persistance.PlaceRepository
import de.morhenn.ar_navigation.persistance.Webservice
import de.morhenn.ar_navigation.util.FileLog
import de.morhenn.ar_navigation.util.Utils

open class MyApplication : Application() {

    companion object {
        @JvmField
        var initialized = false
        const val TAG = "ArNavApp"
    }

    override fun onCreate() {
        super.onCreate()
        if (!initialized) {
            initialized = true
            Utils.init(applicationContext)
            FileLog.init(applicationContext, true)
            AppDatabase.init(applicationContext)
            Webservice.init()
            PlaceRepository.init()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> FileLog.fatal(e) }
        }
    }
}