package de.tvtransfer.update

import de.tvtransfer.net.Http
import de.tvtransfer.net.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Eine veröffentlichte Version. */
data class ReleaseInfo(
    val tag: String,
    /** Laufnummer aus "tv-app-transfer-v1.0.<Build>". */
    val build: Int,
    val version: String,
    val notes: String,
    val pageUrl: String,
    /** Dateiname -> Download-Link */
    val assets: Map<String, String>,
)

/**
 * Prüft auf GitHub, ob es eine neuere Version gibt (gleiches Schema wie PowerIPTV und Portiva):
 * Versionen heißen "tv-app-transfer-v1.0.<Laufnummer>" und liegen als Releases im eigenen öffentlichen Repo.
 * Kein GitHub-Konto nötig.
 */
class UpdateChecker(
    private val apiBase: String = "https://api.github.com",
    private val repos: List<String> = REPOS,
) {
    /** Neueste veröffentlichte Version. */
    fun latest(): ReleaseInfo {
        var lastError: IOException? = null
        val found = repos.mapNotNull { repo ->
            try {
                val r = Http.get("$apiBase/repos/$repo/releases?per_page=50", 15_000, mapOf("Accept" to "application/vnd.github+json"))
                when {
                    r.code == 403 || r.code == 429 -> { lastError = IOException("GitHub ist gerade ausgelastet – bitte später erneut versuchen."); null }
                    !r.ok -> null
                    else -> newestOwn(r.text)
                }
            } catch (e: IOException) {
                lastError = IOException("Keine Verbindung zu GitHub (${e.message}).")
                null
            }
        }
        return found.maxByOrNull { it.build } ?: throw (lastError ?: IOException("Noch keine Version veröffentlicht."))
    }

    /** Lädt eine Datei herunter; erst nach vollständigem Download wird [target] ersetzt. */
    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit): File {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", Http.USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download fehlgeschlagen (HTTP ${conn.responseCode}).")
            val total = conn.contentLengthLong
            var done = 0L
            var last = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().buffered(256 * 1024).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Abgebrochen.")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 512 * 1024) { last = done; onProgress(done, total) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download unvollständig – bitte erneut versuchen.")
            onProgress(done, total)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        return target
    }

    companion object {
        /** Eigenes öffentliches Repo – dort liegen alle Versionen (Releases). */
        const val REPO = "Schnielz87/Samsung-TV-App-Konverter-Transfer"
        val REPOS = listOf(REPO)

        /** Seite mit der aktuellen Version. */
        const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

        const val TAG_PREFIX = "tv-app-transfer-v"

        /** Alle 24 Stunden automatisch prüfen. */
        const val INTERVAL = 24 * 3600_000L

        /** Feste Dateinamen im Release – nicht ändern, die Apps suchen danach. */
        const val APK_NAME = "TV-App-Transfer.apk"
        const val WINDOWS_SETUP_NAME = "TV-App-Transfer-Setup.exe"
        const val WINDOWS_PORTABLE_NAME = "TV-App-Transfer-Portable.zip"

        /** "1.0.12" / "tv-app-transfer-v1.0.12" -> 12 */
        fun buildOf(version: String?): Int? =
            version?.let { Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(it) }?.groupValues?.get(3)?.toIntOrNull()

        fun isOwnTag(tag: String): Boolean = tag.startsWith(TAG_PREFIX)

        @Suppress("UNCHECKED_CAST")
        fun newestOwn(json: String): ReleaseInfo? {
            val list = Json.parse(json) as? List<Map<String, Any?>> ?: return null
            return list
                .filter { it["draft"] != true && it["prerelease"] != true && isOwnTag(it["tag_name"].toString()) }
                .map { o ->
                    val tag = o["tag_name"].toString()
                    val assets = (o["assets"] as? List<Map<String, Any?>> ?: emptyList())
                        .associate { it["name"].toString() to it["browser_download_url"].toString() }
                    ReleaseInfo(
                        tag = tag,
                        build = buildOf(tag) ?: 0,
                        version = tag.removePrefix(TAG_PREFIX),
                        notes = o["body"]?.toString().orEmpty(),
                        pageUrl = o["html_url"]?.toString().orEmpty(),
                        assets = assets,
                    )
                }
                .filter { it.assets.isNotEmpty() }
                .maxByOrNull { it.build }
        }
    }
}
