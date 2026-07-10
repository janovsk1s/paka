package com.paka.app

import android.content.Context
import android.graphics.fonts.SystemFonts
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

internal val LocalPakaFontFamily = staticCompositionLocalOf<FontFamily?> { null }
private const val SYSTEM_FONT_DIR = "/system/fonts"

/**
 * Mirrors the official Light SDK font lookup:
 * use the Light Phone's system Akkurat family when present, otherwise fall back
 * to the previous Compose/default font so the old Paka look is recoverable.
 */
@Composable
internal fun ProvidePakaTypography(
    officialFont: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val fontFamily = remember(context, officialFont) {
        if (officialFont) lightPhoneFontFamily(context) else null
    }
    val baseStyle = LocalTextStyle.current
    val materialTypography = MaterialTheme.typography.withPakaFont(fontFamily)
    CompositionLocalProvider(
        LocalPakaFontFamily provides fontFamily,
        LocalTextStyle provides baseStyle.withPakaFont(fontFamily),
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            typography = materialTypography,
            content = content,
        )
    }
}

@Composable
internal fun TextStyle.withPakaFont(fontFamily: FontFamily? = null): TextStyle {
    val family = fontFamily ?: LocalPakaFontFamily.current
    return if (family == null) this else copy(fontFamily = family)
}

private fun lightPhoneFontFamily(context: Context): FontFamily {
    val systemFamily = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        systemAkkuratFonts()
    } else {
        null
    }
    return systemFamily ?: systemAkkuratFontFiles() ?: bundledAkkuratFonts(context) ?: FontFamily.Default
}

private fun systemAkkuratFontFiles(): FontFamily? {
    val fonts = listOf(
        "AkkuratLLTT-Light.ttf" to FontWeight.Light,
        "AkkuratLLTT-Regular.ttf" to FontWeight.Normal,
        "AkkuratLLTT-Bold.ttf" to FontWeight.Bold,
    ).mapNotNull { (name, weight) ->
        File(SYSTEM_FONT_DIR, name).takeIf { it.canRead() }?.let { Font(it, weight) }
    }
    return if (fonts.isEmpty()) null else FontFamily(fonts)
}

private fun bundledAkkuratFonts(context: Context): FontFamily? {
    val res = context.resources
    val pkg = context.packageName
    fun fontId(name: String): Int = res.getIdentifier(name, "font", pkg)
    val fonts = buildList {
        fontId("akkuratll_light").takeIf { it != 0 }
            ?.let { add(Font(it, FontWeight.Light)) }
        fontId("akkuratll_regular").takeIf { it != 0 }
            ?.let { add(Font(it, FontWeight.Normal)) }
        fontId("akkuratll_medium").takeIf { it != 0 }
            ?.let { add(Font(it, FontWeight.Medium)) }
        fontId("akkuratll_bold").takeIf { it != 0 }
            ?.let { add(Font(it, FontWeight.Bold)) }
    }
    return if (fonts.isEmpty()) null else FontFamily(fonts)
}

private fun Typography.withPakaFont(fontFamily: FontFamily?): Typography =
    if (fontFamily == null) {
        this
    } else {
        copy(
            displayLarge = displayLarge.copy(fontFamily = fontFamily),
            displayMedium = displayMedium.copy(fontFamily = fontFamily),
            displaySmall = displaySmall.copy(fontFamily = fontFamily),
            headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
            headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
            headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
            titleLarge = titleLarge.copy(fontFamily = fontFamily),
            titleMedium = titleMedium.copy(fontFamily = fontFamily),
            titleSmall = titleSmall.copy(fontFamily = fontFamily),
            bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
            bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
            bodySmall = bodySmall.copy(fontFamily = fontFamily),
            labelLarge = labelLarge.copy(fontFamily = fontFamily),
            labelMedium = labelMedium.copy(fontFamily = fontFamily),
            labelSmall = labelSmall.copy(fontFamily = fontFamily),
        )
    }

@RequiresApi(Build.VERSION_CODES.Q)
private fun systemAkkuratFonts(): FontFamily? {
    val fonts = SystemFonts.getAvailableFonts()
        .filter { it.file?.name?.startsWith("Akkurat", ignoreCase = true) == true }
        .mapNotNull { font ->
            val file = font.file ?: return@mapNotNull null
            val weight = FontWeight(font.style.weight)
            val style = if (font.style.slant != 0) FontStyle.Italic else FontStyle.Normal
            Font(file = file, weight = weight, style = style)
        }
    return if (fonts.isEmpty()) null else FontFamily(fonts)
}
