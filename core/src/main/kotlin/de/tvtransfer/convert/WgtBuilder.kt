package de.tvtransfer.convert

import de.tvtransfer.tv.TvModel
import java.io.File
import java.security.MessageDigest

/** Einstellungen für die erzeugte Samsung-App. */
data class WgtOptions(
    val name: String,
    val version: String = "1.0.0",
    val model: TvModel = TvModel.DEFAULT,
    /** Bei Webseiten: welche Adresse geöffnet wird. */
    val url: String? = null,
    /** Fernbedienungs-Hilfe (Pfeiltasten springen zwischen Elementen, Zurück-Taste). */
    val remoteHelper: Boolean = true,
)

/** Baut aus Web-Inhalten bzw. einer Webadresse eine (noch unsignierte) Tizen-Web-App (.wgt). */
object WgtBuilder {

    data class Result(val wgt: ByteArray, val packageId: String, val appId: String)

    /** Entwurf aus einer echten Android-App (siehe [DraftGenerator]). */
    fun buildDraft(analysis: Analysis, options: WgtOptions): Pair<Result, DraftGenerator.Draft> {
        val draft = DraftGenerator.generate(analysis.source!!, analysis)
        val result = fromWebFiles(draft.files, "index.html", options, seed = "draft:" + (analysis.packageName ?: analysis.displayName), icon = analysis.icon, cordova = false)
        return result to draft
    }

    fun build(analysis: Analysis, options: WgtOptions): Result = when (analysis.type) {
        SourceType.WEB_APP_IN_APK -> fromWebFiles(
            Zip.subtree(analysis.source!!, analysis.webRoot!!), analysis.startPage, options,
            seed = analysis.packageName ?: analysis.displayName, icon = analysis.icon,
            cordova = analysis.framework.startsWith("Cordova"),
        )
        SourceType.WEBVIEW_WRAPPER, SourceType.WEBSITE -> fromUrl(
            options.url ?: analysis.urls.first(), options,
            seed = (analysis.packageName ?: "") + (options.url ?: analysis.urls.first()), icon = analysis.icon,
        )
        SourceType.TIZEN_PACKAGE -> {
            val bytes = analysis.source!!.readBytes()
            Result(bytes, de.tvtransfer.tv.SamsungTv.packageId(bytes) ?: "", de.tvtransfer.tv.SamsungTv.applicationId(bytes) ?: "")
        }
        SourceType.SAMSUNG_VERSION -> throw IllegalStateException("Samsung-Version muss zuerst geladen werden.")
        else -> throw IllegalArgumentException("Diese Datei lässt sich nicht in eine Samsung-App umwandeln.")
    }

    fun fromUrl(url: String, options: WgtOptions, seed: String = url, icon: ByteArray? = null): Result {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "*"
        val files = LinkedHashMap<String, ByteArray>()
        val ids = ids(seed, options.name)
        files["config.xml"] = configXml(ids, options, content = url, allowHost = host).toByteArray(Charsets.UTF_8)
        files["icon.png"] = icon ?: defaultIcon()
        return Result(Zip.write(files), ids.first, ids.second)
    }

    fun fromWebFiles(
        web: Map<String, ByteArray>,
        startPage: String,
        options: WgtOptions,
        seed: String,
        icon: ByteArray?,
        cordova: Boolean,
    ): Result {
        val ids = ids(seed, options.name)
        val files = LinkedHashMap<String, ByteArray>()
        for ((name, data) in web) {
            if (name == "config.xml" || de.tvtransfer.sign.WidgetSigner.isSignatureFile(name)) continue
            files[name] = data
        }
        if (cordova || "cordova.js" in files) files["cordova.js"] = resource("cordova-shim.js")
        if (options.remoteHelper) {
            files["tv-bridge.js"] = resource("tv-bridge.js")
            files[startPage]?.let { html -> files[startPage] = injectScript(String(html, Charsets.UTF_8), "tv-bridge.js").toByteArray(Charsets.UTF_8) }
        }
        files["config.xml"] = configXml(ids, options, content = startPage, allowHost = "*").toByteArray(Charsets.UTF_8)
        if ("icon.png" !in files || icon != null) files["tv-icon.png"] = icon ?: defaultIcon()
        // config.xml zuerst – manche Fernseher erwarten das
        val ordered = LinkedHashMap<String, ByteArray>()
        ordered["config.xml"] = files.remove("config.xml")!!
        ordered.putAll(files)
        return Result(Zip.write(ordered), ids.first, ids.second)
    }

