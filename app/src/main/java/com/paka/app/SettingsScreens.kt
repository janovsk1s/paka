package com.paka.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class ManageGlyph { UP, DOWN }
private enum class SettingsAction { REORDER, BACKUP, VIBRATION, ABOUT }
private enum class DeveloperItem {
    TEXT_SIZE, LANGUAGE, RETURN_HOME, AUTO_LIGHT,
    MAX_BRIGHTNESS, PAGE_NUMBERS, OFFICIAL_FONT, LIGHT_GEAR, DEMO_MODE,
}
private enum class TextSizeItem { SAMPLE, SMALLER, LARGER }

internal enum class DeveloperRoute { MENU, TEXT_SIZE, LANGUAGE }
internal enum class ManageKind { PASSES, CODES, STACK }

internal data class ManageRow(val id: String, val name: String)

@Composable
internal fun SettingsScreen(
    manageKind: ManageKind,
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
        SimpleTopBar(stringResource(R.string.settings_title), onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PagedList(SettingsAction.entries) { item ->
                SettingsItem(
                    label = when (item) {
                        SettingsAction.REORDER -> stringResource(
                            if (manageKind == ManageKind.CODES) {
                                R.string.settings_reorder_codes
                            } else {
                                R.string.settings_reorder_passes
                            },
                        )
                        SettingsAction.BACKUP -> stringResource(R.string.settings_backup)
                        SettingsAction.VIBRATION -> stringResource(R.string.settings_vibration)
                        SettingsAction.ABOUT -> stringResource(R.string.settings_about)
                    },
                    trailing = if (item == SettingsAction.VIBRATION) {
                        stringResource(if (vibrationEnabled) R.string.core_on else R.string.core_off)
                    } else {
                        null
                    },
                    onClick = {
                        when (item) {
                            SettingsAction.REORDER -> onReorder()
                            SettingsAction.BACKUP -> onBackup()
                            SettingsAction.VIBRATION -> {
                                val enabled = !vibrationEnabled
                                onVibration(enabled)
                                if (enabled) performPakaHaptic(context, haptics)
                            }
                            SettingsAction.ABOUT -> onAbout()
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
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val hiddenDeveloperTap: () -> Unit = {
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 600) taps + 1 else 1
        lastTap = now
        if (taps >= 3) {
            taps = 0
            performPakaHaptic(context, haptics)
            onDev()
        }
    }
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.about_title), onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ScrollList(topPadding = 20.dp, spacing = 36.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { hiddenDeveloperTap() })
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        color = White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                    )
                    // The name measures first; the version column takes the rest and
                    // wraps its long channel label instead of squeezing the name.
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                        Text(
                            "v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
                            color = Grey,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            textAlign = TextAlign.End,
                        )
                        if (BuildConfig.RELEASE_CHANNEL_LABEL.isNotBlank()) {
                            Text(
                                BuildConfig.RELEASE_CHANNEL_LABEL,
                                color = Grey,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Light,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.about_description),
                    color = White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                )
                Column {
                    Text(
                        stringResource(R.string.about_byline),
                        color = White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal,
                    )
                    Text("@janovsk1s", color = Grey, fontSize = 16.sp, fontWeight = FontWeight.Light)
                }
            }
        }
    }
}

