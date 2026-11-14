package de.tvtransfer.tv

import de.tvtransfer.bridge.BridgeConnection
import de.tvtransfer.bridge.BridgeException
import de.tvtransfer.net.Http
import de.tvtransfer.net.Json
import de.tvtransfer.net.obj
import de.tvtransfer.net.str
import java.io.ByteArrayInputStream

/** Angaben, die ein Samsung-Fernseher über http://<ip>:8001/api/v2/ preisgibt (ohne Anmeldung). */
data class TvInfo(
    val host: String,
    val name: String,
    val modelName: String?,
    val model: String?,
    val developerMode: Boolean,
    val developerIp: String?,
    val os: String?,
) {
    val detectedModel: TvModel? get() = TvModel.detect(model, modelName)
}

/** Ergebnis einer Installation auf dem Fernseher. */
data class InstallResult(val success: Boolean, val message: String, val rawOutput: String)

object SamsungTv {

    const val REMOTE_DIR = "/home/owner/share/tmp/sdk_tools"

    fun fetchInfo(host: String, timeoutMs: Int = 3000): TvInfo? = try {
        val r = Http.get("http://$host:8001/api/v2/", timeoutMs)
        if (!r.ok) null else {
            val root = Json.parse(r.text)
            val dev = root.obj("device")
            TvInfo(
                host = host,
                name = root.str("name") ?: dev.str("name") ?: "Samsung TV",
                modelName = dev.str("modelName"),
                model = dev.str("model"),
                developerMode = dev.str("developerMode") == "1",
                developerIp = dev.str("developerIP"),
                os = dev.str("OS"),
            )
        }
    } catch (e: Exception) {
        null
    }

    /** Verbindet sich mit dem Fernseher (Entwicklermodus muss aktiv sein). */
    fun connect(host: String): BridgeConnection = try {
        BridgeConnection.connect(host, BridgeConnection.SDB_PORT)
    } catch (e: BridgeException.Unreachable) {
        throw BridgeException.Closed(
            "Der Fernseher lässt keine Verbindung zu. Bitte prüfen: Entwicklermodus ist an, als " +
                "\"Host PC IP\" ist die IP-Adresse DIESES Geräts eingetragen und der Fernseher wurde danach neu gestartet.",
            e,
        )
    }

    /** Geräte-ID (DUID) – wird für das Samsung-Verteilerzertifikat benötigt. */
    fun duid(conn: BridgeConnection): String {
        val out = conn.shell("0 getduid", 20_000).trim()
        val id = out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.contains(' ') }
        return id ?: throw BridgeException.Protocol("Der Fernseher hat keine Geräte-ID (DUID) geliefert: $out")
    }

    /** Liest die Paket-ID aus einer .wgt (config.xml) oder .tpk (tizen-manifest.xml). */
    fun packageId(pkg: ByteArray): String? {
        val files = de.tvtransfer.convert.Zip.read(pkg)
        files["config.xml"]?.let { xml ->
            val s = String(xml, Charsets.UTF_8)
            Regex("<tizen:application[^>]*\\spackage=\"([^\"]+)\"").find(s)?.let { return it.groupValues[1] }
        }
        files["tizen-manifest.xml"]?.let { xml ->
            val s = String(xml, Charsets.UTF_8)
            Regex("<manifest[^>]*\\spackage=\"([^\"]+)\"").find(s)?.let { return it.groupValues[1] }
        }
        return null
    }

    fun applicationId(pkg: ByteArray): String? {
        val files = de.tvtransfer.convert.Zip.read(pkg)
        files["config.xml"]?.let { xml ->
            Regex("<tizen:application[^>]*\\sid=\"([^\"]+)\"").find(String(xml, Charsets.UTF_8))?.let { return it.groupValues[1] }
        }
        files["tizen-manifest.xml"]?.let { xml ->
            Regex("<ui-application[^>]*\\sappid=\"([^\"]+)\"").find(String(xml, Charsets.UTF_8))?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * Überträgt das (bereits signierte) Paket und installiert es.
     * @param isWgt true für Web-Apps (.wgt), false für native Tizen-Pakete (.tpk)
     */
    fun install(
        conn: BridgeConnection,
        pkg: ByteArray,
        isWgt: Boolean,
        log: (String) -> Unit,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): InstallResult {
        val pkgId = packageId(pkg) ?: return InstallResult(false, "Paket-ID nicht gefunden (config.xml fehlt?).", "")
        val remote = "$REMOTE_DIR/$pkgId.${if (isWgt) "wgt" else "tpk"}"
        log("Übertrage Paket auf den Fernseher …")
        conn.push(ByteArrayInputStream(pkg), pkg.size.toLong(), remote, 0x81ED, onProgress)
        log("Installiere …")
        val out = conn.shell(
            "0 vd_appinstall $pkgId $remote",
            totalTimeoutMs = 240_000,
            onOutput = { chunk -> chunk.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { log("  TV: $it") } },
            stopWhen = { it.contains("spend time") || it.contains("install failed") || it.contains("Fail", ignoreCase = false) && it.contains("reason") },
        )
        return interpret(out)
    }

    fun interpret(out: String): InstallResult {
        val lower = out.lowercase()
        val ok = (lower.contains("install completed") || lower.contains("installed successfully")) && !lower.contains("failed")
        if (ok) return InstallResult(true, "Installation erfolgreich.", out)
        val hint = when {
            lower.contains("certificate") || lower.contains("signature") || lower.contains("-12") ->
                "Zertifikat passt nicht zu diesem Fernseher. Bitte unter \"Zertifikat\" neu erstellen, damit die Geräte-ID dieses Fernsehers enthalten ist."
            lower.contains("privilege") -> "Die App verlangt Rechte, die der Fernseher nicht zulässt."
            lower.contains("version") -> "Die App ist für eine neuere Tizen-Version gebaut als dieser Fernseher hat."
            lower.contains("space") || lower.contains("storage") -> "Auf dem Fernseher ist nicht genug Speicher frei."
            out.isBlank() -> "Der Fernseher hat keine Rückmeldung gegeben. Bitte den Fernseher neu starten und erneut versuchen."
            else -> "Der Fernseher hat die Installation abgelehnt."
        }
        return InstallResult(false, hint, out)
    }

    /** Startet eine installierte App (nicht jeder Fernseher unterstützt das). */
    fun launch(conn: BridgeConnection, appId: String): String =
        conn.shell("0 was_execute $appId", 20_000)
}
