// LocalizationManager.kt
package com.map.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Manages application language switching without restart.
 * Supports Russian (default) and English.
 */
class LocalizationManager(context: Context) {
    private val appContext = context.applicationContext
    var currentLanguage: Locale = getSystemLocale()
        set(value) {
            field = value
            applyLanguage()
            saveToPreferences()
        }
    
    companion object {
        const val LANG_RU = "ru"
        const val LANG_EN = "en"
        const val LANG_AUTO = "auto"
        private const val PREFS_NAME = "map_prefs"
        private const val KEY_LANGUAGE = "language"
    }
    
    init {
        updateFromPreferences()
    }
    
    /**
     * Get current system locale.
     */
    private fun getSystemLocale(): Locale {
        val config = appContext.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locales = config.locales
            if (!locales.isEmpty && locales.get(0).language == "ru") {
                Locale("ru", "RU")
            } else {
                Locale.getDefault()
            }
        } else {
            @Suppress("DEPRECATION")
            if (config.locale.language == "ru") {
                Locale("ru", "RU")
            } else {
                Locale.getDefault()
            }
        }
    }
    
    /**
     * Apply language settings without restart.
     */
    @Suppress("DEPRECATION")
    private fun applyLanguage() {
        val config = Configuration(appContext.resources.configuration)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(currentLanguage)
        } else {
            config.locale = currentLanguage
        }
        
        appContext.resources.updateConfiguration(config, appContext.resources.displayMetrics)
    }
    
    /**
     * Save language preference.
     */
    private fun saveToPreferences() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, currentLanguage.language).apply()
    }
    
    /**
     * Load language from preferences.
     */
    private fun updateFromPreferences() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLang = prefs.getString(KEY_LANGUAGE, LANG_AUTO)
        
        currentLanguage = when (savedLang) {
            LANG_RU -> Locale("ru", "RU")
            LANG_EN -> Locale("en", "GB")
            else -> getSystemLocale()
        }
    }
    
    /**
     * Set language to specific locale.
     */
    fun setLanguage(langCode: String) {
        currentLanguage = when (langCode.lowercase()) {
            "ru" -> Locale("ru", "RU")
            "en" -> Locale("en", "GB")
            LANG_AUTO -> getSystemLocale()
            else -> getSystemLocale()
        }
    }
    
    /**
     * Get language code for current locale.
     */
    fun getLanguageCode(): String {
        return currentLanguage.language
    }
}
