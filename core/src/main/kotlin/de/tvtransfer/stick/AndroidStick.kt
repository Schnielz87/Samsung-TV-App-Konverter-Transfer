package de.tvtransfer.stick

import de.tvtransfer.bridge.AdbKey
import de.tvtransfer.bridge.BridgeConnection
import de.tvtransfer.bridge.BridgeException
import java.io.File

/**
 * Installation auf Android-TV-Geräten (Fire TV Stick, Chromecast/Google TV, Android-TV-Boxen),
 * die z. B. am HDMI-Eingang des Samsung-Fernsehers stecken. Dort laufen APKs unverändert.
 *
 * Auf dem Stick einmalig: Entwickleroptionen → "ADB-Debugging" bzw. "USB-Debugging" einschalten.
 */
object AndroidStick {

    fun connect(host: String, key: AdbKey, onConfirmOnTv: () -> Unit): BridgeConnection = try {
        BridgeConnection.connect(host, BridgeConnection.ADB_PORT, key, onAuthPrompt = onConfirmOnTv)
    } catch (e: BridgeException.Unreachable) {
        throw BridgeException.Closed(
            "Der Stick ist nicht erreichbar. Bitte am Stick unter Einstellungen → Mein Fire TV/Info → Entwickleroptionen " +
                "\"ADB-Debugging\" einschalten (Google TV: \"USB-Debugging\").",
            e,
        )
    }

    fun deviceName(conn: BridgeConnection): String {
        val b = conn.banner
        val model = Regex("ro.product.model=([^;]+)").find(b)?.groupValues?.get(1)
        val name = Regex("ro.product.name=([^;]+)").find(b)?.groupValues?.get(1)
        return model ?: name ?: runCatching { conn.shell("getprop ro.product.model", 10_000).trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "Android-Gerät"
    }

    /**
     * Installiert eine App. Mehrere Dateien = geteilte APK (Basis + Splits, z. B. aus einer installierten App vom Handy).
     */
    fun install(
        conn: BridgeConnection,
        apks: List<File>,
        log: (String) -> Unit,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Pair<Boolean, String> {
        require(apks.isNotEmpty())
        val total = apks.sumOf { it.length() }
        var done = 0L
        val remote = apks.mapIndexed { i, f -> "/data/local/tmp/tvtransfer_$i.apk" }
        apks.forEachIndexed { i, f ->
            log("Übertrage ${f.name} (${f.length() / 1024 / 1024} MB) …")
            f.inputStream().use { s ->
                conn.push(s, f.length(), remote[i], 0x81A4) { sent, _ -> onProgress(done + sent, total) }
            }
            done += f.length()
        }
        log("Installiere auf dem Stick …")
        val result = if (apks.size == 1) {
            conn.shell("pm install -r \"${remote[0]}\"", 300_000)
        } else {
            val create = conn.shell("pm install-create -r -S $total", 60_000)
            val session = Regex("\\[(\\d+)]").find(create)?.groupValues?.get(1)
                ?: return false to "Installation konnte nicht gestartet werden: $create"
            apks.forEachIndexed { i, f ->
                val w = conn.shell("pm install-write -S ${f.length()} $session split_$i.apk \"${remote[i]}\"", 300_000)
                if (!w.contains("Success")) return false to "Teil ${f.name} wurde abgelehnt: $w"
            }
            conn.shell("pm install-commit $session", 300_000)
        }
        runCatching { conn.shell("rm -f ${remote.joinToString(" ")}", 15_000) }
        return if (result.contains("Success")) true to "Installation erfolgreich." else false to explain(result)
    }

    fun explain(out: String): String = when {
        out.contains("INSTALL_FAILED_NO_MATCHING_ABIS") -> "Die App ist nicht für den Prozessor des Sticks gebaut."
        out.contains("INSTALL_FAILED_OLDER_SDK") -> "Die App braucht eine neuere Android-Version als der Stick hat."
        out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") -> "Eine andere Version der App ist bereits installiert (andere Signatur). Bitte zuerst am Stick deinstallieren."
        out.contains("INSTALL_FAILED_VERSION_DOWNGRADE") -> "Auf dem Stick ist bereits eine neuere Version installiert."
        out.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> "Auf dem Stick ist nicht genug Speicher frei."
        out.contains("INSTALL_FAILED_MISSING_SPLIT") -> "Es fehlen Teile der App. Bitte die App direkt aus der App-Liste des Handys wählen (dann werden alle Teile mitgeschickt)."
        else -> "Der Stick hat die Installation abgelehnt: ${out.trim().take(300)}"
    }

    /** Öffnet die App nach der Installation. */
    fun launch(conn: BridgeConnection, packageName: String) =
        conn.shell("monkey -p $packageName -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p $packageName 1", 20_000)
}
