package com.bonevane.bridge

import android.content.Context
import android.util.Base64
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine

/*
 * A minimal ADB *client* that runs inside the phone and talks to the phone's
 * own adbd on 127.0.0.1:5555 (after `adb tcpip 5555`). Adapted from Shizuku's
 * manager (https://github.com/RikkaApps/Shizuku, Apache-2.0); see NOTICE.
 *
 * The wire protocol (system/core/adb/protocol.txt) is a sequence of 24-byte
 * headers, little-endian, optionally followed by a payload:
 *
 *   command  arg0  arg1  data_length  data_checksum  magic(= ~command)
 *
 * Handshake: we send CNXN, adbd answers AUTH with a 20-byte token, we sign it
 * with our RSA key (SIGNATURE). If adbd doesn't know the key yet it asks again;
 * we then send the public key (RSAPUBLICKEY) and the phone shows the familiar
 * "Allow USB debugging?" dialog. Once accepted, adbd sends CNXN and we can
 * OPEN a `shell:<cmd>` stream.
 */
class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534C5453
        const val A_VERSION = 0x01000000
        const val A_MAXDATA = 4096
        const val A_STLS_VERSION = 0x01000000
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        const val HEADER = 24
    }

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    fun connect(timeoutMs: Int = 5000) {
        // Not `Socket().apply { … }`: inside apply, `port` would mean Socket.port (0).
        socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = 30_000
        input = DataInputStream(socket.getInputStream())
        output = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::".cstr())
        var msg = read()
        if (msg.command == A_STLS) {
            // Wireless debugging: adbd wants a TLS handshake. Our client certificate
            // carries the same RSA key the user already authorized, so adbd accepts
            // it without the pairing dance.
            write(A_STLS, A_STLS_VERSION, 0)
            val tls = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tls.startHandshake()
            input = DataInputStream(tls.inputStream)
            output = DataOutputStream(tls.outputStream)
            msg = read()
        }
        if (msg.command == A_AUTH) {
            check(msg.arg0 == AUTH_TOKEN) { "unexpected AUTH type ${msg.arg0}" }
            write(A_AUTH, AUTH_SIGNATURE, 0, key.sign(msg.data))
            msg = read()
            if (msg.command != A_CNXN) {
                // Unknown key: offer it. The user must tap "Allow" on the phone.
                write(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                msg = read()
            }
        }
        check(msg.command == A_CNXN) { "expected CNXN, got ${msg.name}" }
    }

    /** Runs `shell:<command>` and returns its combined output. */
    fun shell(command: String): String = service("shell:$command")

    /** Opens any adbd service stream (e.g. `tcpip:5555`) and returns what it wrote. */
    fun service(name: String): String {
        val localId = 1
        val out = StringBuilder()
        write(A_OPEN, localId, 0, name.cstr())
        var msg = read()
        when (msg.command) {
            A_OKAY -> while (true) {
                msg = read()
                val remoteId = msg.arg0
                when (msg.command) {
                    A_WRTE -> {
                        msg.data?.let { out.append(String(it)) }
                        write(A_OKAY, localId, remoteId)
                    }
                    A_CLSE -> { write(A_CLSE, localId, remoteId); break }
                    else -> error("expected WRTE or CLSE, got ${msg.name}")
                }
            }
            A_CLSE -> write(A_CLSE, localId, msg.arg0)
            else -> error("expected OKAY or CLSE, got ${msg.name}")
        }
        return out.toString()
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        val len = data?.size ?: 0
        val buf = ByteBuffer.allocate(HEADER + len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(checksum(data)).putInt(command.inv())
        if (data != null) buf.put(data)
        output.write(buf.array())
        output.flush()
    }

    private fun read(): Message {
        val header = ByteArray(HEADER).also { input.readFully(it) }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int; val arg0 = buf.int; val arg1 = buf.int
        val len = buf.int; val sum = buf.int; val magic = buf.int
        check(magic == command.inv()) { "bad magic" }
        val data = if (len > 0) ByteArray(len).also { input.readFully(it) } else null
        check(len == 0 || checksum(data) == sum) { "bad checksum" }
        return Message(command, arg0, arg1, data)
    }

    override fun close() { runCatching { socket.close() } }

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray?) {
        val name: String get() = when (command) {
            A_CNXN -> "CNXN"; A_AUTH -> "AUTH"; A_OPEN -> "OPEN"; A_OKAY -> "OKAY"
            A_CLSE -> "CLSE"; A_WRTE -> "WRTE"; A_STLS -> "STLS"; else -> "0x%08x".format(command)
        }
    }

    /** The checksum is just the sum of the payload bytes. */
    private fun checksum(data: ByteArray?): Int {
        var sum = 0
        data?.forEach { sum += it.toInt() and 0xff }
        return sum
    }

    private fun String.cstr() = "$this\u0000".toByteArray()
}

