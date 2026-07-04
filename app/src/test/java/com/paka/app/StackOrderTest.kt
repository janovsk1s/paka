package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Reordering inside a stack must never disturb singles or other stacks. */
class StackOrderTest {

    private fun card(id: String, stack: String? = null) =
        Card(name = id, data = "data-$id", format = PakaFormat.QR, id = id, stack = stack)

    // Global order: single, A1, single, A2, B1, A3
    private val cards = listOf(
        card("s1"),
        card("a1", "A"),
        card("s2"),
        card("a2", "A"),
        card("b1", "B"),
        card("a3", "A"),
    )

    private fun ids(list: List<Card>) = list.map { it.id }

    @Test
    fun movingUpSwapsWithThePreviousStackMemberOnly() {
        assertEquals(
            listOf("s1", "a2", "s2", "a1", "b1", "a3"),
            ids(cards.movedWithinStack("a2", up = true)),
        )
    }

    @Test
    fun movingDownSkipsCardsOfOtherStacks() {
        assertEquals(
            listOf("s1", "a1", "s2", "a3", "b1", "a2"),
            ids(cards.movedWithinStack("a2", up = false)),
        )
    }

    @Test
    fun edgesDoNotWrap() {
        assertSame(cards, cards.movedWithinStack("a1", up = true))
        assertSame(cards, cards.movedWithinStack("a3", up = false))
    }

    @Test
    fun singlesAndUnknownIdsAreUntouched() {
        assertSame(cards, cards.movedWithinStack("s1", up = false))
        assertSame(cards, cards.movedWithinStack("missing", up = true))
    }
}
