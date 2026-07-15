package com.paka.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpOtpauthParseTest {
    @Test
    fun parsesIssuerAndAccountFromLabel() {
        val account = Totp.parseOtpauth("otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP")

        assertEquals("Example", account?.issuer)
        assertEquals("alice@example.com", account?.account)
        assertEquals("JBSWY3DPEHPK3PXP", account?.secret)
    }

    @Test
    fun issuerParameterWinsOverLabelPrefix() {
        val account = Totp.parseOtpauth(
            "otpauth://totp/Label:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Provider",
        )

        assertEquals("Provider", account?.issuer)
        assertEquals("alice@example.com", account?.account)
    }

    @Test
    fun decodesPercentEncodedLabelExactlyOnce() {
        // The label encodes the literal issuer "100%25 off". A second decode
        // pass would corrupt it to "100% off".
        val account = Totp.parseOtpauth(
            "otpauth://totp/100%2525%20off:alice@example.com?secret=JBSWY3DPEHPK3PXP",
        )

        assertEquals("100%25 off", account?.issuer)
        assertEquals("alice@example.com", account?.account)
    }

    @Test
    fun readsDigitsPeriodAndAlgorithmParameters() {
        val account = Totp.parseOtpauth(
            "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=sha256",
        )

        assertEquals(8, account?.digits)
        assertEquals(60, account?.period)
        assertEquals("SHA256", account?.algorithm)
    }

    @Test
    fun rejectsUnsupportedUris() {
        assertNull(Totp.parseOtpauth("otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP&counter=1"))
        assertNull(Totp.parseOtpauth("otpauth://totp/Example:alice"))
        assertNull(Totp.parseOtpauth("https://example.com/totp?secret=JBSWY3DPEHPK3PXP"))
    }
}