/**
 * The RSA-2048 key this app uses to identify itself to adbd, like ~/.android/adbkey
 * on a computer. Stored in the app's private preferences. Once the user taps
 * "Allow" (with "always allow"), adbd remembers the public key across reboots.
 */
class AdbKey private constructor(private val privateKey: RSAPrivateKey) {

    companion object {
        private const val PREF = "adbkey"
        private const val MODULUS_BYTES = 2048 / 8
        private const val MODULUS_WORDS = MODULUS_BYTES / 4

        fun load(ctx: Context): AdbKey {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.getString("pkcs8", null)?.let { b64 ->
                runCatching {
                    val spec = PKCS8EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP))
                    return AdbKey(KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey)
                }
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val priv = gen.generateKeyPair().private as RSAPrivateKey
            prefs.edit().putString("pkcs8", Base64.encodeToString(priv.encoded, Base64.NO_WRAP)).apply()
            return AdbKey(priv)
        }

        /*
         * PKCS#1 v1.5 padding for a SHA-1 DigestInfo. adbd verifies the signature
         * with RSA_verify(NID_sha1, token), so the "hash" it expects is the raw
         * 20-byte token: we pad it by hand and do a raw RSA operation.
         */
        private val PADDING: ByteArray = ByteArray(MODULUS_BYTES - 20).also {
            it[0] = 0x00; it[1] = 0x01
            for (i in 2 until it.size - 16) it[i] = 0xff.toByte()
            it[it.size - 16] = 0x00
            byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
                .copyInto(it, it.size - 15)
        }
    }

    private val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

    /** TLS 1.3 context presenting our self-signed cert; adbd's cert is not verified (it's our own phone, over loopback). */
    val sslContext: SSLContext by lazy {
        val cert = SelfSignedCert.create(publicKey, privateKey)
        val km = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?) = "key"
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = privateKey
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("key")
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?) = null
        }
        val tm = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: java.net.Socket?) {}
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: java.net.Socket?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(km), arrayOf(tm), SecureRandom()) }
    }

    fun sign(token: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(token)
    }

    /**
     * adbd's public-key format (libcrypto_utils/android_pubkey.c): a struct with
     * precomputed Montgomery values, base64-encoded, followed by " name\0".
     */
    val adbPublicKey: ByteArray by lazy {
        val n = publicKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate()
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(BigInteger.TWO, n)
        val buf = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MODULUS_WORDS).putInt(n0inv.toInt())
        n.toLittleEndianWords().forEach { buf.putInt(it) }
        rr.toLittleEndianWords().forEach { buf.putInt(it) }
        buf.putInt(publicKey.publicExponent.toInt())
        Base64.encode(buf.array(), Base64.NO_WRAP) + " bridge@phone\u0000".toByteArray()
    }

    private fun BigInteger.toLittleEndianWords(): IntArray {
        val words = IntArray(MODULUS_WORDS)
        val r32 = BigInteger.ONE.shiftLeft(32)
        var v = this
        for (i in words.indices) { words[i] = v.mod(r32).toInt(); v = v.shiftRight(32) }
        return words
    }
}
