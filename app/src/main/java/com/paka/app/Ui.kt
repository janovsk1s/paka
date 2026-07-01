package com.paka.app

import android.annotation.SuppressLint
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val Black = Color(0xFF000000)
internal val White = Color(0xFFFFFFFF)
internal val Grey = Color(0xFF888888)

/** Clickable with no Material ripple, to keep the austere look. */
@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
internal fun tapModifier(onClick: () -> Unit): Modifier = tapModifier(onClick, null)

@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
internal fun tapModifier(onClick: () -> Unit, label: String? = null): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val semantics = if (label == null) Modifier else Modifier.semantics {
        contentDescription = label
        role = Role.Button
    }
    return semantics.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Tap + long-press, no ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@SuppressLint("ModifierFactoryExtensionFunction")
internal fun tapLongModifier(onClick: () -> Unit, onLongClick: () -> Unit, label: String? = null): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val semantics = if (label == null) Modifier else Modifier.semantics {
        contentDescription = label
        role = Role.Button
    }
    return semantics.combinedClickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
internal fun BackArrow(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Canvas(modifier = modifier.size(48.dp).then(tapModifier(onBack, "Back"))) {
        val s = size.minDimension
        drawLine(White, Offset(s * 0.59f, s * 0.31f), Offset(s * 0.41f, s * 0.5f), strokeWidth = s * 0.055f, cap = StrokeCap.Round)
        drawLine(White, Offset(s * 0.41f, s * 0.5f), Offset(s * 0.59f, s * 0.69f), strokeWidth = s * 0.055f, cap = StrokeCap.Round)
    }
}
