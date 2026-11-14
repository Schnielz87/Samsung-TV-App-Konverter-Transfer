package de.tvtransfer.bridge

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Schlüssel, mit dem sich dieses Programm bei Android-Geräten (adb) ausweist.
 * Beim ersten Verbinden fragt der Stick einmal "USB-Debugging zulassen?" – danach nie wieder.
 */
class AdbKey(private val keyPair: KeyPair, private val name: String) {

    private val publicKey get() = keyPair.public as RSAPublicKey
    private val privateKey: PrivateKey get() = keyPair.private

    /** adb signiert das 20-Byte-Token so, als wäre es ein SHA-1-Hashwert (PKCS#1 v1.5). */
    fun signToken(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(privateKey)
        sig.update(SHA1_DIGEST_INFO)
        sig.update(token)
        return sig.sign()
    }

    /** Öffentlicher Schlüssel im Android-eigenen Format (Base64 + Name + Nullbyte). */
    fun publicKeyForDevice(): ByteArray {
        val words = 64 // 2048 Bit / 32
        val n = publicKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(64 * words).mod(n) // R² mod n, R = 2^2048
        val buf = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        putWords(buf, n, words)
        putWords(buf, rr, words)
        buf.putInt(publicKey.publicExponent.toInt())
        val text = Base64.getEncoder().encodeToString(buf.array()) + " " + name + "\u0000"
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun putWords(buf: ByteBuffer, value: BigInteger, words: Int) {
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        var v = value
        repeat(words) {
            buf.putInt(v.and(mask).toLong().toInt())
            v = v.shiftRight(32)
        }
    }

    companion object {
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** Lädt den Schlüssel aus [file] oder legt einen neuen an. */
        fun loadOrCreate(file: File, name: String): AdbKey {
            val kf = KeyFactory.getInstance("RSA")
            if (file.exists()) {
                runCatching {
                    val der = Base64.getDecoder().decode(file.readText().trim())
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(der)) as java.security.interfaces.RSAPrivateCrtKey
                    val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
                    return AdbKey(KeyPair(pub, priv), name)
                }
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val kp = gen.generateKeyPair()
            file.parentFile?.mkdirs()
            file.writeText(Base64.getEncoder().encodeToString(kp.private.encoded))
            return AdbKey(kp, name)
        }

        fun generate(name: String): AdbKey {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            return AdbKey(gen.generateKeyPair(), name)
        }
    }
}
