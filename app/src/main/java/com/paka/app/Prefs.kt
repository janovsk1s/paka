package com.paka.app

import android.content.Context

/** Small on-device preferences. */
internal object Prefs {
    private const val FILE = "paka_prefs"
    // New key intentionally resets existing installations to the new five-row layout.
    private const val KEY_TEXT_SIZE = "list_text_size_v2"
    private const val KEY_VIBRATION = "vibration_enabled"
    private const val KEY_RETURN_HOME = "return_home_on_leave"
    private const val KEY_AUTO_LIGHT = "automatic_camera_light"
    private const val KEY_MAX_CODE_BRIGHTNESS = "maximum_code_brightness"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_DEMO_MODE = "demo_mode"
    private const val KEY_PAGE_NUMBERS = "viewer_page_numbers"
    private const val KEY_OFFICIAL_FONT = "official_light_font"
    private const val KEY_LIGHT_GEAR = "light_sdk_settings_gear"
    private const val KEY_LANGUAGE = "developer_language"
    private const val KEY_LIGHT_MODE = "light_mode"

    const val MIN_TEXT_SIZE = 16f
    const val DEFAULT_TEXT_SIZE = 30f
    const val MAX_TEXT_SIZE = 42f

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun textSize(context: Context): Float =
        prefs(context).getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)

    fun setTextSize(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_TEXT_SIZE, value.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)).apply()
    }

    fun vibration(context: Context): Boolean = prefs(context).getBoolean(KEY_VIBRATION, true)

    fun setVibration(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATION, enabled).apply()
    }

    fun returnHome(context: Context): Boolean = prefs(context).getBoolean(KEY_RETURN_HOME, true)

    fun setReturnHome(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RETURN_HOME, enabled).apply()
    }

    fun autoLight(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_LIGHT, true)

    fun setAutoLight(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_LIGHT, enabled).apply()
    }

    fun maxCodeBrightness(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MAX_CODE_BRIGHTNESS, true)

    fun setMaxCodeBrightness(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAX_CODE_BRIGHTNESS, enabled).apply()
    }

    fun pageNumbers(context: Context): Boolean = prefs(context).getBoolean(KEY_PAGE_NUMBERS, true)

    fun setPageNumbers(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAGE_NUMBERS, enabled).apply()
    }

    fun officialFont(context: Context): Boolean = prefs(context).getBoolean(KEY_OFFICIAL_FONT, true)

    fun setOfficialFont(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OFFICIAL_FONT, enabled).apply()
    }

    fun lightMode(context: Context): Boolean = prefs(context).getBoolean(KEY_LIGHT_MODE, false)

    fun setLightMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIGHT_MODE, enabled).apply()
    }

    fun lightGear(context: Context): Boolean = prefs(context).getBoolean(KEY_LIGHT_GEAR, false)

    fun setLightGear(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIGHT_GEAR, enabled).apply()
    }

    fun language(context: Context): AppLanguage {
        val preferences = prefs(context)
        return if (preferences.contains(KEY_LANGUAGE)) {
            AppLanguage.fromTag(preferences.getString(KEY_LANGUAGE, null))
        } else {
            AppLanguage.defaultForSystem(context)
        }
    }

    fun hasExplicitLanguage(context: Context): Boolean = prefs(context).contains(KEY_LANGUAGE)

    fun setLanguage(context: Context, language: AppLanguage) {
        // apply() updates this process's in-memory preferences synchronously,
        // so the immediately recreated Activity sees the new locale without a
        // main-thread disk write.
        prefs(context).edit().putString(KEY_LANGUAGE, language.tag).apply()
    }

    fun demoMode(context: Context): Boolean = prefs(context).getBoolean(KEY_DEMO_MODE, false)

    fun setDemoMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEMO_MODE, enabled).apply()
    }

    fun onboardingComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }
}
