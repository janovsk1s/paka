package com.paka.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class UriBytesTest {

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun readsContentSmallerThanOneBuffer() {
        val content = bytes(1_000)
        assertArrayEquals(content, UriBytes.readBounded(ByteArrayInputStream(content), 10_000, "too large"))
    }

    @Test
    fun readsContentAcrossBufferGrowthBoundaries() {
        val content = bytes(300_000)
        assertArrayEquals(content, UriBytes.readBounded(ByteArrayInputStream(content), 400_000, "too large"))
    }

    @Test
    fun readsContentExactlyAtTheLimit() {
        val content = bytes(70_000)
        assertArrayEquals(content, UriBytes.readBounded(ByteArrayInputStream(content), 70_000, "too large"))
    }

    @Test
    fun rejectsContentPastTheLimit() {
        val content = bytes(70_001)
        val error = assertThrows(IllegalArgumentException::class.java) {
            UriBytes.readBounded(ByteArrayInputStream(content), 70_000, "too large")
        }
        assertEquals("too large", error.message)
    }

    @Test
    fun readsFromStreamsThatReturnShortReads() {
        val content = bytes(150_000)
        val trickle = object : InputStream() {
            private var position = 0
            override fun read(): Int = if (position < content.size) content[position++].toInt() and 0xff else -1
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (position >= content.size) return -1
                val count = minOf(length, 1_234, content.size - position)
                content.copyInto(target, offset, position, position + count)
                position += count
                return count
            }
        }
        assertArrayEquals(content, UriBytes.readBounded(trickle, 200_000, "too large"))
    }

    @Test
    fun readsEmptyContent() {
        assertArrayEquals(ByteArray(0), UriBytes.readBounded(ByteArrayInputStream(ByteArray(0)), 10, "too large"))
    }
}
