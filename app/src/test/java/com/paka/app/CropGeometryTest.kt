package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The crop selection must stay inside the photo and above the minimum size. */
class CropGeometryTest {

    private val centered = CropRect(0.25f, 0.25f, 0.75f, 0.75f)
    private val minSize = 0.15f

    @Test
    fun minimumSizeBoxAtAnEdgeCanStillBeResizedWithoutCrashing() {
        // Shrinking to the minimum and moving to an edge can round the width a
        // hair below minSize; the corner clamp range must not become empty.
        for (step in 0 until 2_000) {
            val start = step / 2_000f * 0.8f
            val minimal = CropRect(start, start, start + 0.5f, start + 0.5f)
                .resized(CropHandle.TOP_LEFT, dx = 10f, dy = 10f, minSize = minSize)
            for (edge in listOf(minimal.movedBy(-10f, -10f), minimal.movedBy(10f, 10f))) {
                CropHandle.entries.forEach { handle ->
                    val resized = edge.resized(handle, dx = 0.001f, dy = 0.001f, minSize = minSize)
                    assertTrue(resized.left >= 0f && resized.top >= 0f)
                    assertTrue(resized.right <= 1f && resized.bottom <= 1f)
                }
            }
        }
    }

    @Test
    fun movingClampsAtTheEdges() {
        val moved = centered.movedBy(dx = 10f, dy = -10f)
        assertEquals(CropRect(0.5f, 0f, 1f, 0.5f), moved)
        assertEquals(centered.width, moved.width, 1e-6f)
        assertEquals(centered.height, moved.height, 1e-6f)
    }

    @Test
    fun cornerResizeRespectsTheMinimumSize() {
        val collapsed = centered.resized(CropHandle.TOP_LEFT, dx = 10f, dy = 10f, minSize = minSize)
        assertEquals(centered.right - minSize, collapsed.left, 1e-6f)
        assertEquals(centered.bottom - minSize, collapsed.top, 1e-6f)
    }

    @Test
    fun cornerResizeClampsToThePhoto() {
        val expanded = centered.resized(CropHandle.BOTTOM_RIGHT, dx = 10f, dy = 10f, minSize = minSize)
        assertEquals(CropRect(0.25f, 0.25f, 1f, 1f), expanded)
    }

    @Test
    fun dragStartPicksCornersInsideOrNothing() {
        val width = 1_000f
        val height = 1_000f
        val radius = 48f
        assertEquals(
            CropDrag.Corner(CropHandle.TOP_LEFT),
            centered.dragAt(260f, 260f, CropDragTarget(width, height, radius)),
        )
        assertEquals(
            CropDrag.Corner(CropHandle.BOTTOM_RIGHT),
            centered.dragAt(740f, 760f, CropDragTarget(width, height, radius)),
        )
        assertEquals(CropDrag.Move, centered.dragAt(500f, 500f, CropDragTarget(width, height, radius)))
        assertNull(centered.dragAt(100f, 100f, CropDragTarget(width, height, radius)))
    }

    @Test
    fun overlappingCornersPreferTheNearest() {
        val drag = centered.dragAt(265f, 500f, CropDragTarget(1_000f, 1_000f, 300f))
        assertTrue(drag is CropDrag.Corner)
    }

    @Test
    fun enlargedCornerTargetStillGrabsTheCornerNearTheFrame() {
        val drag = centered.dragAt(305f, 250f, CropDragTarget(1_000f, 1_000f, 64f))
        assertEquals(CropDrag.Corner(CropHandle.TOP_LEFT), drag)
    }

    @Test
    fun topCornersHaveExtraForgivenessForThumbsBelowTheFrame() {
        val drag = centered.dragAt(
            x = 325f,
            y = 320f,
            target = CropDragTarget(
                imageWidth = 1_000f,
                imageHeight = 1_000f,
                touchRadius = 64f,
                topTouchRadius = 88f,
            ),
        )
        assertEquals(CropDrag.Corner(CropHandle.TOP_LEFT), drag)
    }
}
