package de.tvtransfer

import de.tvtransfer.convert.Zip
import de.tvtransfer.sign.Csr
import de.tvtransfer.sign.SigningIdentity
import de.tvtransfer.sign.WidgetSigner
import java.io.ByteArrayInputStream
import java.security.Key
import java.security.Signature
import java.util.Base64
import javax.xml.crypto.AlgorithmMethod
import javax.xml.crypto.Data
import javax.xml.crypto.KeySelector
import javax.xml.crypto.KeySelectorResult
import javax.xml.crypto.OctetStreamData
import javax.xml.crypto.URIDereferencer
import javax.xml.crypto.URIReference
import javax.xml.crypto.XMLCryptoContext
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.keyinfo.KeyInfo
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignerTest {

    @Test
    fun propDigestsMatchKnownTizenValues() {
        // Diese Werte sind bei allen Tizen-Signaturen gleich (bekannt aus Tizen Studio / tizen.js)
        assertEquals(
            "aXbSAVgmAz0GsBUeZ1UmNDRrxkWhDUVGb45dZcNRq429wX3X+x6kaXT3NdNDTSNVTU+ypkysPMGvQY10fG1EWQ==",
            WidgetSigner.propDigest("AuthorSignature"),
        )
        assertEquals(
            "/r5npk2VVA46QFJnejgONBEh4BWtjrtu9x/IFeLksjWyGmB/cMWKSJWQl7aU3YRQRZ3AesG8gF7qGyvKX9Snig==",
            WidgetSigner.propDigest("DistributorSignature"),
        )
    }

    @Test
    fun signatureValidatesWithStandardXmlDsig() {
        val (akp, acert) = TestCerts.create("Autor")
        val (dkp, dcert) = TestCerts.create("Verteiler")
        val pkg = Zip.write(
            linkedMapOf(
                "config.xml" to "<widget/>".toByteArray(),
                "index.html" to "<html></html>".toByteArray(),
                "js/app mit leerzeichen.js" to "var a=1;".toByteArray(),
                "author-signature.xml" to "alt".toByteArray(),
            ),
        )
        val signed = Zip.read(WidgetSigner.sign(pkg, SigningIdentity(akp.private, listOf(acert)), SigningIdentity(dkp.private, listOf(dcert))))
        assertEquals(listOf("signature1.xml", "author-signature.xml", "config.xml", "index.html", "js/app mit leerzeichen.js"), signed.keys.toList())

        for ((sigFile, key) in listOf("author-signature.xml" to acert.publicKey, "signature1.xml" to dcert.publicKey)) {
            val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(signed[sigFile]!!))
            val sigEl = doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature").item(0)
            val ctx = DOMValidateContext(fixedKey(key), sigEl)
            ctx.uriDereferencer = PackageDereferencer(signed, XMLSignatureFactory.getInstance("DOM").uriDereferencer)
            val objEl = doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Object").item(0) as org.w3c.dom.Element
            ctx.setIdAttributeNS(objEl, null, "Id")
            ctx.setProperty("org.jcp.xml.dsig.secureValidation", false)
            val sig = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx)
            val ok = sig.validate(ctx)
            val refs = sig.signedInfo.references.map { (it as javax.xml.crypto.dsig.Reference).uri + "=" + it.validate(ctx) }
            assertTrue(sig.signatureValue.validate(ctx), "$sigFile Signaturwert ungültig")
            assertTrue(ok, "$sigFile ungültig: $refs")
        }
    }

    private fun fixedKey(k: Key) = object : KeySelector() {
        override fun select(keyInfo: KeyInfo?, purpose: Purpose?, method: AlgorithmMethod?, context: XMLCryptoContext?) =
            KeySelectorResult { k }
    }

    private class PackageDereferencer(val files: Map<String, ByteArray>, val fallback: URIDereferencer) : URIDereferencer {
        override fun dereference(ref: URIReference, ctx: XMLCryptoContext): Data {
            val uri = ref.uri
            if (uri.startsWith("#")) return fallback.dereference(ref, ctx)
            val name = java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8")
            return OctetStreamData(ByteArrayInputStream(files[name] ?: error("fehlt: $name")))
        }
    }

    @Test
    fun csrIsSelfConsistent() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = Csr.distributor(kp, "test@example.org", listOf("ABCDEF123456"))
        val der = Base64.getMimeDecoder().decode(pem.lines().filter { !it.startsWith("-----") }.joinToString(""))
        // Aufbau: SEQ { info SEQ, algo SEQ, BIT STRING }
        val (outerStart, _) = header(der, 0)
        val (infoStart, infoLen) = header(der, outerStart)
        val infoEnd = infoStart + infoLen
        val info = der.copyOfRange(outerStart, infoEnd)
        val (algoStart, algoLen) = header(der, infoEnd)
        val (bitsStart, bitsLen) = header(der, algoStart + algoLen)
        val sig = der.copyOfRange(bitsStart + 1, bitsStart + bitsLen)
        val v = Signature.getInstance("SHA512withRSA").apply { initVerify(kp.public); update(info) }
        assertTrue(v.verify(sig))
        assertTrue(String(der, Charsets.ISO_8859_1).contains("URN:tizen:deviceid=ABCDEF123456"))
    }

    /** Liefert (Beginn des Inhalts, Länge) eines DER-Elements an [pos]. */
    private fun header(d: ByteArray, pos: Int): Pair<Int, Int> {
        val l = d[pos + 1].toInt() and 0xFF
        if (l < 0x80) return (pos + 2) to l
        val n = l and 0x7F
        var len = 0
        for (i in 0 until n) len = (len shl 8) or (d[pos + 2 + i].toInt() and 0xFF)
        return (pos + 2 + n) to len
    }
}
