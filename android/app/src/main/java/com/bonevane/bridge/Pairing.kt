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
}
