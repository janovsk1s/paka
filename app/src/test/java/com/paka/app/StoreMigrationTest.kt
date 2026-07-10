package com.paka.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Cipher

/** Legacy on-device stores must migrate to the current authenticated formats on first load. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        TestKeys.install()
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
    }

    @Test
    fun legacyPlaintextCardsMigrateToEncryptedStore() {
        val legacy = File(context.filesDir, "cards.json")
        legacy.parentFile?.mkdirs()
        legacy.writeText(
            """[{"name":"Billa","data":"9120012345678","format":"EAN13"}]""",
        )

        val loaded = CardStore.load(context)

        assertTrue(loaded.writable)
        assertEquals(1, loaded.value.size)
        assertEquals("Billa", loaded.value.single().name)
        assertEquals(
            PassContent.Barcode(PakaFormat.EAN13, "9120012345678"),
            loaded.value.single().content,
        )
        assertTrue("migration should be reported", loaded.warning.orEmpty().contains("migrated"))
        assertTrue("encrypted store must exist", File(context.filesDir, "cards.enc").exists())
        assertFalse("plaintext must be erased", legacy.exists())

        val reloaded = CardStore.load(context)
        assertEquals(loaded.value, reloaded.value)
        assertNull(reloaded.warning)
    }

    @Test
    fun corruptEncryptedCardsRecoverFromBackup() {
        val cards = listOf(Card(name = "Konzertkarte", data = "PAKA-1", format = PakaFormat.QR))
        assertTrue(CardStore.save(context, cards).isSuccess)
        val store = File(context.filesDir, "cards.enc")
        store.copyTo(AtomicStore.backupFile(store), overwrite = true)
        store.writeBytes(ByteArray(64) { 0x5A })

        val loaded = CardStore.load(context)

        assertTrue(loaded.writable)
        assertEquals(cards, loaded.value)
        assertTrue("recovery should be reported", loaded.warning.orEmpty().contains("recovered"))
    }

    @Test
    fun backupOnlyEncryptedCardsRecoverAsWritable() {
        val cards = listOf(Card(name = "Konzertkarte", data = "PAKA-1", format = PakaFormat.QR))
        assertTrue(CardStore.save(context, cards).isSuccess)
        val store = CardStore.storageFile(context)
        store.copyTo(AtomicStore.backupFile(store), overwrite = true)
        assertTrue(store.delete())

        val loaded = CardStore.load(context)

        assertTrue(loaded.writable)
        assertEquals(cards, loaded.value)
        assertTrue(store.exists())
        assertTrue(loaded.warning.orEmpty().contains("recovered"))
    }

    @Test
    fun backupOnly2faStoreRecoversAsWritable() {
        val accounts = listOf(OtpAccount(issuer = "GitHub", account = "adrians", secret = "JBSWY3DPEHPK3PXP"))
        assertTrue(SecureStore.saveAccounts(context, accounts).isSuccess)
        val store = SecureStore.storageFile(context)
        store.copyTo(AtomicStore.backupFile(store), overwrite = true)
        assertTrue(store.delete())

        val loaded = SecureStore.loadAccounts(context)

        assertTrue(loaded.writable)
        assertEquals(accounts, loaded.value)
        assertTrue(store.exists())
        assertTrue(loaded.warning.orEmpty().contains("recovered"))
    }

    @Test
    fun plaintextCorruptEvidenceIsErasedAfterMigration() {
        val legacy = File(context.filesDir, "cards.json")
        legacy.writeText("broken plaintext")
        AtomicStore.backupFile(legacy).writeText(
            """[{"name":"Billa","data":"9120012345678","format":"EAN13"}]""",
        )

        val loaded = CardStore.load(context)

        assertTrue(loaded.writable)
        assertFalse(legacy.exists())
        assertFalse(AtomicStore.backupFile(legacy).exists())
        assertFalse(AtomicStore.corruptFile(legacy).exists())
    }

    @Test
    fun preVersioned2faStoreIsReencryptedOnFirstLoad() {
        val account = OtpAccount(issuer = "GitHub", account = "adrians", secret = "JBSWY3DPEHPK3PXP")
        val legacyJson = """[{"issuer":"GitHub","account":"adrians","secret":"JBSWY3DPEHPK3PXP"}]"""
        // Legacy layout: [12-byte IV][GCM ciphertext] with no magic and no AAD.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, TestKeys.key("paka_otp_key"))
        val blob = cipher.iv + cipher.doFinal(legacyJson.toByteArray(Charsets.UTF_8))
        File(context.filesDir, "otp.enc").apply { parentFile?.mkdirs() }.writeBytes(blob)

        val loaded = SecureStore.loadAccounts(context)

        assertEquals(listOf(account.issuer), loaded.value.map { it.issuer })
        assertEquals(listOf(account.secret), loaded.value.map { it.secret })
        val rewritten = File(context.filesDir, "otp.enc").readBytes()
        val magic = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'A'.code.toByte(), 1)
        assertTrue(
            "store must be rewritten in the versioned layout immediately",
            rewritten.copyOfRange(0, 4).contentEquals(magic),
        )
        assertEquals(loaded.value, SecureStore.loadAccounts(context).value)
    }
}
