package de.tvtransfer.sign

import de.tvtransfer.convert.Zip
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64

/** Privater Schlüssel + Zertifikatskette (eigenes Zertifikat zuerst, dann Samsung-Zwischenzertifikat). */
class SigningIdentity(val privateKey: PrivateKey, val chain: List<X509Certificate>)

/**
 * Signiert Tizen-Pakete (.wgt/.tpk) nach "XML Digital Signatures for Widgets":
 *  - author-signature.xml   (Autor: alle Dateien)
 *  - signature1.xml         (Verteiler: alle Dateien + author-signature.xml; enthält die Geräte-IDs)
 * Verfahren: SHA-512-Prüfsummen, RSA-SHA512, SignedInfo in Exclusive-C14N.
 */
object WidgetSigner {

    private const val NS = "http://www.w3.org/2000/09/xmldsig#"

    fun sign(pkg: ByteArray, author: SigningIdentity, distributor: SigningIdentity): ByteArray {
        val all = Zip.read(pkg)
        val files = all.filterKeys { !isSignatureFile(it) }
        val authorXml = signatureXml("AuthorSignature", files.entries.map { it.key to it.value }, author)
        val distFiles = listOf("author-signature.xml" to authorXml) + files.entries.map { it.key to it.value }
        val distXml = signatureXml("DistributorSignature", distFiles, distributor)
        val out = LinkedHashMap<String, ByteArray>()
        out["signature1.xml"] = distXml
        out["author-signature.xml"] = authorXml
        out.putAll(files)
        return Zip.write(out)
    }

    fun isSignatureFile(name: String): Boolean =
        name == "author-signature.xml" || Regex("^signature\\d+\\.xml$").matches(name)

    /** Kanonische Form des Objekts "#prop" (C14N 1.1, mit geerbtem Standard-Namensraum). */
    fun propCanonical(id: String): String = "<Object xmlns=\"$NS\" Id=\"prop\">" + propInner(id) + "</Object>"

    private fun propInner(id: String): String {
        val role = if (id == "AuthorSignature") "author" else "distributor"
        return "<SignatureProperties xmlns:dsp=\"http://www.w3.org/2009/xmldsig-properties\">" +
            "<SignatureProperty Id=\"profile\" Target=\"#$id\"><dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"></dsp:Profile></SignatureProperty>" +
            "<SignatureProperty Id=\"role\" Target=\"#$id\"><dsp:Role URI=\"http://www.w3.org/ns/widgets-digsig#role-$role\"></dsp:Role></SignatureProperty>" +
            "<SignatureProperty Id=\"identifier\" Target=\"#$id\"><dsp:Identifier></dsp:Identifier></SignatureProperty>" +
            "</SignatureProperties>"
    }

    fun propDigest(id: String): String = b64(sha512(propCanonical(id).toByteArray(Charsets.UTF_8)))

    fun signatureXml(id: String, files: List<Pair<String, ByteArray>>, identity: SigningIdentity): ByteArray {
        val refs = StringBuilder()
        for ((name, data) in files) refs.append(reference(uri(name), b64(sha512(data)), transform = false))
        refs.append(reference("#prop", propDigest(id), transform = true))

        val signedInfoBody =
            "\n<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"></CanonicalizationMethod>\n" +
                "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha512\"></SignatureMethod>\n" +
                refs
        // Exclusive C14N der SignedInfo: der genutzte Standard-Namensraum wird am Element ausgegeben.
        val canonical = "<SignedInfo xmlns=\"$NS\">$signedInfoBody</SignedInfo>"
        val sigValue = Signature.getInstance("SHA512withRSA").run {
            initSign(identity.privateKey)
            update(canonical.toByteArray(Charsets.UTF_8))
            sign()
        }

        val sb = StringBuilder()
        sb.append("<Signature xmlns=\"$NS\" Id=\"$id\">\n")
        sb.append("<SignedInfo>").append(signedInfoBody).append("</SignedInfo>\n")
        sb.append("<SignatureValue>\n").append(wrap(b64(sigValue))).append("\n</SignatureValue>\n")
        sb.append("<KeyInfo>\n<X509Data>")
        for (c in identity.chain) sb.append("\n<X509Certificate>\n").append(wrap(b64(c.encoded))).append("\n</X509Certificate>")
        sb.append("\n</X509Data>\n</KeyInfo>\n")
        sb.append("<Object Id=\"prop\">").append(propInner(id)).append("</Object>\n")
        sb.append("</Signature>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun reference(uri: String, digest: String, transform: Boolean): String =
        "<Reference URI=\"$uri\">\n" +
            (if (transform) "<Transforms>\n<Transform Algorithm=\"http://www.w3.org/2006/12/xml-c14n11\"></Transform>\n</Transforms>\n" else "") +
            "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>\n" +
            "<DigestValue>${wrap(digest)}</DigestValue>\n" +
            "</Reference>\n"

    /** Dateinamen wie in URIs üblich kodieren; Schrägstriche bleiben erhalten. */
    fun uri(name: String): String = name.split('/').joinToString("/") { seg ->
        val sb = StringBuilder()
        for (b in seg.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") sb.append(ch)
            else sb.append('%').append(String.format("%02X", c))
        }
        sb.toString()
    }

    private fun sha512(b: ByteArray) = MessageDigest.getInstance("SHA-512").digest(b)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun wrap(s: String) = s.chunked(76).joinToString("\n")
}
