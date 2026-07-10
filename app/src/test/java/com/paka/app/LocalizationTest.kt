package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

class LocalizationTest {
    @Test
    fun supportedLanguagesAreTheFixedLtrAllowlist() {
        assertEquals(
            linkedSetOf("en", "lv", "et", "lt", "fi", "sv", "de", "sk"),
            AppLanguage.supportedTags,
        )
        assertFalse(AppLanguage.supportedTags.contains("ar"))
        assertFalse(AppLanguage.supportedTags.contains("he"))
    }

    @Test
    fun unsupportedOrMissingTagsFallBackToEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("ar"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("ar-SA"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("he"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("iw"))
    }

    @Test
    fun firstSupportedSystemLocaleBecomesTheAutomaticDefault() {
        assertEquals(
            AppLanguage.GERMAN,
            AppLanguage.fromSystemLocales(
                listOf(Locale.forLanguageTag("fr-FR"), Locale.forLanguageTag("de-AT")),
            ),
        )
        assertEquals(
            AppLanguage.LATVIAN,
            AppLanguage.fromSystemLocales(listOf(Locale.forLanguageTag("lv-LV"))),
        )
        assertEquals(
            AppLanguage.ENGLISH,
            AppLanguage.fromSystemLocales(
                listOf(Locale.forLanguageTag("ar-SA"), Locale.forLanguageTag("he-IL")),
            ),
        )
    }

    @Test
    fun applicationLocaleOnlyFollowsTheSystemWithoutAnExplicitChoice() {
        assertEquals(
            "",
            AppLanguage.desiredApplicationLocaleTags(
                selected = AppLanguage.GERMAN,
                hasExplicitSelection = false,
                supportedSystemLanguage = AppLanguage.GERMAN,
            ),
        )
        assertEquals(
            "en",
            AppLanguage.desiredApplicationLocaleTags(
                selected = AppLanguage.ENGLISH,
                hasExplicitSelection = false,
                supportedSystemLanguage = null,
            ),
        )
        assertEquals(
            "en",
            AppLanguage.desiredApplicationLocaleTags(
                selected = AppLanguage.ENGLISH,
                hasExplicitSelection = true,
                supportedSystemLanguage = AppLanguage.GERMAN,
            ),
        )
    }
}
