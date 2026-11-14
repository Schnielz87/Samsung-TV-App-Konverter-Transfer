package de.tvtransfer.sign

import de.tvtransfer.net.Http
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Properties

/** Zugangsdaten aus der offiziellen Samsung-Anmeldung (kein Passwort – nur ein kurzlebiges Token). */
data class SamsungAccess(val accessToken: String, val userId: String, val email: String)

/**
 * Holt Entwickler-Zertifikate bei der Samsung-Zertifizierungsstelle für Fernseher ("VD").
 * Ablauf wie in der offiziellen Tizen-Studio-Erweiterung "Samsung Certificate Extension".
 */
object SamsungCertificates {

    const val LOGIN_PORT = 4794
    const val LOGIN_URL =
        "https://account.samsung.com/mobile/account/check.do?serviceID=v285zxnl3h&actionID=StartOAuth2&accessToken=Y" +
            "&redirect_uri=http://localhost:$LOGIN_PORT/signin/callback"

    private const val CA_BASE = "https://svdca.samsungqbe.com/apis/v3"

    /**
     * Berechtigungsstufe des Verteilerzertifikats. "Public" ist die Stufe für einzelne Entwickler und reicht für alle
     * Rechte, die umgewandelte Apps nutzen (Internet, Fernbedienung, Netzwerk). Höhere Stufen nur nach rechtlicher Prüfung
     * (LEGAL_REVIEW_REQUIRED, siehe docs/RECHTLICHE_LEITPLANKEN.md).
     */
    const val PRIVILEGE = "Public"

    fun caCert(name: String): X509Certificate {
        val stream = SamsungCertificates::class.java.getResourceAsStream("/de/tvtransfer/ca/$name")
            ?: error("Samsung-Stammzertifikat $name fehlt im Programm.")
        return stream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    }

    /**
     * Ergänzt das passende Samsung-Zwischenzertifikat anhand des Ausstellers des eigenen Zertifikats
     * (Autor / Verteiler Public / Verteiler Partner). Ein falsches Zwischenzertifikat lässt der Fernseher nicht zu.
     */
    fun completeChain(id: SigningIdentity): SigningIdentity {
        val leaf = id.chain.firstOrNull() ?: return id
        // Samsung hat die Zwischenstellen erneuert (gleicher Name, neuer Schlüssel) → nur die nehmen, die das Zertifikat
        // nachweislich unterschrieben hat. *_2075 = Stand Samsung Certificate Extension 2.0.75.
        val ca = BUNDLED_CAS.map { caCert(it) }
            .filter { it.subjectX500Principal == leaf.issuerX500Principal }
            .firstOrNull { c -> runCatching { leaf.verify(c.publicKey) }.isSuccess }
            ?: return id
        return SigningIdentity(id.privateKey, listOf(leaf, ca))
    }

    private val BUNDLED_CAS = listOf(
        "vd_tizen_dev_author_ca2.cer", "vd_tizen_dev_author_ca.cer",
        "vd_tizen_dev_public2_2075.crt", "vd_tizen_dev_public2.crt",
        "vd_tizen_dev_partner2_2075.crt", "vd_tizen_dev_partner2.crt",
    )

    /** true, wenn das beigelegte Zwischenzertifikat das eigene Zertifikat wirklich unterschrieben hat. */
    fun chainValid(id: SigningIdentity): Boolean =
        id.chain.size >= 2 && runCatching { id.chain[0].verify(id.chain[1].publicKey) }.isSuccess

    /** Kurzname eines Zertifikats für das Protokoll (CN). */
    fun cn(c: X509Certificate, issuer: Boolean = false): String =
        (if (issuer) c.issuerX500Principal else c.subjectX500Principal).name.split(',').map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: "?"

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    fun requestAuthor(access: SamsungAccess, keyPair: KeyPair, name: String): SigningIdentity {
        val csr = Csr.author(keyPair, name)
        val cert = post("$CA_BASE/authors", access, csr, "author.csr", extra = emptyList())
        return completeChain(SigningIdentity(keyPair.private, listOf(cert, caCert("vd_tizen_dev_author_ca.cer"))))
    }

    /** Verteilerzertifikat + von Samsung unterschriebenes Geräteprofil (device-profile.xml, enthält die DUIDs). */
    class Distributor(val identity: SigningIdentity, val deviceProfile: ByteArray)

    /**
     * Wie Tizen Studio im TV-Modus: zwei Anfragen mit derselben CSR –
     * v1 liefert das Geräteprofil (device-profile.xml), v3 das Zertifikat.
     */
    fun requestDistributor(access: SamsungAccess, keyPair: KeyPair, duids: List<String>): Distributor {
        val csr = Csr.distributor(keyPair, access.email, duids)
        val extra = listOf(Http.Part("privilege_level", PRIVILEGE), Http.Part("developer_type", "Individual"))
        val profile = postRaw("https://svdca.samsungqbe.com/apis/v1/distributors", access, csr, "distributor.csr", extra)
        if (!String(profile, Charsets.UTF_8).contains("TestDevice"))
            throw IllegalStateException("Samsung hat kein gültiges Geräteprofil geliefert: ${String(profile, Charsets.UTF_8).take(200)}")
        val cert = post("$CA_BASE/distributors", access, csr, "distributor.csr", extra)
        val ca = if (PRIVILEGE == "Partner") "vd_tizen_dev_partner2_2075.crt" else "vd_tizen_dev_public2_2075.crt"
        return Distributor(completeChain(SigningIdentity(keyPair.private, listOf(cert, caCert(ca)))), profile)
    }

    /** DUIDs aus einem Geräteprofil. */
    fun profileDuids(profile: ByteArray): List<String> =
        Regex("<TestDevice>\\s*([^<\\s]+)\\s*</TestDevice>").findAll(String(profile, Charsets.UTF_8)).map { it.groupValues[1] }.toList()

