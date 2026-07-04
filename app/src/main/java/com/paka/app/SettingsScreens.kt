package com.paka.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class ManageGlyph { UP, DOWN }

internal data class ManageRow(val id: String, val name: String)

@Composable
internal fun SettingsScreen(
    onReorder: () -> Unit,
    onBackup: () -> Unit,
    vibrationEnabled: Boolean,
    onVibration: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("settings", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(listOf("reorder", "backup", "vibration", "about")) { item ->
                SettingsItem(
                    label = item,
                    trailing = if (item == "vibration") if (vibrationEnabled) "on" else "off" else null,
                    onClick = {
                        when (item) {
                            "reorder" -> onReorder()
                            "backup" -> onBackup()
                            "vibration" -> {
                                val enabled = !vibrationEnabled
                                onVibration(enabled)
                                if (enabled) performPakaHaptic(context, haptics)
                            }
                            else -> onAbout()
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun AboutScreen(onDev: () -> Unit, onBack: () -> Unit) {
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    val hiddenDeveloperTap = {
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 600) taps + 1 else 1
        lastTap = now
        if (taps >= 3) {
            taps = 0
            onDev()
        }
    }
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("about", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp, end = 14.dp, bottom = 8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Row(
                        modifier = Modifier.fillMaxWidth().then(tapModifier(hiddenDeveloperTap)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Paka",
                            color = White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            color = Grey,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                }
                Box(Modifier.weight(3f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "Latvian for “package”. Saves passes and carries 2FA codes in a light way. Long-presses may reveal more options.",
                        color = White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Column {
                        Text("From a Latvian in Vienna.", color = White, fontSize = 20.sp, fontWeight = FontWeight.Normal)
                        Text("@janovsk1s", color = Grey, fontSize = 16.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsItem(label: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().then(tapModifier(onClick)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = White, fontSize = 30.sp, fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
internal fun DevScreen(
    textSize: Float,
    onTextSize: (Float) -> Unit,
    returnHomeEnabled: Boolean,
    onReturnHome: (Boolean) -> Unit,
    autoLightEnabled: Boolean,
    onAutoLight: (Boolean) -> Unit,
    maxCodeBrightnessEnabled: Boolean,
    onMaxCodeBrightness: (Boolean) -> Unit,
    pageNumbersEnabled: Boolean,
    onPageNumbers: (Boolean) -> Unit,
    demoModeEnabled: Boolean,
    onDemoMode: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("developer", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(listOf(0, 1, 2, 3, 4, 5, 6, 7)) { item ->
                when (item) {
                    0 -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("text size", color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                        Text("${textSize.toInt()} sp", color = White, fontSize = 18.sp, fontWeight = FontWeight.Normal)
                    }
                    1 -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "−",
                            color = White,
                            fontSize = 30.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f).then(tapModifier { onTextSize((textSize - 1f).coerceAtLeast(16f)) }),
                        )
                        Text(
                            "+",
                            color = White,
                            fontSize = 30.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f).then(tapModifier { onTextSize((textSize + 1f).coerceAtMost(64f)) }),
                        )
                    }
                    2 -> Text(
                        "KlimaTicket",
                        color = White,
                        fontSize = textSize.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    3 -> SettingsItem(
                        label = "return home",
                        trailing = if (returnHomeEnabled) "on" else "off",
                        onClick = { onReturnHome(!returnHomeEnabled) },
                    )
                    4 -> SettingsItem(
                        label = "auto light",
                        trailing = if (autoLightEnabled) "on" else "off",
                        onClick = { onAutoLight(!autoLightEnabled) },
                    )
                    5 -> SettingsItem(
                        label = "max brightness",
                        trailing = if (maxCodeBrightnessEnabled) "on" else "off",
                        onClick = { onMaxCodeBrightness(!maxCodeBrightnessEnabled) },
                    )
                    6 -> SettingsItem(
                        label = "page numbers",
                        trailing = if (pageNumbersEnabled) "on" else "off",
                        onClick = { onPageNumbers(!pageNumbersEnabled) },
                    )
                    else -> SettingsItem(
                        label = "demo mode",
                        trailing = if (demoModeEnabled) "on" else "off",
                        onClick = { onDemoMode(!demoModeEnabled) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ManageScreen(
    rows: List<ManageRow>,
    onUp: (String) -> Unit,
    onDown: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar("reorder", onBack)
        PagedList(rows, endPadding = 0.dp) { row ->
                val index = rows.indexOfFirst { it.id == row.id }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.name,
                        color = White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ManageGlyphAction(ManageGlyph.UP, "Move ${row.name} up", Modifier.width(48.dp), enabled = index > 0) { onUp(row.id) }
                    ManageGlyphAction(ManageGlyph.DOWN, "Move ${row.name} down", Modifier.width(48.dp), enabled = index < rows.lastIndex) { onDown(row.id) }
                }
        }
    }
}

@Composable
private fun ManageGlyphAction(
    glyph: ManageGlyph,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Boundary arrows remain non-interactive, but keep the same visual weight
    // as every other reorder glyph.
    val color = White
    Box(
        modifier = modifier
            .height(48.dp)
            .then(if (enabled) tapModifier(onClick, description) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            when (glyph) {
                ManageGlyph.UP -> {
                    drawLine(color, Offset(size.width * 0.27f, size.height * 0.59f), Offset(size.width * 0.5f, size.height * 0.36f), stroke, StrokeCap.Square)
                    drawLine(color, Offset(size.width * 0.5f, size.height * 0.36f), Offset(size.width * 0.73f, size.height * 0.59f), stroke, StrokeCap.Square)
                }
                ManageGlyph.DOWN -> {
                    drawLine(color, Offset(size.width * 0.27f, size.height * 0.41f), Offset(size.width * 0.5f, size.height * 0.64f), stroke, StrokeCap.Square)
                    drawLine(color, Offset(size.width * 0.5f, size.height * 0.64f), Offset(size.width * 0.73f, size.height * 0.41f), stroke, StrokeCap.Square)
                }
            }
        }
    }
}
