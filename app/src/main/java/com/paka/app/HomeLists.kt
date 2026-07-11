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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onOpenCard: (Card) -> Unit,
    onOpenStack: (String) -> Unit,
) {
    PagedList(entries) { entry ->
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
internal fun CodesList(accounts: List<OtpAccount>, nowMs: Long, textSize: Float, onCopy: (String) -> Unit) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val localTextStyle = LocalTextStyle.current
    val copyLabel = stringResource(R.string.accessibility_copy_code)
    PagedList(accounts) { account ->
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
                TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Light),
            )
            val labelStyle = localTextStyle.merge(
                TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Light),
            )
            val timerWidthPx = remember(localTextStyle, density) {
                textMeasurer.measure(
                    text = "888",
                    style = timerStyle,
                    maxLines = 1,
                    softWrap = false,
                ).size.width
            }
            val labelHeightPx = remember(localTextStyle, density) {
                textMeasurer.measure(
                    text = "Åg",
                    style = labelStyle,
                    maxLines = 1,
                    softWrap = false,
                ).size.height
            }
            val representativeCode = when (code.length) {
                6 -> "888 888"
                7, 8 -> "8888 8888"
                else -> code
            }
            val codeWidthPx = (constraints.maxWidth - timerWidthPx - with(density) { 8.dp.roundToPx() })
                .coerceAtLeast(1)
            val verticalGapPx = with(density) { 4.dp.roundToPx() }
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
                        style = localTextStyle.merge(
                            TextStyle(
                                fontSize = candidate.sp,
                                fontWeight = FontWeight.Normal,
                                letterSpacing = 2.sp,
                            ),
                        ),
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
            val fittedCodeHeightPx = remember(representativeCode, fittedTextSize, localTextStyle, density) {
                textMeasurer.measure(
                    text = representativeCode,
                    style = localTextStyle.merge(
                        TextStyle(
                            fontSize = fittedTextSize.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 2.sp,
                        ),
                    ),
                    maxLines = 1,
                    softWrap = false,
                ).size.height
            }
            val labelOffset = with(density) {
                (-(fittedCodeHeightPx / 2f + verticalGapPx + labelHeightPx / 2f)).toDp()
            }
            Text(
                account.title(),
                color = Palette.dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
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
                    fontSize = fittedTextSize.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    remaining.toString(),
                    color = Palette.dim,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(with(density) { timerWidthPx.toDp() })
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}

private fun formatCode(code: String): String = when (code.length) {
    6 -> "${code.substring(0, 3)} ${code.substring(3)}"
    7, 8 -> "${code.substring(0, 4)} ${code.substring(4)}"
    else -> code
}

internal fun OtpAccount.duplicateKey(): String = listOf(
    secret.filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT),
    digits.toString(),
    period.toString(),
    algorithm.uppercase(Locale.ROOT),
).joinToString(":")
