package com.paka.app

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/** Fixed, deliberately LTR-only language allowlist exposed in Developer settings. */
internal enum class AppLanguage(
    val tag: String,
    @StringRes val displayNameRes: Int,
) {
    ENGLISH("en", R.string.language_name_english),
    LATVIAN("lv", R.string.language_name_latvian),
    ESTONIAN("et", R.string.language_name_estonian),
    LITHUANIAN("lt", R.string.language_name_lithuanian),
    FINNISH("fi", R.string.language_name_finnish),
    SWEDISH("sv", R.string.language_name_swedish),
    GERMAN("de", R.string.language_name_german),
    SLOVAK("sk", R.string.language_name_slovak),
    ;

    val locale: Locale get() = Locale.forLanguageTag(tag)

    fun applyTo(context: Context): Context {
        val selectedLocale = locale
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(selectedLocale)
            setLayoutDirection(selectedLocale)
        }
        return context.createConfigurationContext(configuration)
    }

    /**
     * Routes the language change through the OS per-app locale service (API 33+).
     * On Light OS this is the only path that works: its framework silently ignores
     * app-side Configuration.setLocale/setLocales, so [applyTo] cannot change the
     * resolved resources there. Returns true when the service accepted the change
     * and the system will recreate activities itself; false when the caller must
     * fall back to [applyTo] plus a manual recreate.
     */
    fun applyAsApplicationLocale(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val manager = context.getSystemService(LocaleManager::class.java) ?: return false
        if (manager.applicationLocales.toLanguageTags() == tag) return false
        manager.applicationLocales = LocaleList(locale)
        return true
    }

    companion object {
        val supportedTags: Set<String> = entries.mapTo(linkedSetOf(), AppLanguage::tag)

        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: ENGLISH

        internal fun fromSystemLocales(locales: List<Locale>): AppLanguage =
            supportedFromLocales(locales) ?: ENGLISH

        internal fun desiredApplicationLocaleTags(
            selected: AppLanguage,
            hasExplicitSelection: Boolean,
            supportedSystemLanguage: AppLanguage?,
        ): String = if (!hasExplicitSelection && supportedSystemLanguage != null) "" else selected.tag

        fun systemLanguage(context: Context): AppLanguage? {
            val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java)?.systemLocales
                    ?: Resources.getSystem().configuration.locales
            } else {
                Resources.getSystem().configuration.locales
            }
            return supportedFromLocales((0 until locales.size()).map(locales::get))
        }

        fun defaultForSystem(context: Context): AppLanguage = systemLanguage(context) ?: ENGLISH

        /**
         * Keeps the OS locale service and Paka's selection in agreement. With
         * no explicit in-app choice, a supported device locale remains automatic;
         * unsupported device locales are pinned to English. An explicit Developer
         * setting is always authoritative, including an explicit English choice.
         */
        fun reconcileApplicationLocale(
            context: Context,
            selected: AppLanguage,
            hasExplicitSelection: Boolean,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val desiredTags = desiredApplicationLocaleTags(
                selected = selected,
                hasExplicitSelection = hasExplicitSelection,
                supportedSystemLanguage = systemLanguage(context),
            )
            if (manager.applicationLocales.toLanguageTags() != desiredTags) {
                manager.applicationLocales = if (desiredTags.isEmpty()) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(desiredTags)
                }
            }
        }

        private fun supportedFromLocales(locales: List<Locale>): AppLanguage? =
            locales.firstNotNullOfOrNull { locale ->
                entries.firstOrNull { language -> language.tag == locale.language }
            }
    }
}

/** A validation result that remains locale-neutral until it reaches the UI. */
internal data class LocalizedMessage(
    @StringRes val resourceId: Int,
    val arguments: List<Any> = emptyList(),
) {
    fun resolve(context: Context): String = when (arguments.size) {
        0 -> context.getString(resourceId)
        1 -> context.getString(resourceId, arguments[0])
        2 -> context.getString(resourceId, arguments[0], arguments[1])
        MAX_ARGUMENTS -> context.getString(resourceId, arguments[0], arguments[1], arguments[2])
        else -> error("Localized messages support at most three arguments")
    }

    private companion object {
        const val MAX_ARGUMENTS = 3
    }
}
