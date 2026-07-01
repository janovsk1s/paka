package com.paka.app

import android.net.Uri
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.UUID

/** A time-based one-time-password account (RFC 6238). */
data class OtpAccount(
    val issuer: String,
    val account: String,
    val secret: String,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String = "SHA1",
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
)

fun OtpAccount.title(): String = if (account.isBlank()) issuer else "$issuer · $account"

object Totp {

    /** Current TOTP code, or a row of dashes if the secret is unusable. */
    fun code(account: OtpAccount, timeMillis: Long): String {
        return try {
            val key = base32Decode(account.secret)
            if (key.isEmpty()) return "-".repeat(account.digits)
            val counter = (timeMillis / 1000L) / account.period
            hotp(key, counter, account.digits, account.algorithm)
        } catch (e: Exception) {
            "-".repeat(account.digits)
        }
    }

    /** Seconds left in the current period. */
    fun secondsRemaining(account: OtpAccount, timeMillis: Long): Int {
        val p = account.period.coerceAtLeast(1)
        return (p - ((timeMillis / 1000L) % p)).toInt()
    }

    private fun hotp(key: ByteArray, counter: Long, digits: Int, algorithm: String): String {
        val algo = when (algorithm.uppercase()) {
            "SHA256" -> "HmacSHA256"
            "SHA512" -> "HmacSHA512"
            else -> "HmacSHA1"
        }
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(key, algo))
        val data = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            data[i] = (c and 0xff).toByte()
            c = c shr 8
        }
        val hash = mac.doFinal(data)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var pow = 1
        repeat(digits.coerceIn(1, 9)) { pow *= 10 }
        return (binary % pow).toString().padStart(digits, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.trim().replace(" ", "").replace("-", "").uppercase().trimEnd('=')
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>(clean.length)
        for (ch in clean) {
            val v = alphabet.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }

    /** Parse an otpauth://totp/... URI (e.g. from a setup QR) into an account. */
    fun parseOtpauth(raw: String): OtpAccount? {
        if (!raw.startsWith("otpauth://totp", ignoreCase = true)) return null
        return try {
            val uri = Uri.parse(raw)
            val secret = uri.getQueryParameter("secret")?.replace(" ", "").orEmpty()
            if (secret.isBlank()) return null
            val label = Uri.decode(uri.path?.trimStart('/').orEmpty())
            val issuerParam = uri.getQueryParameter("issuer")
            val issuer: String
            val account: String
            if (label.contains(":")) {
                issuer = issuerParam ?: label.substringBefore(":").trim()
                account = label.substringAfter(":").trim()
            } else {
                issuer = issuerParam ?: label.trim()
                account = if (issuerParam != null) label.trim() else ""
            }
            OtpAccount(
                issuer = issuer.ifBlank { "Unknown" },
                account = account,
                secret = secret,
                digits = uri.getQueryParameter("digits")?.toIntOrNull() ?: 6,
                period = uri.getQueryParameter("period")?.toIntOrNull() ?: 30,
                algorithm = uri.getQueryParameter("algorithm")?.uppercase() ?: "SHA1",
            )
        } catch (e: Exception) {
            null
        }
    }
}
