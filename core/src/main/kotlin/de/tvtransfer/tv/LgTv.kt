package de.tvtransfer.tv

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import de.tvtransfer.net.Http
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Installation auf LG-Fernsehern (webOS) über LGs offiziellen Entwicklermodus – wie LGs Werkzeug "ares-cli":
 *  1. Am Fernseher die App "Developer Mode" (LG Content Store) mit einem kostenlosen LG-Entwicklerkonto starten,
 *     "Dev Mode Status" EIN, Fernseher neu starten, "Key Server" EIN.
 *  2. Dieses Programm lädt den Geräteschlüssel vom Key Server (Port 9991) und meldet sich mit der am Fernseher
 *     angezeigten Passphrase per SSH (Port 9922, Benutzer "prisoner") an.
 *  3. Paket hochladen und mit dem Installationsdienst des Fernsehers installieren.
 */
object LgTv {

    const val SSH_PORT = 9922
    private const val TEMP_DIR = "/media/developer/temp"

    class LgException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Geräteschlüssel vom Key Server des Fernsehers holen (nur im Entwicklermodus mit "Key Server: EIN"). */
    fun fetchKey(host: String): ByteArray {
        val r = try { Http.get("http://$host:9991/webos_rsa", 8000) } catch (e: Exception) {
            throw LgException("Der Key Server des Fernsehers ist nicht erreichbar. Bitte in der App „Developer Mode“ am Fernseher „Key Server“ einschalten.", e)
        }
        if (!r.ok || !r.text.contains("PRIVATE KEY")) throw LgException("Der Fernseher hat keinen Schlüssel geliefert (HTTP ${r.code}). Ist „Key Server“ in der Developer-Mode-App eingeschaltet?")
        return r.body
    }

    fun connect(host: String, key: ByteArray, passphrase: String): Session {
        val jsch = JSch()
        try {
            jsch.addIdentity("lg-webos", key, null, passphrase.trim().toByteArray())
        } catch (e: Exception) {
            throw LgException("Die Passphrase passt nicht. Bitte die 6 Zeichen aus der Developer-Mode-App am Fernseher genau übernehmen (Groß-/Kleinschreibung).", e)
        }
        val s = jsch.getSession("prisoner", host, SSH_PORT)
        // Entwicklermodus der Fernseher nutzt ältere Verfahren (ssh-rsa); Gerät im eigenen Heimnetz
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa")
        s.setConfig("PubkeyAcceptedAlgorithms", "rsa-sha2-512,rsa-sha2-256,ssh-rsa")
        try {
            s.connect(15_000)
        } catch (e: Exception) {
            throw LgException("Anmeldung am LG-Fernseher fehlgeschlagen (${e.message}). Ist der Entwicklermodus aktiv und noch nicht abgelaufen?", e)
        }
        return s
    }

    fun exec(s: Session, command: String, timeoutMs: Long = 120_000, stopWhen: (String) -> Boolean = { false }): String {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val input = ch.inputStream
        ch.setErrStream(System.err, true)
        ch.connect(10_000)
        val out = StringBuilder()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                while (input.available() > 0) {
                    val n = input.read(buf); if (n < 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                }
                if (stopWhen(out.toString()) || ch.isClosed) break
                Thread.sleep(150)
            }
        } finally {
            ch.disconnect()
        }
        return out.toString()
    }

    /**
     * Überträgt und installiert das Paket. Rückgabe: (Erfolg, Meldung).
     */
    fun install(s: Session, ipk: ByteArray, appId: String, log: (String) -> Unit): Pair<Boolean, String> {
        val remote = "$TEMP_DIR/tvtransfer_$appId.ipk"
        log("Übertrage Paket auf den LG-Fernseher …")
        val sftp = s.openChannel("sftp") as ChannelSftp
        sftp.connect(10_000)
        try {
            runCatching { sftp.mkdir(TEMP_DIR) }
            sftp.put(ByteArrayInputStream(ipk), remote)
        } finally {
            sftp.disconnect()
        }
        log("Installiere …")
        val payload = "{\"id\":\"com.ares.defaultName\",\"ipkUrl\":\"$remote\",\"subscribe\":true}"
        val out = exec(s, "luna-send-pub -i -f luna://com.webos.appInstallService/dev/install '$payload'", 180_000) { o ->
            o.contains("\"installed\"") || o.contains("\"failed\"") || o.contains("\"errorCode\"") || o.contains("\"returnValue\": false") || o.contains("\"returnValue\":false")
        }
        out.lines().map { it.trim() }.filter { it.contains("state") || it.contains("errorText") || it.contains("errorCode") }.take(20).forEach { log("  TV: $it") }
        runCatching { exec(s, "rm -f $remote", 15_000) }
        return when {
            out.contains("\"installed\"") -> true to "Installation erfolgreich."
            out.contains("errorText") -> false to "Der Fernseher hat die Installation abgelehnt: " +
                (Regex("\"errorText\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1) ?: out.take(300))
            else -> false to "Keine Bestätigung vom Fernseher erhalten: ${out.trim().take(300)}"
        }
    }

    fun launch(s: Session, appId: String) {
        exec(s, "luna-send-pub -n 1 luna://com.webos.applicationManager/launch '{\"id\":\"$appId\"}'", 20_000)
    }

    /** Schlüssel + Passphrase je Fernseher im privaten Programmordner merken (Entwicklermodus-Sitzung). */
    class KeyStore(private val dir: File) {
        fun key(host: String): ByteArray? = File(dir, "$host.key").takeIf { it.exists() }?.readBytes()
        fun passphrase(host: String): String? = File(dir, "$host.pass").takeIf { it.exists() }?.readText()
        fun save(host: String, key: ByteArray, pass: String) {
            dir.mkdirs(); File(dir, "$host.key").writeBytes(key); File(dir, "$host.pass").writeText(pass)
        }
        fun forget(host: String) { File(dir, "$host.key").delete(); File(dir, "$host.pass").delete() }
    }
}
