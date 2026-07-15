package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraScanRegionTest {
    // Portrait Light Phone-style geometry: 720x1280 upright analysis frame
    // shown FILL_CENTER in a 1080x2340 view. scale = 2340/1280 = 1.828125,
    // so the 0.70 * 1080 px guide square spans 413.5 frame pixels.
    private val frameWidth = 720
    private val frameHeight = 1280
    private val viewWidth = 1080
    private val viewHeight = 2340
    private val centerX = frameWidth / 2f
    private val centerY = frameHeight / 2f

    private fun select(centers: List<SymbolCenter>): Int? = selectAimedSymbol(
        centers = centers,
        geometry = ScanGeometry(frameWidth, frameHeight, viewWidth, viewHeight),
    )

    @Test
    fun `nothing detected selects nothing`() {
        assertNull(select(emptyList()))
    }

    @Test
    fun `a lone symbol is accepted even outside the guide`() {
        assertEquals(0, select(listOf(SymbolCenter(40f, 60f))))
    }

    @Test
    fun `with several symbols only the one inside the guide is selected`() {
        val outside = SymbolCenter(centerX, 100f)
        val inside = SymbolCenter(centerX + 180f, centerY + 180f)
        assertEquals(1, select(listOf(outside, inside)))
        assertEquals(0, select(listOf(inside, outside)))
    }

    @Test
    fun `two symbols inside the guide select nothing`() {
        val first = SymbolCenter(centerX - 100f, centerY)
        val second = SymbolCenter(centerX + 100f, centerY)
        assertNull(select(listOf(first, second)))
    }

    @Test
    fun `several symbols all outside the guide select nothing`() {
        assertNull(select(listOf(SymbolCenter(30f, 40f), SymbolCenter(700f, 1250f))))
    }

    @Test
    fun `guide boundary uses the scaled square size`() {
        val outside = SymbolCenter(centerX, 100f)
        // halfSide = 0.70 * 1080 / (2340 / 1280) / 2 = 206.77 frame pixels.
        val justInside = SymbolCenter(centerX + 205f, centerY)
        val justOutside = SymbolCenter(centerX + 210f, centerY)
        assertEquals(1, select(listOf(outside, justInside)))
        assertNull(select(listOf(outside, justOutside)))
    }

    @Test
    fun `unknown view size keeps scanning instead of guessing`() {
        val centers = listOf(SymbolCenter(centerX, centerY), SymbolCenter(30f, 40f))
        assertNull(
            selectAimedSymbol(
                centers = centers,
                geometry = ScanGeometry(frameWidth, frameHeight, viewWidth = 0, viewHeight = 0),
            ),
        )
    }

    @Test
    fun `landscape frames scale by the covering axis`() {
        // 1280x720 frame covering a 1080x2340 view scales by width: the
        // guide square spans 0.70 * 1080 / (2340 / 720) = 232.6 frame pixels.
        val outside = SymbolCenter(100f, 100f)
        val inside = SymbolCenter(640f + 110f, 360f)
        assertEquals(
            1,
            selectAimedSymbol(
                centers = listOf(outside, inside),
                geometry = ScanGeometry(frameWidth = 1280, frameHeight = 720, viewWidth = 1080, viewHeight = 2340),
            ),
        )
    }
}
