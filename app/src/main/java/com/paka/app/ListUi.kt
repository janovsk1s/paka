package com.paka.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun SimpleTopBar(title: String, onBack: () -> Unit, trailing: String? = null) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        BackArrow(modifier = Modifier.align(Alignment.CenterStart).offset(x = (-30).dp), onBack = onBack)
        Text(title.replaceFirstChar { it.uppercase() }, color = White, fontSize = 16.sp, fontWeight = FontWeight.Normal)
        if (trailing != null) {
            Text(trailing, color = White, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Grey, fontSize = 18.sp, fontWeight = FontWeight.Normal)
    }
}

/** A vertically scrolling column with a proportional scrollbar shown only on overflow. */
@Composable
internal fun ScrollList(topPadding: Dp = 44.dp, spacing: Dp = 36.dp, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberHapticScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = topPadding, end = 14.dp, bottom = 8.dp).hardSnapVerticalScroll(state),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        VerticalScrollbar(state, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
internal fun rememberHapticScrollState(): ScrollState {
    return rememberScrollState()
}

/** Follow the finger while dragging, then cut immediately to the nearest viewport boundary. */
@Composable
internal fun Modifier.hardSnapVerticalScroll(state: ScrollState): Modifier {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var viewportPx by remember { mutableIntStateOf(1) }
    var settledValue by remember(state) { mutableIntStateOf(state.value) }
    val flingBehavior = remember(state) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val page = viewportPx.coerceAtLeast(1)
                val current = state.value
                val lower = (current / page) * page
                val upper = min(lower + page, state.maxValue)
                val target = listOf(lower, upper, state.maxValue)
                    .distinct()
                    .minByOrNull { abs(it - current) }
                    ?: current
                scrollBy((target - current).toFloat())
                if (target != settledValue) performPakaHaptic(context, haptics)
                settledValue = target
                return 0f
            }
        }
    }
    return onSizeChanged { viewportPx = it.height.coerceAtLeast(1) }
        .verticalScroll(state, flingBehavior = flingBehavior)
}

/** Thin white track + thicker white segment, square ends, sized to the visible portion. */
@Composable
private fun VerticalScrollbar(state: ScrollState, modifier: Modifier) {
    if (state.maxValue <= 0) return
    Canvas(modifier = modifier.offset(x = 18.dp).fillMaxHeight().width(6.dp)) {
        val vh = size.height
        val maxV = state.maxValue.toFloat()
        val total = vh + maxV
        val margin = 6.dp.toPx()
        val trackLen = (vh - 2 * margin).coerceAtLeast(1f)
        val thumbLen = (vh / total * trackLen).coerceIn(24.dp.toPx(), trackLen)
        val thumbY = margin + (state.value / maxV) * (trackLen - thumbLen)
        val centerX = size.width / 2f
        drawRect(White.copy(alpha = 0.3f), topLeft = Offset(centerX - 0.5.dp.toPx(), margin), size = Size(1.dp.toPx(), trackLen))
        drawRect(White, topLeft = Offset(centerX - 2.dp.toPx(), thumbY), size = Size(4.dp.toPx(), thumbLen))
    }
}

private const val ITEMS_PER_PAGE = 5

/** Five fixed row slots per page. Swipes replace the page instantly on release. */
@Composable
internal fun <T> PagedList(
    items: List<T>,
    endPadding: Dp = 14.dp,
    onPageChange: ((List<T>) -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val pages = remember(items) { items.chunked(ITEMS_PER_PAGE) }
    if (pages.isEmpty()) return
    HardCutPager(
        pageCount = pages.size,
        onPageChange = onPageChange?.let { report -> { page -> report(pages[page]) } },
    ) { currentPage, _ ->
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp, end = endPadding, bottom = 8.dp),
        ) {
            pages[currentPage].forEach { item ->
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    content(item)
                }
            }
            repeat(ITEMS_PER_PAGE - pages[currentPage].size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun HardCutPager(
    pageCount: Int,
    modifier: Modifier = Modifier,
    indicatorOffset: Dp = 18.dp,
    showIndicator: Boolean = true,
    gesturesEnabled: Boolean = true,
    onPageChange: ((Int) -> Unit)? = null,
    content: @Composable (Int, () -> Unit) -> Unit,
) {
    if (pageCount <= 0) return
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var page by remember { mutableIntStateOf(0) }
    val currentPage = page.coerceIn(0, pageCount - 1)

    LaunchedEffect(pageCount) {
        if (page >= pageCount) page = pageCount - 1
    }
    LaunchedEffect(currentPage) {
        onPageChange?.invoke(currentPage)
    }

    Box(
        modifier = modifier.fillMaxSize().then(
            if (gesturesEnabled) Modifier.pointerInput(pageCount, currentPage) {
                val threshold = 24.dp.toPx()
                var dragDistance = 0f
                var feedbackSent = false
                detectVerticalDragGestures(
                    onDragStart = {
                        dragDistance = 0f
                        feedbackSent = false
                    },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragDistance += amount
                        val canChangePage =
                            (dragDistance <= -threshold && currentPage < pageCount - 1) ||
                                (dragDistance >= threshold && currentPage > 0)
                        if (!feedbackSent && canChangePage) {
                            performPakaHaptic(context, haptics)
                            feedbackSent = true
                        }
                    },
                    onDragEnd = {
                        val destination = when {
                            dragDistance <= -threshold -> (currentPage + 1).coerceAtMost(pageCount - 1)
                            dragDistance >= threshold -> (currentPage - 1).coerceAtLeast(0)
                            else -> currentPage
                        }
                        page = destination
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            } else Modifier,
        ),
    ) {
        content(currentPage) { page = (currentPage + 1) % pageCount }
        if (showIndicator && pageCount > 1) {
            PageIndicator(
                page = currentPage,
                pageCount = pageCount,
                horizontalOffset = indicatorOffset,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun PageIndicator(page: Int, pageCount: Int, horizontalOffset: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.offset(x = horizontalOffset).fillMaxHeight().width(6.dp)) {
        val topMargin = 20.dp.toPx()
        val bottomMargin = 6.dp.toPx()
        val trackLength = (size.height - topMargin - bottomMargin).coerceAtLeast(1f)
        val thumbLength = (trackLength / pageCount).coerceAtLeast(24.dp.toPx()).coerceAtMost(trackLength)
        val availableTravel = trackLength - thumbLength
        val thumbY = topMargin + if (pageCount <= 1) 0f else availableTravel * page / (pageCount - 1)
        val centerX = size.width / 2f
        drawRect(
            White.copy(alpha = 0.3f),
            topLeft = Offset(centerX - 0.5.dp.toPx(), topMargin),
            size = Size(1.dp.toPx(), trackLength),
        )
        drawRect(
            White,
            topLeft = Offset(centerX - 2.dp.toPx(), thumbY),
            size = Size(4.dp.toPx(), thumbLength),
        )
    }
}
