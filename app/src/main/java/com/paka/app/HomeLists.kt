package com.paka.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
internal fun CardsList(entries: List<Entry>, textSize: Float, onOpenCard: (Card) -> Unit, onOpenStack: (String) -> Unit) {
    val context = LocalContext.current
    var visibleEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    // Warm the session cache for photo passes on the visible page (and each
    // page scrolled to) so their first open is as instant as a reopen. The
    // quick dimensions match the viewer's so the cache keys line up.
    LaunchedEffect(visibleEntries) {
        val photoPages = visibleEntries.filterIsInstance<SingleEntry>()
            .mapNotNull { it.card.photoContent }
            .flatMap { it.pages }
            .distinctBy { it.documentId }
        if (photoPages.isEmpty()) return@LaunchedEffect
        val metrics = context.resources.displayMetrics
        withContext(Dispatchers.IO) {
            photoPages.forEach { page ->
                if (!isActive) return@withContext
                runCatching {
                    PhotoStore.decode(context, page.documentId, metrics.widthPixels / 2, metrics.heightPixels / 2)
                }
            }
        }
    }
    PagedList(entries, onPageChange = { visibleEntries = it }) { entry ->
        when (entry) {
            is SingleEntry -> Text(
                text = entry.card.name,
                color = White, fontSize = textSize.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().then(tapModifier { onOpenCard(entry.card) }),
            )
            is StackEntry -> Row(
                modifier = Modifier.fillMaxWidth().then(tapModifier { onOpenStack(entry.name) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.name, color = White, fontSize = textSize.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${entry.cards.size}", color = Grey, fontSize = 16.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

@Composable
internal fun CodesList(accounts: List<OtpAccount>, nowMs: Long, textSize: Float, onCopy: (String) -> Unit) {
    PagedList(accounts) { account ->
        val code = Totp.code(account, nowMs)
        val remaining = Totp.secondsRemaining(account, nowMs)
        // Keep the large code on the same vertical centerline as a pass name.
        // The secondary account label is overlaid above it so it cannot push
        // the primary content down when switching modes.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .then(tapModifier { onCopy(code) }),
        ) {
            Text(
                account.title(),
                color = Grey,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).offset(y = (-10).dp),
            )
            Row(modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                Text(formatCode(code), color = White, fontSize = textSize.sp, fontWeight = FontWeight.Normal, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                Text("$remaining", color = Grey, fontSize = 16.sp, fontWeight = FontWeight.Light)
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
