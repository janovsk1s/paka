package com.paka.app

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

internal val Black = Color(0xFF000000)
internal val White = Color(0xFFFFFFFF)
internal val Grey = Color(0xFF888888)

// Grey reads too faint on paper-white; light mode uses a darker secondary.
private const val GREY_ON_PAPER_ARGB = 0xFF555555
private val GreyOnPaper = Color(GREY_ON_PAPER_ARGB)

/**
 * Screen palette. Dark is Paka's native look; the Developer light-mode option
 * inverts the paper screens for e-ink displays such as Light Phone 2.
 *
 * Camera, capture review, and full-screen content viewers keep their physical
 * colors (a viewfinder or photo backdrop must not invert), and rendered codes
 * are always dark modules on a white field regardless of mode — those sites
 * keep the literal [Black]/[White]/[Grey] values.
 *
 * Backed by snapshot state, so composition and draw scopes that read these
 * update immediately when the Developer toggle flips.
 */
internal object Palette {
    var lightMode by mutableStateOf(false)
    val background: Color get() = if (lightMode) White else Black
    val foreground: Color get() = if (lightMode) Black else White
    val dim: Color get() = if (lightMode) GreyOnPaper else Grey
}

internal fun performPakaHaptic(
    context: Context,
    haptics: HapticFeedback,
    type: HapticFeedbackType = HapticFeedbackType.LongPress,
) {
    if (Prefs.vibration(context)) haptics.performHapticFeedback(type)
}

/** Clickable with no Material ripple, to keep the austere look. */
@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
internal fun tapModifier(onClick: () -> Unit): Modifier = tapModifier(onClick, null, null)

@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
internal fun tapModifier(
    onClick: () -> Unit,
    label: String? = null,
    clickLabel: String? = label,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val semantics = if (label == null) Modifier else Modifier.semantics {
        contentDescription = label
    }
    return semantics.clickable(
        interactionSource = interaction,
        indication = null,
        onClickLabel = clickLabel,
        role = Role.Button,
        onClick = {
            performPakaHaptic(context, haptics)
            onClick()
        },
    ).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
}

/** Tap + long-press, no ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
internal fun tapLongModifier(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    label: String? = null,
    longClickLabel: String? = null,
    clickLabel: String? = label,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val semantics = if (label == null) Modifier else Modifier.semantics {
        contentDescription = label
    }
    return semantics.combinedClickable(
        interactionSource = interaction,
        indication = null,
        onClickLabel = clickLabel,
        onLongClickLabel = longClickLabel,
        role = Role.Button,
        onClick = {
            performPakaHaptic(context, haptics)
            onClick()
        },
        onLongClick = {
            performPakaHaptic(context, haptics, HapticFeedbackType.LongPress)
            onLongClick()
        },
    ).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
}

/** Long-press interaction whose ordinary tap is intentionally silent. */
@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
internal fun longPressModifier(
    onLongClick: () -> Unit,
    label: String? = null,
    longClickLabel: String? = null,
): Modifier {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val currentOnLongClick = rememberUpdatedState(onLongClick)
    val actionLabel = longClickLabel ?: stringResource(R.string.accessibility_open_details)
    return Modifier
        .semantics(mergeDescendants = true) {
            if (label != null) contentDescription = label
            role = Role.Button
            onClick(label = actionLabel) {
                performPakaHaptic(context, haptics, HapticFeedbackType.LongPress)
                currentOnLongClick.value()
                true
            }
        }
        .pointerInput(context, haptics) {
            detectTapGestures(onLongPress = {
                performPakaHaptic(context, haptics, HapticFeedbackType.LongPress)
                currentOnLongClick.value()
            })
        }
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
}

@Composable
internal fun BackArrow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.foreground,
    onBack: () -> Unit,
) {
    val backLabel = stringResource(R.string.accessibility_back)
    val interaction = if (enabled) {
        tapModifier(onBack, backLabel, backLabel)
    } else {
        Modifier.semantics {
            contentDescription = backLabel
            disabled()
        }
    }
    Canvas(modifier = modifier.size(48.dp).then(interaction)) {
        val s = size.minDimension
        drawLine(
            color,
            Offset(s * 0.59f, s * 0.31f),
            Offset(s * 0.41f, s * 0.5f),
            strokeWidth = s * 0.055f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(s * 0.41f, s * 0.5f),
            Offset(s * 0.59f, s * 0.69f),
            strokeWidth = s * 0.055f,
            cap = StrokeCap.Round,
        )
    }
}
