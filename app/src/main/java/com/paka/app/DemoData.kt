package com.paka.app

import java.security.SecureRandom

internal data class DemoContent(
    val cards: List<Card>,
    val accounts: List<OtpAccount>,
)

/** Fresh synthetic content that is never written to Paka's encrypted stores. */
internal object DemoData {
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val random = SecureRandom()

    fun create(): DemoContent {
        val session = randomText(12)
        return DemoContent(
            cards = listOf(
                Card("Museum", "https://example.invalid/paka/museum/$session", PakaFormat.QR),
                Card("Library", "LIB-${randomText(14)}", PakaFormat.CODE128),
                Card("Day ticket", "DEMO|DAY|${randomText(20)}", PakaFormat.AZTEC, stack = "Transit"),
                Card("Night ticket", "DEMO|NIGHT|${randomText(20)}", PakaFormat.AZTEC, stack = "Transit"),
                Card("Event", "EVENT-${randomText(18)}", PakaFormat.PDF417),
                Card("Rewards", "https://example.invalid/paka/rewards/${randomText(10)}", PakaFormat.QR),
                Card("Parcel", "PK${randomText(16)}", PakaFormat.CODE39),
            ),
            accounts = listOf(
                demoAccount("Northstar", "demo@example.invalid"),
                demoAccount("Workshop", "guest@example.invalid"),
                demoAccount("Archive", "sample@example.invalid"),
                demoAccount("Studio", "visitor@example.invalid"),
                demoAccount("Cloud", "demo@example.invalid"),
                demoAccount("Forum", "sample@example.invalid"),
            ),
        )
    }

    private fun demoAccount(issuer: String, account: String) = OtpAccount(
        issuer = issuer,
        account = account,
        secret = randomBase32(20),
    )

    private fun randomText(length: Int): String = buildString(length) {
        repeat(length) { append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]) }
    }

    private fun randomBase32(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also(random::nextBytes)
        var buffer = 0
        var bits = 0
        return buildString((byteCount * 8 + 4) / 5) {
            bytes.forEach { byte ->
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    append(BASE32[(buffer shr bits) and 31])
                }
            }
            if (bits > 0) append(BASE32[(buffer shl (5 - bits)) and 31])
        }
    }
}
