package de.tvtransfer.sign

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.Signature
import java.util.Base64

/** Minimaler DER-Kodierer – genug für eine Zertifikatsanfrage (PKCS#10). */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg items: ByteArray) = tlv(0x30, concat(*items))
    fun set(vararg items: ByteArray) = tlv(0x31, concat(*items))
    fun int(v: Int) = tlv(0x02, byteArrayOf(v.toByte()))
    fun nul() = byteArrayOf(0x05, 0x00)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun ia5(s: String) = tlv(0x16, s.toByteArray(Charsets.US_ASCII))
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun bits(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) { stack.add(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
            for (b in stack.reversed()) out.write(b)
        }
        return tlv(0x06, out.toByteArray())
    }

    fun concat(vararg items: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return out.toByteArray()
    }
}

/** Erzeugt Zertifikatsanfragen wie die offizielle Samsung-Zertifikatsverwaltung. */
object Csr {
    private const val OID_CN = "2.5.4.3"
    private const val OID_EMAIL = "1.2.840.113549.1.9.1"
    private const val OID_EXT_REQUEST = "1.2.840.113549.1.9.14"
    private const val OID_SAN = "2.5.29.17"
    private const val OID_SHA512_RSA = "1.2.840.113549.1.1.13"

    /** Anfrage für das Autorenzertifikat: nur ein Name. */
    fun author(keyPair: KeyPair, name: String): String =
        build(keyPair, listOf(rdn(OID_CN, Der.utf8(name))), emptyList())

    /** Anfrage für das Verteilerzertifikat: enthält die Geräte-IDs (DUID) der Fernseher. */
    fun distributor(keyPair: KeyPair, email: String, duids: List<String>): String {
        val names = buildList {
            add("URN:tizen:packageid=")
            duids.forEach { add("URN:tizen:deviceid=$it") }
        }
        return build(keyPair, listOf(rdn(OID_CN, Der.utf8("TizenSDK")), rdn(OID_EMAIL, Der.ia5(email))), names)
    }

    private fun rdn(oid: String, value: ByteArray) = Der.set(Der.seq(Der.oid(oid), value))

    private fun build(keyPair: KeyPair, subject: List<ByteArray>, sanUris: List<String>): String {
        val name = Der.seq(*subject.toTypedArray())
        val attributes = if (sanUris.isEmpty()) {
            Der.tlv(0xA0, ByteArray(0))
        } else {
            val generalNames = Der.seq(*sanUris.map { Der.tlv(0x86, it.toByteArray(Charsets.US_ASCII)) }.toTypedArray())
            val ext = Der.seq(Der.oid(OID_SAN), Der.octets(generalNames))
            val extensions = Der.seq(ext)
            Der.tlv(0xA0, Der.seq(Der.oid(OID_EXT_REQUEST), Der.set(extensions)))
        }
        val info = Der.seq(Der.int(0), name, keyPair.public.encoded, attributes)
        val sig = Signature.getInstance("SHA512withRSA").run {
            initSign(keyPair.private)
            update(info)
            sign()
        }
        val csr = Der.seq(info, Der.seq(Der.oid(OID_SHA512_RSA), Der.nul()), Der.bits(sig))
        return pem("CERTIFICATE REQUEST", csr)
    }

    fun pem(type: String, der: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }
}
