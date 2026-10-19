package com.paka.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

internal sealed interface Entry
internal data class SingleEntry(val card: Card) : Entry
internal data class StackEntry(val name: String, val cards: List<Card>) : Entry

internal fun buildEntries(cards: List<Card>): List<Entry> {
    val groupedStacks = linkedMapOf<String, MutableList<Card>>()
    cards.forEach { card ->
        card.stack?.let { stack -> groupedStacks.getOrPut(stack) { mutableListOf() }.add(card) }
    }
    val entries = mutableListOf<Entry>()
    val seen = mutableSetOf<String>()
    for (c in cards) {
        val s = c.stack
        if (s == null) entries.add(SingleEntry(c))
        else if (seen.add(s)) entries.add(StackEntry(s, groupedStacks.getValue(s)))
    }
    return entries
}

@Composable
internal fun CardsList(
    entries: List<Entry>,
    textSize: Float,
    pageState: MutableIntState,
    onOpenCard: (Card) -> Unit,
    onOpenStack: (String) -> Unit,
) {
    PagedList(entries, pageState = pageState) { entry ->
        when (entry) {
            is SingleEntry -> Box(
                modifier = Modifier.fillMaxSize().then(tapModifier { onOpenCard(entry.card) }),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = entry.card.name,
                    color = Palette.foreground,
                    fontSize = textSize.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is StackEntry -> Row(
                modifier = Modifier.fillMaxSize().then(tapModifier { onOpenStack(entry.name) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.name,
                    color = Palette.foreground,
                    fontSize = textSize.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(entry.cards.size.toString(), color = Palette.dim, fontSize = 16.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

@Composable
internal fun CodesList(
    accounts: List<OtpAccount>,
    textSize: Float,
    pageState: MutableIntState,
    onCopy: (String) -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val localTextStyle = LocalTextStyle.current
    val copyLabel = stringResource(R.string.accessibility_copy_code)
    val foreground by rememberIsForeground()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Only this list redraws each second, and only while Paka is in the foreground.
    LaunchedEffect(foreground) {
        while (foreground) {
            nowMs = System.currentTimeMillis()
            // Wake on the next whole second so a code never lingers past its rollover.
            delay(1_000L - nowMs % 1_000L)
        }
    }
    PagedList(accounts, pageState = pageState) { account ->
        val code = Totp.code(account, nowMs)
        val remaining = Totp.secondsRemaining(account, nowMs)
        val remainingDescription = pluralStringResource(
            R.plurals.accessibility_seconds_remaining,
            remaining,
            remaining,
        )
        // Keep the large code on the same vertical centerline as a pass name.
        // The secondary account label is overlaid above it so it cannot push
        // the primary content down when switching modes.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .semantics { stateDescription = remainingDescription }
                .then(tapModifier(onClick = { onCopy(code) }, clickLabel = copyLabel)),
        ) {
            val timerStyle = localTextStyle.merge(
                TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Light).withTypeSizedLines(),
            )
            val labelStyle = localTextStyle.merge(
                TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Light).withTypeSizedLines(),
            )
            fun codeStyle(size: Float) = localTextStyle.merge(
                TextStyle(fontSize = size.sp, fontWeight = FontWeight.Normal, letterSpacing = 2.sp)
                    .withTypeSizedLines(),
            )
            val timerWidthPx = remember(localTextStyle, density) {
                textMeasurer.measure(
                    text = "888",
                    style = timerStyle,
                    maxLines = 1,
                    softWrap = false,
                ).size.width
            }
            val labelLayout = remember(localTextStyle, density) {
                textMeasurer.measure(
                    text = "Åg",
                    style = labelStyle,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            val labelHeightPx = labelLayout.size.height
            val representativeCode = when (code.length) {
                6 -> "888 888"
                7, 8 -> "8888 8888"
                else -> code
            }
            val codeWidthPx = (constraints.maxWidth - timerWidthPx - with(density) { 8.dp.roundToPx() })
                .coerceAtLeast(1)
            val verticalGapPx = with(density) { 6.dp.roundToPx() }
            val fittedTextSize = remember(
                representativeCode,
                textSize,
                codeWidthPx,
                constraints.maxHeight,
                labelHeightPx,
                localTextStyle,
                density,
            ) {
                var candidate = textSize.coerceIn(16f, 64f)
                while (candidate > 16f) {
                    val measured = textMeasurer.measure(
                        text = representativeCode,
                        style = codeStyle(candidate),
                        maxLines = 1,
                        softWrap = false,
                    ).size
                    val fitsWidth = measured.width <= codeWidthPx
                    val fitsHeight = measured.height + labelHeightPx + verticalGapPx <= constraints.maxHeight
                    if (fitsWidth && fitsHeight) break
                    candidate -= 1f
                }
                candidate
            }
            val codeLayout = remember(representativeCode, fittedTextSize, localTextStyle, density) {
                textMeasurer.measure(
                    text = representativeCode,
                    style = codeStyle(fittedTextSize),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            // Place the label by baselines, not line boxes: its baseline sits a fixed gap
            // above the top of the code's digits, so it reads as this code's account on any
            // font and font scale instead of drifting toward the previous row.
            val labelOffset = with(density) {
                val codeBaseline = codeLayout.firstBaseline - codeLayout.size.height / 2f
                val digitTop = codeBaseline - DIGIT_HEIGHT_EM * fittedTextSize.sp.toPx()
                val labelBaseline = digitTop - verticalGapPx
                (labelBaseline - labelLayout.firstBaseline + labelHeightPx / 2f).toDp()
            }
            Text(
                account.title(),
                color = Palette.dim,
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart).offset(y = labelOffset),
            )
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatCode(code),
                    color = Palette.foreground,
                    style = codeStyle(fittedTextSize),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    remaining.toString(),
                    color = Palette.dim,
                    style = timerStyle,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(with(density) { timerWidthPx.toDp() })
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}

// Lining digits stand about 0.72 em in sans faces such as Akkurat and Roboto.
private const val DIGIT_HEIGHT_EM = 0.72f

/**
 * Explicit one-em line heights with centred glyphs, so 2FA rows do not inherit
 * Material's 24 sp body line height, which is taller than the 12 sp label and
 * shorter than the 30 sp code.
 */
private fun TextStyle.withTypeSizedLines(): TextStyle = copy(
    lineHeight = 1.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private fun formatCode(code: String): String = when (code.length) {
    6 -> "${code.substring(0, 3)} ${code.substring(3)}"
    8 -> "${code.substring(0, 4)} ${code.substring(4)}"
    else -> code
}

internal fun OtpAccount.duplicateKey(): String = listOf(
    Totp.normalizedSecret(secret),
    digits.toString(),
    period.toString(),
    algorithm.uppercase(Locale.ROOT),
).joinToString(":")
