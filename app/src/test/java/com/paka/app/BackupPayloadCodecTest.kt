package com.paka.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupPayloadCodecTest {
    @Test
    fun `binary photo payload round trips without Base64 expansion`() {
        val metadata = "{\"schema\":3}".toByteArray()
        val document = "%PDF-1.7\nsynthetic".toByteArray()
        val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)
        val documents = linkedMapOf("a".repeat(64) to document)
        val photos = linkedMapOf("b".repeat(64) to photo)

        val encoded = BackupPayloadCodec.encode(metadata, documents, photos, 4_096)
        val decoded = BackupPayloadCodec.decode(encoded, 4_096, 1_024, 1_024, 2_048, 2_048)

        assertArrayEquals(metadata, decoded.metadata)
        assertArrayEquals(document, decoded.documents.getValue("a".repeat(64)))
        assertArrayEquals(photo, decoded.photos.getValue("b".repeat(64)))
        // The compact envelope should be close to raw size, not 4/3 Base64 plus JSON copies.
        assert(encoded.size < metadata.size + document.size + photo.size + 180)
        decoded.clear()
    }

    @Test
    fun `binary payload rejects truncation trailing data and oversized entries`() {
        val encoded = BackupPayloadCodec.encode(
            metadata = byteArrayOf(1, 2, 3),
            documents = emptyMap(),
            photos = mapOf("c".repeat(64) to ByteArray(32)),
            maxBytes = 1_024,
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupPayloadCodec.decode(encoded.copyOf(encoded.size - 1), 1_024, 64, 64, 64, 64)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupPayloadCodec.decode(encoded + 0, 1_024, 64, 64, 64, 64)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupPayloadCodec.decode(encoded, 1_024, 64, 16, 64, 64)
        }
    }

    @Test
    fun `payload selection preserves stable rollback when no photos exist`() {
        assertEquals(BackupPayloadFormat.LEGACY_SCHEMA_1, selectBackupPayloadFormat(false, false))
        assertEquals(BackupPayloadFormat.LEGACY_SCHEMA_2, selectBackupPayloadFormat(true, false))
        assertEquals(BackupPayloadFormat.BINARY_PHOTOS, selectBackupPayloadFormat(false, true))
        assertEquals(BackupPayloadFormat.BINARY_PHOTOS, selectBackupPayloadFormat(true, true))
    }
}
