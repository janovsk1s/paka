package com.paka.app

import android.content.Context

/** Small on-device preferences. */
object Prefs {
    private const val FILE = "paka_prefs"
    // New key intentionally resets existing installations to the new five-row layout.
    private const val KEY_TEXT_SIZE = "list_text_size_v2"
    private const val KEY_VIBRATION = "vibration_enabled"
    private const val KEY_RETURN_HOME = "return_home_on_leave"
    private const val KEY_AUTO_LIGHT = "automatic_camera_light"

    const val DEFAULT_TEXT_SIZE = 30f

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun textSize(context: Context): Float = prefs(context).getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)

    fun setTextSize(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_TEXT_SIZE, value).apply()
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
}
