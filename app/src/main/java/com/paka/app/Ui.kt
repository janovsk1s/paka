package com.paka.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

internal val Black = Color(0xFF000000)
internal val White = Color(0xFFFFFFFF)
internal val Grey = Color(0xFF888888)

/** Clickable with no Material ripple, to keep the austere look. */
@Composable
internal fun tapModifier(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Tap + long-press, no ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun tapLongModifier(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return Modifier.combinedClickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
internal fun BackArrow(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Canvas(modifier = modifier.size(28.dp).then(tapModifier(onBack))) {
        val s = size.minDimension
        drawLine(White, Offset(s * 0.62f, s * 0.2f), Offset(s * 0.34f, s * 0.5f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
        drawLine(White, Offset(s * 0.34f, s * 0.5f), Offset(s * 0.62f, s * 0.8f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
    }
}
