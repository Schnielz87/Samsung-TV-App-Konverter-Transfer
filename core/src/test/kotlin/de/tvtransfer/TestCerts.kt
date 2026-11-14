package de.tvtransfer

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Erzeugt selbstsignierte Testzertifikate ohne Fremdbibliothek. */
object TestCerts {
    fun tlv(tag: Int, c: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); o.write(tag)
        val n = c.size
        when { n < 0x80 -> o.write(n); n < 0x100 -> { o.write(0x81); o.write(n) }; else -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xFF) } }
        o.write(c); return o.toByteArray()
    }
    fun seq(vararg x: ByteArray) = tlv(0x30, x.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg x: ByteArray) = tlv(0x31, x.fold(ByteArray(0)) { a, b -> a + b })
    private val sha256Rsa = byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val cnOid = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    fun create(cn: String): Pair<KeyPair, X509Certificate> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algo = seq(sha256Rsa, byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(cnOid, tlv(0x0C, cn.toByteArray()))))
        val validity = seq(tlv(0x17, "200101000000Z".toByteArray()), tlv(0x17, "400101000000Z".toByteArray()))
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, BigInteger.valueOf(System.nanoTime()).toByteArray()),
            algo, name, validity, name, kp.public.encoded,
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(tbs); sign() }
        val der = seq(tbs, algo, tlv(0x03, byteArrayOf(0) + sig))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        return kp to cert
    }
}
