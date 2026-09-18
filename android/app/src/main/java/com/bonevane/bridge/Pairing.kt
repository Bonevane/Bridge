package com.bonevane.bridge

import android.content.Context
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The checks every transport does with the pairing secret (see Prefs.pairSecret). */
object Pairing {
    /** Constant-time: a wrong secret takes as long to reject as a nearly-right one. */
    fun matches(ctx: Context, presented: String?): Boolean {
        val expected = Prefs.pairSecret(ctx).toByteArray()
        val got = presented.orEmpty().trim().toByteArray()
        return MessageDigest.isEqual(expected, got)
    }

    /** "AUTH <secret>" as the first line of a stream, or null. */
    fun authLine(ctx: Context): String = "AUTH ${Prefs.pairSecret(ctx)}"

    fun hmac(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun hmacMatches(secret: String, message: String, presented: String?): Boolean =
        MessageDigest.isEqual(hmac(secret, message).toByteArray(), presented.orEmpty().trim().toByteArray())

    fun nonce(): String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /**
     * App-layer encryption for the plain Bluetooth door (Windows). Key =
     * HMAC-SHA256(secret, phoneNonce + peerNonce). AES-256-GCM, 12-byte nonce =
     * one direction byte + an 11-byte big-endian counter, so no nonce is ever
     * reused within a session and each side's stream is distinct.
     * Message = nonce(12) + ciphertext + tag(16).
     */
    class SessionCrypto(secret: String, phoneNonce: String, peerNonce: String, private val phoneSide: Boolean) {
        private val key = javax.crypto.spec.SecretKeySpec(
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
                .doFinal((phoneNonce + peerNonce).toByteArray()),
            "AES",
        )
        private var sendCounter = 0L

        fun seal(plain: ByteArray): ByteArray {
            val nonce = ByteArray(12)
            nonce[0] = if (phoneSide) 1 else 2
            val c = sendCounter++
            for (i in 0 until 8) nonce[11 - i] = (c shr (8 * i)).toByte()
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, nonce))
            return nonce + cipher.doFinal(plain)
        }

        fun open(sealed: ByteArray): ByteArray? {
            if (sealed.size < 12 + 16) return null
            val nonce = sealed.copyOfRange(0, 12)
            if (nonce[0] != (if (phoneSide) 2 else 1).toByte()) return null   // must be the other side's stream
            return runCatching {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, nonce))
                cipher.doFinal(sealed, 12, sealed.size - 12)
            }.getOrNull()
        }
    }
}
