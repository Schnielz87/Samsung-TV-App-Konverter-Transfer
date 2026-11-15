package de.tvtransfer.convert

import javax.xml.parsers.DocumentBuilderFactory

/**
 * Prüft eine erzeugte Samsung-App (.wgt) vor dem Signieren. Ein Paket mit Befund [Portability.BLOCKER]
 * wird nie als "erfolgreich konvertiert" ausgegeben.
 */
object Validator {

    data class Result(val findings: List<Finding>) {
        val ok: Boolean get() = findings.none { it.status == Portability.BLOCKER }
        val limitations: List<Finding> get() = findings.filter { it.status == Portability.LIMITED }
        fun blockers() = findings.filter { it.status == Portability.BLOCKER }
    }

    fun validate(wgt: ByteArray): Result {
        val out = ArrayList<Finding>()
        val files = runCatching { Zip.read(wgt) }.getOrElse {
            return Result(listOf(Finding("Paket", "Paket ist kein gültiges ZIP-Archiv", Portability.BLOCKER, it.message ?: "")))
        }
        val configBytes = files["config.xml"]
            ?: return Result(listOf(Finding("Paket", "config.xml fehlt", Portability.BLOCKER, "Ohne config.xml erkennt der Fernseher keine App.")))

        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(configBytes.inputStream())
        }.getOrElse {
            return Result(listOf(Finding("Paket", "config.xml ist fehlerhaft", Portability.BLOCKER, it.message ?: "")))
        }
        val root = doc.documentElement
        if (root.localName != "widget") out += Finding("Paket", "config.xml: Wurzelelement ist nicht <widget>", Portability.BLOCKER, "")

        val app = root.getElementsByTagNameNS("http://tizen.org/ns/widgets", "application").item(0) as? org.w3c.dom.Element
        val pkg = app?.getAttribute("package").orEmpty()
        val appId = app?.getAttribute("id").orEmpty()
        when {
            app == null -> out += Finding("Paket", "Tizen-App-ID fehlt", Portability.BLOCKER, "<tizen:application> fehlt in config.xml.")
            !Regex("^[0-9A-Za-z]{10}$").matches(pkg) -> out += Finding("Paket", "Paket-ID \"$pkg\" ungültig", Portability.BLOCKER, "Muss aus genau 10 Buchstaben/Ziffern bestehen.")
            !appId.startsWith("$pkg.") -> out += Finding("Paket", "App-ID \"$appId\" ungültig", Portability.BLOCKER, "Muss mit der Paket-ID und einem Punkt beginnen.")
            else -> out += Finding("Paket", "App-ID $appId", Portability.OK, "")
        }
        if (!Regex("^\\d+\\.\\d+\\.\\d+$").matches(root.getAttribute("version")))
            out += Finding("Paket", "Versionsnummer \"${root.getAttribute("version")}\" ungültig", Portability.BLOCKER, "Erwartet x.y.z.")

        // Startseite
        val content = (root.getElementsByTagNameNS("http://www.w3.org/ns/widgets", "content").item(0) as? org.w3c.dom.Element)?.getAttribute("src").orEmpty()
        when {
            content.isEmpty() -> out += Finding("Start", "Keine Startseite angegeben", Portability.BLOCKER, "")
            content.startsWith("http://") || content.startsWith("https://") ->
                out += Finding("Start", "Startet die Webseite $content", Portability.INFO, "Funktioniert nur mit Internet; Bedienung hängt von der Seite ab.")
            files[content] == null -> out += Finding("Start", "Startseite $content fehlt im Paket", Portability.BLOCKER, "")
            else -> {
                out += Finding("Start", "Startseite $content vorhanden", Portability.OK, "")
                checkReferences(content, String(files[content]!!, Charsets.UTF_8), files.keys, out)
            }
        }

        // Symbol
        val icon = (root.getElementsByTagNameNS("http://www.w3.org/ns/widgets", "icon").item(0) as? org.w3c.dom.Element)?.getAttribute("src")
        if (icon.isNullOrEmpty() || files[icon] == null) out += Finding("Paket", "App-Symbol fehlt", Portability.LIMITED, "Der Fernseher zeigt ein Standardsymbol.")

        val size = wgt.size / 1024
        if (size > 200 * 1024) out += Finding("Paket", "Paket sehr groß ($size KB)", Portability.LIMITED, "Ältere Fernseher haben wenig Speicher.")
        return Result(out)
    }

    /** Prüft, ob in der Startseite eingebundene lokale Skripte/Stylesheets im Paket vorhanden sind. */
    private fun checkReferences(page: String, html: String, files: Set<String>, out: MutableList<Finding>) {
        val base = page.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val refs = Regex("<(?:script|link)[^>]+(?:src|href)=[\"']([^\"'#?]+)", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.groupValues[1] }
            // $WEBAPIS/… und $B2BAPIS/… stellt der Fernseher selbst bereit
            .filter { !it.startsWith("http") && !it.startsWith("//") && !it.startsWith("data:") && !it.startsWith("$") }
            .toList()
        val missing = refs.filter { ref -> normalize(base + ref) !in files }
            .filter { it != "cordova.js" && it != "cordova_plugins.js" } // werden ersetzt bzw. sind optional
        if (missing.isEmpty()) {
            if (refs.isNotEmpty()) out += Finding("Start", "${refs.size} eingebundene Dateien vorhanden", Portability.OK, "")
        } else {
            out += Finding("Start", "Fehlende Dateien: ${missing.take(5).joinToString()}${if (missing.size > 5) " …" else ""}", Portability.BLOCKER,
                "Die Startseite verweist auf Dateien, die nicht im Paket sind – die App würde nicht funktionieren.")
        }
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.removePrefix("./").split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        return parts.joinToString("/")
    }
}
