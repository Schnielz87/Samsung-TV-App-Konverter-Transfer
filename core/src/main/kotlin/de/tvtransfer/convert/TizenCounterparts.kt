package de.tvtransfer.convert

import de.tvtransfer.net.Http
import de.tvtransfer.net.Json
import java.io.File
import java.io.IOException

/**
 * Apps, für die es eine eigene Samsung-Version (Tizen) gibt. Wird die Android-App gewählt, lädt das Programm
 * automatisch deren Samsung-Fassung, signiert sie für den Fernseher und installiert sie.
 *
 * Neue App ergänzen: Paketnamen der Android-App, GitHub-Repo mit Releases und Muster des Samsung-Dateinamens.
 */
data class TizenCounterpart(
    val name: String,
    val packageNames: Set<String>,
    /** Öffentliches GitHub-Repo, dessen Releases die Samsung-Fassung enthalten. */
    val repo: String,
    /** Dateinamen der Samsung-Fassung im Release (.wgt bevorzugt, sonst ZIP mit den Web-Dateien). */
    val assetPatterns: List<Regex>,
)

object TizenCounterparts {

    val ALL = listOf(
        TizenCounterpart(
            name = "PowerIPTV (Portiva)",
            packageNames = setOf("com.poweriptv.app"),
            repo = "Schnielz87/PowerIPTV",
            assetPatterns = listOf(Regex("^PowerIPTV\\.wgt$"), Regex("^PowerIPTV-Tizen-v[\\d.]+\\.wgt$"), Regex("^PowerIPTV-Tizen-v[\\d.]+\\.zip$")),
        ),
    )

    fun forPackage(pkg: String?): TizenCounterpart? =
        pkg?.let { p -> ALL.firstOrNull { c -> c.packageNames.any { p == it || p.startsWith("$it.") } } }

    /**
     * Lädt die neueste Samsung-Fassung und liefert sie als (unsignierte oder fremd signierte) .wgt-Datei.
     * Signiert wird anschließend wie jede andere App mit dem Samsung-Zertifikat des Nutzers.
     */
    fun download(c: TizenCounterpart, cacheDir: File, log: (String) -> Unit): File {
        log("Suche die Samsung-Version von ${c.name} …")
        val r = Http.get("https://api.github.com/repos/${c.repo}/releases?per_page=30", 20_000, mapOf("Accept" to "application/vnd.github+json"))
        if (r.code == 403 || r.code == 429) throw IOException("GitHub ist gerade ausgelastet – bitte in ein paar Minuten erneut versuchen.")
        if (!r.ok) throw IOException("Samsung-Version von ${c.name} nicht abrufbar (HTTP ${r.code}).")
        @Suppress("UNCHECKED_CAST")
        val releases = (Json.parse(r.text) as? List<Map<String, Any?>> ?: emptyList()).filter { it["draft"] != true }
        for (rel in releases) { // neueste zuerst
            @Suppress("UNCHECKED_CAST")
            val assets = rel["assets"] as? List<Map<String, Any?>> ?: continue
            for (pattern in c.assetPatterns) {
                val a = assets.firstOrNull { pattern.matches(it["name"].toString()) } ?: continue
                val name = a["name"].toString()
                log("Lade ${name} (${rel["tag_name"]}) …")
                val dl = Http.get(a["browser_download_url"].toString(), 180_000)
                if (!dl.ok) throw IOException("Download fehlgeschlagen (HTTP ${dl.code}).")
                val wgt = if (name.endsWith(".zip")) zipToWgt(dl.body) else dl.body
                val out = File(cacheDir, "samsung-${c.packageNames.first()}.wgt")
                out.parentFile.mkdirs()
                out.writeBytes(wgt)
                return out
            }
        }
        throw IOException("Im Repo ${c.repo} wurde noch keine Samsung-Version gefunden.")
    }

    /** ZIP mit Ordner (z. B. "PowerIPTV-Tizen/config.xml …") → .wgt (config.xml im Hauptverzeichnis). */
    fun zipToWgt(zip: ByteArray): ByteArray {
        val files = Zip.read(zip)
        val config = files.keys.filter { it.endsWith("config.xml") }.minByOrNull { it.count { c -> c == '/' } }
            ?: throw IOException("Die Samsung-Version enthält keine config.xml.")
        val prefix = config.removeSuffix("config.xml")
        val out = LinkedHashMap<String, ByteArray>()
        out["config.xml"] = files[config]!!
        for ((k, v) in files) {
            if (!k.startsWith(prefix) || k == config) continue
            val rel = k.removePrefix(prefix)
            if (de.tvtransfer.sign.WidgetSigner.isSignatureFile(rel) || rel.endsWith(".wgt")) continue
            out[rel] = v
        }
        return Zip.write(out)
    }
}
