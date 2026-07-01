package com.paka.app

import android.content.Context

/** Small on-device preferences. */
object Prefs {
    private const val FILE = "paka_prefs"
    private const val KEY_TEXT_SIZE = "list_text_size"

    const val DEFAULT_TEXT_SIZE = 40f

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun textSize(context: Context): Float = prefs(context).getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)

    fun setTextSize(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_TEXT_SIZE, value).apply()
    }
}
