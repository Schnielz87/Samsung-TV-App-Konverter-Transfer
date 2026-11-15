package de.tvtransfer.convert

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Baut aus einer (unsignierten) Tizen-Web-App eine LG-webOS-App (.ipk) – im selben Format wie LGs Werkzeug
 * "ares-package": ar-Archiv aus debian-binary, control.tar.gz und data.tar.gz (usr/palm/applications/<id>/…).
 */
object WebOsBuilder {

    class Ipk(val bytes: ByteArray, val appId: String, val version: String, val title: String, val warnings: List<String>)

    fun fromWgt(wgt: ByteArray): Ipk {
        val files = Zip.read(wgt)
        val config = files["config.xml"] ?: throw IllegalArgumentException("config.xml fehlt.")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(config.inputStream())
        val root = doc.documentElement
        val title = root.getElementsByTagNameNS("http://www.w3.org/ns/widgets", "name").item(0)?.textContent?.trim().orEmpty().ifEmpty { "App" }
        val version = root.getAttribute("version").takeIf { Regex("^\\d+\\.\\d+\\.\\d+$").matches(it) } ?: "1.0.0"
        val content = (root.getElementsByTagNameNS("http://www.w3.org/ns/widgets", "content").item(0) as? org.w3c.dom.Element)?.getAttribute("src").orEmpty()
        val icon = (root.getElementsByTagNameNS("http://www.w3.org/ns/widgets", "icon").item(0) as? org.w3c.dom.Element)?.getAttribute("src")
        val tizenPkg = (root.getElementsByTagNameNS("http://tizen.org/ns/widgets", "application").item(0) as? org.w3c.dom.Element)?.getAttribute("package").orEmpty()
        val appId = "com.tvtransfer." + (tizenPkg.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" })

        val app = LinkedHashMap<String, ByteArray>()
        val warnings = ArrayList<String>()
        for ((name, data) in files) {
            if (name == "config.xml" || de.tvtransfer.sign.WidgetSigner.isSignatureFile(name)) continue
            app[name] = data
        }
        // Samsung-eigene Schnittstellen erkennen (laufen auf LG nicht)
        val code = app.filterKeys { (it.endsWith(".js") || it.endsWith(".html")) && it != "tv-bridge.js" && it != "cordova.js" }.values.joinToString("") { String(it, Charsets.UTF_8) }
        if (code.contains("webapis.avplay") || code.contains("\$WEBAPIS")) warnings += "Nutzt Samsung-Schnittstellen (z. B. Samsung-Videoplayer AVPlay) – diese gibt es auf LG nicht."
        if (Regex("\\btizen\\.(application|tvinputdevice|filesystem|systeminfo)").containsMatchIn(code))
            warnings += "Nutzt Tizen-Schnittstellen – betroffene Funktionen laufen auf LG nicht."

        val main = if (content.startsWith("http://") || content.startsWith("https://")) {
            // Webseiten-App: kleine Startseite, die zur Adresse weiterleitet
            app["tvtransfer-start.html"] = ("<!doctype html><html><head><meta charset=\"utf-8\"><title>$title</title></head>" +
                "<body style=\"background:#000\"><script>location.replace(${jsString(content)});</script></body></html>").toByteArray()
            "tvtransfer-start.html"
        } else content.ifEmpty { "index.html" }

        val iconName = icon?.takeIf { app.containsKey(it) } ?: "tv-icon.png".takeIf { app.containsKey(it) } ?: run {
            app["icon.png"] = WgtBuilder.defaultIcon(); "icon.png"
        }
        val appInfo = de.tvtransfer.net.Json.write(
            linkedMapOf(
                "id" to appId, "version" to version, "vendor" to "TV-App-Transfer", "type" to "web", "main" to main,
                "title" to title, "icon" to iconName, "largeIcon" to iconName, "resolution" to "1920x1080",
                "uiRevision" to 2,
            ),
        )
        app["appinfo.json"] = appInfo.toByteArray()

        val pkgInfo = de.tvtransfer.net.Json.write(
            linkedMapOf("app" to appId, "id" to appId, "loc_name" to title, "package_format_version" to 2, "vendor" to "TV-App-Transfer", "version" to version),
        )

        val dataTar = Tar()
        dataTar.dir("./"); dataTar.dir("./usr/"); dataTar.dir("./usr/palm/"); dataTar.dir("./usr/palm/applications/"); dataTar.dir("./usr/palm/applications/$appId/")
        val dirs = sortedSetOf<String>()
        app.keys.forEach { n -> n.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.forEach { dirs += it } }
        dirs.forEach { dataTar.dir("./usr/palm/applications/$appId/$it/") }
        app.forEach { (n, d) -> dataTar.file("./usr/palm/applications/$appId/$n", d) }
        dataTar.dir("./usr/palm/packages/"); dataTar.dir("./usr/palm/packages/$appId/")
        dataTar.file("./usr/palm/packages/$appId/packageinfo.json", pkgInfo.toByteArray())
        val data = gzip(dataTar.finish())

        val installedKb = (app.values.sumOf { it.size } / 1024) + 1
        val control = """Package: $appId
Version: $version
Section: misc
Priority: optional
Architecture: all
Installed-Size: $installedKb
Maintainer: TV-App-Transfer
Description: $title
webOS-Package-Format-Version: 2
webOS-Packager-Version: 1.0.0
"""
        val controlTar = Tar().apply { dir("./"); file("./control", control.toByteArray()) }
        val ctl = gzip(controlTar.finish())

        val ar = Ar()
        ar.add("debian-binary", "2.0\n".toByteArray())
        ar.add("control.tar.gz", ctl)
        ar.add("data.tar.gz", data)
        return Ipk(ar.finish(), appId, version, title, warnings)
    }

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** Minimaler tar-Schreiber (ustar). */
    internal class Tar {
        private val out = ByteArrayOutputStream()
        fun dir(name: String) = entry(name, ByteArray(0), '5', "0000755")
        fun file(name: String, data: ByteArray) = entry(name, data, '0', "0000644")
        private fun entry(name: String, data: ByteArray, type: Char, mode: String) {
            val h = ByteArray(512)
            fun put(off: Int, len: Int, v: String) { val b = v.toByteArray(Charsets.UTF_8); System.arraycopy(b, 0, h, off, minOf(b.size, len)) }
            val (prefix, nm) = if (name.toByteArray().size <= 100) "" to name else {
                val cut = name.lastIndexOf('/', 154).takeIf { it > 0 } ?: throw IllegalArgumentException("Dateiname zu lang: $name")
                name.substring(0, cut) to name.substring(cut + 1)
            }
            put(0, 100, nm)
            put(100, 8, "$mode\u0000")
            put(108, 8, "0000000\u0000"); put(116, 8, "0000000\u0000")
            put(124, 12, String.format("%011o", data.size) + "\u0000")
            put(136, 12, String.format("%011o", 1700000000L) + "\u0000")
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = type.code.toByte()
            put(257, 6, "ustar\u0000"); put(263, 2, "00")
            put(265, 32, "root"); put(297, 32, "root")
            put(345, 155, prefix)
            val sum = h.sumOf { it.toInt() and 0xFF }
            put(148, 8, String.format("%06o", sum) + "\u0000 ")
            out.write(h)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
        }
        fun finish(): ByteArray { out.write(ByteArray(1024)); return out.toByteArray() }
    }

    /** Minimaler ar-Schreiber (wie bei Debian-Paketen). */
    internal class Ar {
        private val out = ByteArrayOutputStream().apply { write("!<arch>\n".toByteArray()) }
        fun add(name: String, data: ByteArray) {
            fun pad(s: String, n: Int) = s.padEnd(n, ' ').take(n)
            val header = pad(name, 16) + pad("1700000000", 12) + pad("0", 6) + pad("0", 6) + pad("100644", 8) + pad(data.size.toString(), 10) + "`\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(data)
            if (data.size % 2 == 1) out.write('\n'.code)
        }
        fun finish(): ByteArray = out.toByteArray()
    }
}
