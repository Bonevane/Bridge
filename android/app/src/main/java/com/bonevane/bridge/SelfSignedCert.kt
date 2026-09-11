package com.bonevane.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Builds a minimal self-signed X.509 certificate for an RSA key pair, by writing
 * the DER bytes by hand. adbd (wireless debugging) only looks at the public key
 * inside the client certificate and checks it against its authorized keys, so
 * nothing else in the certificate matters. Doing this by hand avoids pulling in
 * BouncyCastle for one certificate.
 *
 * DER in one paragraph: every value is `tag, length, content`. SEQUENCE (0x30)
 * and SET (0x31) nest other values; INTEGER 0x02, OID 0x06, NULL 0x05,
 * UTF8String 0x0c, UTCTime 0x17, GeneralizedTime 0x18, BIT STRING 0x03.
 */
object SelfSignedCert {

    fun create(publicKey: PublicKey, privateKey: PrivateKey, commonName: String = "Bridge"): X509Certificate {
        val sha256WithRsa = seq(oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0c, commonName.toByteArray()))))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),          // [0] version v3
            tlv(0x02, byteArrayOf(1)),                       // serial 1
            sha256WithRsa,
            name,                                            // issuer
            seq(                                             // validity
                tlv(0x17, "200101000000Z".toByteArray()),    // UTCTime (before 2050)
                tlv(0x18, "20991231235959Z".toByteArray())   // GeneralizedTime
            ),
            name,                                            // subject
            publicKey.encoded                                // SubjectPublicKeyInfo, already DER
        )
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update(tbs) }.sign()
        val cert = seq(tbs, sha256WithRsa, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.reduce { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.reduce { a, b -> a + b })

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }

    /** OID text → DER content bytes (first two arcs packed, then base-128 per arc). */
    private fun oid(text: String): ByteArray {
        val arcs = text.split('.').map { it.toInt() }
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (arc in arcs.drop(2)) {
            val bytes = ArrayList<Int>()
            var v = arc
            do { bytes.add(0, v and 0x7f); v = v shr 7 } while (v > 0)
            for ((i, b) in bytes.withIndex()) out.write(if (i < bytes.size - 1) b or 0x80 else b)
        }
        return tlv(0x06, out.toByteArray())
    }
}
