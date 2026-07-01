package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TotpTest {
    @Test
    fun `matches RFC 6238 vectors`() {
        val vectors = listOf(
            59L to listOf("94287082", "46119246", "90693936"),
            1_111_111_109L to listOf("07081804", "68084774", "25091201"),
            1_111_111_111L to listOf("14050471", "67062674", "99943326"),
            1_234_567_890L to listOf("89005924", "91819424", "93441116"),
            2_000_000_000L to listOf("69279037", "90698825", "38618901"),
            20_000_000_000L to listOf("65353130", "77737706", "47863826"),
        )
        val accounts = listOf(
            OtpAccount("RFC", "", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 8, 30, "SHA1"),
            OtpAccount("RFC", "", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA====", 8, 30, "SHA256"),
            OtpAccount("RFC", "", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA=", 8, 30, "SHA512"),
        )

        vectors.forEach { (seconds, expected) ->
            accounts.forEachIndexed { index, account ->
                assertEquals(expected[index], Totp.code(account, seconds * 1_000L))
            }
        }
    }

    @Test
    fun `rejects invalid account parameters`() {
        val valid = OtpAccount("Example", "me", "JBSWY3DPEHPK3PXP")
        assertNull(Totp.validationError(valid))
        assertNotNull(Totp.validationError(valid.copy(secret = "JBSW!3DP")))
        assertNotNull(Totp.validationError(valid.copy(period = 0)))
        assertNotNull(Totp.validationError(valid.copy(digits = 7)))
        assertNotNull(Totp.validationError(valid.copy(algorithm = "MD5")))
    }
}