@Composable
internal fun SettingsItem(label: String, trailing: String? = null, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .then(
                onClick?.let { action ->
                    tapModifier(onClick = action, clickLabel = label)
                } ?: Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoFitText(
            label,
            color = if (onClick == null) Grey else White,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
internal fun DevScreen(
    textSize: Float,
    onTextSize: (Float) -> Unit,
    language: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
    returnHomeEnabled: Boolean,
    onReturnHome: (Boolean) -> Unit,
    autoLightEnabled: Boolean,
    onAutoLight: (Boolean) -> Unit,
    maxCodeBrightnessEnabled: Boolean,
    onMaxCodeBrightness: (Boolean) -> Unit,
    pageNumbersEnabled: Boolean,
    onPageNumbers: (Boolean) -> Unit,
    officialFontEnabled: Boolean,
    onOfficialFont: (Boolean) -> Unit,
    lightGearEnabled: Boolean,
    onLightGear: (Boolean) -> Unit,
    demoModeEnabled: Boolean,
    onDemoMode: (Boolean) -> Unit,
    initialRoute: DeveloperRoute = DeveloperRoute.MENU,
    onBack: () -> Unit,
) {
    var route by remember(initialRoute) { mutableStateOf(initialRoute) }
    val navigateBack = { if (route == DeveloperRoute.MENU) onBack() else route = DeveloperRoute.MENU }
    BackHandler { navigateBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(
            when (route) {
                DeveloperRoute.MENU -> stringResource(R.string.developer_title)
                DeveloperRoute.TEXT_SIZE -> stringResource(R.string.developer_text_size)
                DeveloperRoute.LANGUAGE -> stringResource(R.string.language_title)
            },
            navigateBack,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (route) {
                DeveloperRoute.MENU -> PagedList(DeveloperItem.entries) { item ->
                    val enabled = stringResource(R.string.core_on)
                    val disabled = stringResource(R.string.core_off)
                    SettingsItem(
                        label = when (item) {
                            DeveloperItem.TEXT_SIZE -> stringResource(R.string.developer_text_size)
                            DeveloperItem.LANGUAGE -> stringResource(R.string.developer_language)
                            DeveloperItem.RETURN_HOME -> stringResource(R.string.developer_return_home)
                            DeveloperItem.AUTO_LIGHT -> stringResource(R.string.developer_auto_light)
                            DeveloperItem.MAX_BRIGHTNESS -> stringResource(R.string.developer_max_brightness)
                            DeveloperItem.PAGE_NUMBERS -> stringResource(R.string.developer_page_numbers)
                            DeveloperItem.OFFICIAL_FONT -> stringResource(R.string.developer_official_font)
                            DeveloperItem.LIGHT_GEAR -> stringResource(R.string.developer_light_gear)
                            DeveloperItem.DEMO_MODE -> stringResource(R.string.developer_demo_mode)
                        },
                        trailing = when (item) {
                            DeveloperItem.TEXT_SIZE -> stringResource(
                                R.string.developer_text_size_value,
                                textSize.toInt(),
                            )
                            DeveloperItem.LANGUAGE -> stringResource(language.displayNameRes)
                            DeveloperItem.RETURN_HOME -> if (returnHomeEnabled) enabled else disabled
                            DeveloperItem.AUTO_LIGHT -> if (autoLightEnabled) enabled else disabled
                            DeveloperItem.MAX_BRIGHTNESS -> if (maxCodeBrightnessEnabled) enabled else disabled
                            DeveloperItem.PAGE_NUMBERS -> if (pageNumbersEnabled) enabled else disabled
                            DeveloperItem.OFFICIAL_FONT -> if (officialFontEnabled) enabled else disabled
                            DeveloperItem.LIGHT_GEAR -> if (lightGearEnabled) enabled else disabled
                            DeveloperItem.DEMO_MODE -> if (demoModeEnabled) enabled else disabled
                        },
                        onClick = {
                            when (item) {
                                DeveloperItem.TEXT_SIZE -> route = DeveloperRoute.TEXT_SIZE
                                DeveloperItem.LANGUAGE -> route = DeveloperRoute.LANGUAGE
                                DeveloperItem.RETURN_HOME -> onReturnHome(!returnHomeEnabled)
                                DeveloperItem.AUTO_LIGHT -> onAutoLight(!autoLightEnabled)
                                DeveloperItem.MAX_BRIGHTNESS -> onMaxCodeBrightness(!maxCodeBrightnessEnabled)
                                DeveloperItem.PAGE_NUMBERS -> onPageNumbers(!pageNumbersEnabled)
                                DeveloperItem.OFFICIAL_FONT -> onOfficialFont(!officialFontEnabled)
                                DeveloperItem.LIGHT_GEAR -> onLightGear(!lightGearEnabled)
                                DeveloperItem.DEMO_MODE -> onDemoMode(!demoModeEnabled)
                            }
                        },
                    )
                }
                DeveloperRoute.TEXT_SIZE -> PagedList(TextSizeItem.entries) { item ->
                    when (item) {
                        TextSizeItem.SAMPLE -> Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.developer_sample),
                                color = White,
                                fontSize = textSize.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.developer_text_size_value, textSize.toInt()),
                                color = Grey,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                        TextSizeItem.SMALLER -> SettingsItem(
                            label = stringResource(R.string.developer_text_smaller),
                            onClick = if (textSize > Prefs.MIN_TEXT_SIZE) {
                                { onTextSize((textSize - 1f).coerceAtLeast(Prefs.MIN_TEXT_SIZE)) }
                            } else null,
                        )
                        TextSizeItem.LARGER -> SettingsItem(
                            label = stringResource(R.string.developer_text_larger),
                            onClick = if (textSize < Prefs.MAX_TEXT_SIZE) {
                                { onTextSize((textSize + 1f).coerceAtMost(Prefs.MAX_TEXT_SIZE)) }
                            } else null,
                        )
                    }
                }
                DeveloperRoute.LANGUAGE -> PagedList(AppLanguage.entries) { item ->
                    SettingsItem(
                        label = stringResource(item.displayNameRes),
                        trailing = if (item == language) stringResource(R.string.language_selected) else null,
                        onClick = if (item == language) null else { { onLanguage(item) } },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ManageScreen(
    rows: List<ManageRow>,
    kind: ManageKind = ManageKind.STACK,
    onUp: (String) -> Unit,
    onDown: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding().padding(horizontal = 28.dp)) {
        val title = stringResource(
            when (kind) {
                ManageKind.PASSES -> R.string.reorder_passes_title
                ManageKind.CODES -> R.string.reorder_codes_title
                ManageKind.STACK -> R.string.reorder_stack_title
            },
        )
        SimpleTopBar(title, onBack)
        if (rows.isEmpty()) {
            EmptyHint(stringResource(R.string.reorder_empty))
        } else PagedList(rows, endPadding = 0.dp) { row ->
                val index = rows.indexOfFirst { it.id == row.id }
                Row(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
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
                    ManageGlyphAction(
                        ManageGlyph.UP,
                        stringResource(R.string.reorder_move_up, row.name),
                        Modifier.width(48.dp),
                        enabled = index > 0,
                    ) { onUp(row.id) }
                    ManageGlyphAction(
                        ManageGlyph.DOWN,
                        stringResource(R.string.reorder_move_down, row.name),
                        Modifier.width(48.dp),
                        enabled = index < rows.lastIndex,
                    ) { onDown(row.id) }
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
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
            .then(if (enabled) tapModifier(onClick) else Modifier),
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
