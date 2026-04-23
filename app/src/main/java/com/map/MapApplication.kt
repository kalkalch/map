// MapApplication.kt
package com.map

import android.app.Application
import timber.log.Timber
import com.map.util.SstpConnectionLog

/**
 * Main Application class for MAP (Mooqle.com Android Proxy)
 * 
 * @author Mooqle Team
 */
class MapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SstpConnectionLog.initialize(this)
        initLogging()
    }
    
    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
    }
    
    /**
     * Production logging tree - only logs warnings and errors,
     * filters out sensitive information.
     */
    private class ReleaseTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean {
            return priority >= android.util.Log.WARN
        }
        
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (!isLoggable(tag, priority)) return

            // Keep logs fully visible except password values.
            val safeMessage = message
                .replace(Regex("(?i)(password|passwd|pass)[=:]\\S+"), "password=***")
            
            when (priority) {
                android.util.Log.WARN -> android.util.Log.w(tag, safeMessage, t)
                android.util.Log.ERROR -> android.util.Log.e(tag, safeMessage, t)
                android.util.Log.ASSERT -> android.util.Log.wtf(tag, safeMessage, t)
            }
        }
    }
}