    /** Fügt das Skript möglichst früh im <head> ein (vor den App-Skripten). */
    fun injectScript(html: String, src: String): String {
        val tag = "<script src=\"$src\"></script>"
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (head != null) return html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
        val htmlTag = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (htmlTag != null) return html.substring(0, htmlTag.range.last + 1) + "<head>$tag</head>" + html.substring(htmlTag.range.last + 1)
        return tag + html
    }

    /** Paket-ID (10 Zeichen) und App-ID – gleiche Quelle ergibt gleiche IDs, damit Updates die alte Version ersetzen. */
    fun ids(seed: String, name: String): Pair<String, String> {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val h = MessageDigest.getInstance("SHA-256").digest(("tvtransfer:" + seed).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        sb.append(alphabet[(h[0].toInt() and 0xFF) % 52]) // beginnt mit Buchstabe
        for (i in 1 until 10) sb.append(alphabet[(h[i].toInt() and 0xFF) % alphabet.length])
        val pkg = sb.toString()
        val appPart = name.filter { it.isLetterOrDigit() && it.code < 128 }.take(24).ifEmpty { "App" }
        val app = if (appPart[0].isDigit()) "A$appPart" else appPart
        return pkg to "$pkg.$app"
    }

    /** Tizen verlangt Versionsnummern der Form x.y.z (nur Ziffern). */
    fun sanitizeVersion(v: String?): String {
        val nums = Regex("\\d+").findAll(v ?: "").map { it.value.toLong() }.toList()
        val a = (nums.getOrNull(0) ?: 1).coerceIn(0, 255)
        val b = (nums.getOrNull(1) ?: 0).coerceIn(0, 255)
        val c = (nums.getOrNull(2) ?: 0).coerceIn(0, 65535)
        return "$a.$b.$c"
    }

    fun configXml(ids: Pair<String, String>, o: WgtOptions, content: String, allowHost: String): String {
        val name = xmlEscape(o.name.ifBlank { "App" })
        return """<?xml version="1.0" encoding="UTF-8"?>
<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" id="http://tvtransfer.local/${ids.first}" version="${sanitizeVersion(o.version)}" viewmodes="maximized">
    <access origin="*" subdomains="true"></access>
${if (allowHost != "*") "    <tizen:allow-navigation>${xmlEscape(allowHost)}</tizen:allow-navigation>\n" else ""}    <tizen:application id="${ids.second}" package="${ids.first}" required_version="2.3"/>
    <content src="${xmlEscape(content)}"/>
    <feature name="http://tizen.org/feature/screen.size.normal.1080.1920"/>
    <icon src="${if (content.startsWith("http")) "icon.png" else "tv-icon.png"}"/>
    <name>$name</name>
    <tizen:privilege name="http://tizen.org/privilege/internet"/>
    <tizen:privilege name="http://tizen.org/privilege/tv.inputdevice"/>
    <tizen:privilege name="http://developer.samsung.com/privilege/network.public"/>
    <tizen:profile name="tv-samsung"/>
    <tizen:setting screen-orientation="landscape" context-menu="enable" background-support="disable" encryption="disable" install-location="auto" hwkey-event="enable"/>
</widget>
"""
    }

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun resource(name: String): ByteArray =
        WgtBuilder::class.java.getResourceAsStream("/de/tvtransfer/web/$name")?.use { it.readBytes() }
            ?: error("Programmdatei $name fehlt.")

    fun defaultIcon(): ByteArray = resource("default-icon.png")

    fun save(result: Result, dir: File, name: String): File {
        dir.mkdirs()
        val f = File(dir, name.filter { it.isLetterOrDigit() || it in " -_" }.ifBlank { "App" } + ".wgt")
        f.writeBytes(result.wgt)
        return f
    }
}