    private fun postRaw(url: String, access: SamsungAccess, csr: String, fileName: String, extra: List<Http.Part>): ByteArray {
        val parts = listOf(
            Http.Part("access_token", access.accessToken),
            Http.Part("user_id", access.userId),
            Http.Part("platform", "VD"),
        ) + extra + Http.Part("csr", csr.toByteArray(Charsets.US_ASCII), fileName, "application/octet-stream")
        val r = Http.postMultipart(url, parts)
        if (!r.ok) {
            val hint = if (r.code == 401 || r.code == 403) " Die Samsung-Anmeldung ist abgelaufen – bitte erneut anmelden." else ""
            throw IllegalStateException("Samsung hat die Anfrage verweigert (HTTP ${r.code}).$hint ${r.text.take(300)}")
        }
        return r.body
    }

    private fun post(url: String, access: SamsungAccess, csr: String, fileName: String, extra: List<Http.Part>): X509Certificate {
        val body = postRaw(url, access, csr, fileName, extra)
        return parseCertificate(body)
            ?: throw IllegalStateException("Unerwartete Antwort der Samsung-Zertifizierungsstelle: ${String(body, Charsets.UTF_8).take(300)}")
    }

    fun parseCertificate(body: ByteArray): X509Certificate? {
        val text = String(body, Charsets.UTF_8)
        val pem = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").find(text)?.value
        val bytes = pem?.toByteArray(Charsets.US_ASCII) ?: body
        return runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }.getOrNull()
    }
}

/**
 * Speichert Autor- und Verteilerzertifikat dauerhaft im Programmordner (PKCS#12).
 * Die Schlüssel verlassen das Gerät nie; Samsung erhält nur die Zertifikatsanfragen.
 */
class CertStore(private val dir: File) {

    private val propsFile = File(dir, "zertifikat.properties")
    private val authorFile = File(dir, "author.p12")
    private val distributorFile = File(dir, "distributor.p12")
    private val profileFile = File(dir, "device-profile.xml")

    private val props = Properties().apply { if (propsFile.exists()) propsFile.inputStream().use { load(it) } }

    val hasCertificate: Boolean get() = authorFile.exists() && distributorFile.exists()
    val email: String? get() = props.getProperty("email")
    val duids: List<String> get() = props.getProperty("duids").orEmpty().split(',').filter { it.isNotBlank() }
    val createdAt: Long get() = props.getProperty("created")?.toLongOrNull() ?: 0L

    /** Zertifikat und Samsung-Geräteprofil vorhanden und dieser Fernseher ist enthalten. */
    fun covers(duid: String): Boolean =
        hasCertificate && duid in duids && profileFile.exists() && duid in SamsungCertificates.profileDuids(profileFile.readBytes())

    /** Von Samsung unterschriebenes Geräteprofil (muss vor jeder Installation auf den Fernseher). */
    fun deviceProfile(): ByteArray? = profileFile.takeIf { it.exists() }?.readBytes()

    fun author(): SigningIdentity = SamsungCertificates.completeChain(load(authorFile))
    fun distributor(): SigningIdentity = SamsungCertificates.completeChain(load(distributorFile))

    /**
     * Erstellt (oder erweitert) das Zertifikat so, dass alle bisherigen Fernseher plus [duid] enthalten sind.
     * Der Autorenschlüssel bleibt erhalten, damit Updates bereits installierter Apps weiterhin passen.
     */
    fun create(access: SamsungAccess, duid: String, authorName: String, log: (String) -> Unit) {
        dir.mkdirs()
        val allDuids = (duids + duid).distinct()
        val author = if (authorFile.exists()) {
            log("Vorhandenes Autorenzertifikat wird weiterverwendet.")
            author()
        } else {
            log("Fordere Autorenzertifikat bei Samsung an …")
            SamsungCertificates.requestAuthor(access, SamsungCertificates.newKeyPair(), authorName)
        }
        log("Fordere Verteilerzertifikat für ${allDuids.size} Fernseher bei Samsung an …")
        val distributor = SamsungCertificates.requestDistributor(access, SamsungCertificates.newKeyPair(), allDuids)
        save(authorFile, author)
        save(distributorFile, distributor.identity)
        profileFile.writeBytes(distributor.deviceProfile)
        log("Samsung-Geräteprofil erhalten (Fernseher: ${SamsungCertificates.profileDuids(distributor.deviceProfile).joinToString()}).")
        props.setProperty("email", access.email)
        props.setProperty("duids", allDuids.joinToString(","))
        props.setProperty("created", System.currentTimeMillis().toString())
        propsFile.outputStream().use { props.store(it, "TV-App-Transfer Zertifikat") }
        log("Zertifikat gespeichert.")
    }

    fun delete() {
        authorFile.delete(); distributorFile.delete(); propsFile.delete(); profileFile.delete()
        props.clear()
    }

    private fun password(): CharArray {
        var pw = props.getProperty("pw")
        if (pw == null) {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            pw = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            props.setProperty("pw", pw)
        }
        return pw!!.toCharArray()
    }

    private fun save(file: File, id: SigningIdentity) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        val pw = password()
        ks.setKeyEntry("key", id.privateKey, pw, id.chain.toTypedArray())
        file.outputStream().use { ks.store(it, pw) }
    }

    private fun load(file: File): SigningIdentity {
        val ks = KeyStore.getInstance("PKCS12")
        val pw = password()
        file.inputStream().use { ks.load(it, pw) }
        val key = ks.getKey("key", pw) as PrivateKey
        val chain = ks.getCertificateChain("key").map { it as X509Certificate }
        return SigningIdentity(key, chain)
    }
}
