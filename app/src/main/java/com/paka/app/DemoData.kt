package com.paka.app

import android.content.Context
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

    fun create(context: Context? = null): DemoContent {
        val session = randomText(12)
        fun text(resourceId: Int, fallback: String) = context?.getString(resourceId) ?: fallback
        val transit = text(R.string.demo_transit, "Transit")
        return DemoContent(
            cards = listOf(
                Card(
                    text(R.string.demo_museum, "Museum"),
                    "https://example.invalid/paka/museum/$session",
                    PakaFormat.QR,
                ),
                Card(text(R.string.demo_library, "Library"), "LIB-${randomText(14)}", PakaFormat.CODE128),
                Card(
                    text(R.string.demo_day_ticket, "Day ticket"),
                    "DEMO|DAY|${randomText(20)}",
                    PakaFormat.AZTEC,
                    stack = transit,
                ),
                Card(
                    text(R.string.demo_night_ticket, "Night ticket"),
                    "DEMO|NIGHT|${randomText(20)}",
                    PakaFormat.AZTEC,
                    stack = transit,
                ),
                Card(text(R.string.demo_event, "Event"), "EVENT-${randomText(18)}", PakaFormat.PDF417),
                Card(
                    text(R.string.demo_rewards, "Rewards"),
                    "https://example.invalid/paka/rewards/${randomText(10)}",
                    PakaFormat.QR,
                ),
                Card(text(R.string.demo_parcel, "Parcel"), "PK${randomText(16)}", PakaFormat.CODE39),
            ),
            accounts = listOf(
                demoAccount(text(R.string.demo_northstar, "Northstar"), "demo@example.invalid"),
                demoAccount(text(R.string.demo_workshop, "Workshop"), "guest@example.invalid"),
                demoAccount(text(R.string.demo_archive, "Archive"), "sample@example.invalid"),
                demoAccount(text(R.string.demo_studio, "Studio"), "visitor@example.invalid"),
                demoAccount(text(R.string.demo_cloud, "Cloud"), "demo@example.invalid"),
                demoAccount(text(R.string.demo_forum, "Forum"), "sample@example.invalid"),
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
