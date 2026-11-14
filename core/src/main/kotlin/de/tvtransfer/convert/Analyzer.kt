package de.tvtransfer.convert

import java.io.File

/** Was mit einer Datei möglich ist. */
enum class SourceType(val convertible: Boolean) {
    /** APK, deren Inhalt eine Web-App ist (Cordova, Capacitor/Ionic …) → wird zur Samsung-App. */
    WEB_APP_IN_APK(true),
    /** APK, die vermutlich nur eine Webseite anzeigt → Webseite wird als Samsung-App verpackt. */
    WEBVIEW_WRAPPER(true),
    /** Android-App, für die es eine eigene Samsung-Version gibt → diese wird geladen, signiert und installiert. */
    SAMSUNG_VERSION(true),
    /** Bereits eine Tizen-App (.wgt/.tpk) → wird für den Fernseher signiert und installiert. */
    TIZEN_PACKAGE(true),
    /** Adresse einer Webseite/Web-App → wird als Samsung-App verpackt. */
    WEBSITE(true),
    /** Echte Android-App → läuft nicht auf Samsung (Tizen), aber auf einem Android-Stick am Fernseher. */
    NATIVE_ANDROID(false),
    /** Enthält einen Lizenz-/Kopierschutz, der beim Umwandeln wegfallen würde → wird nicht umgewandelt. */
    PROTECTED(false),
    UNKNOWN(false),
}

data class Analysis(
    val source: File?,
    val type: SourceType,
    val displayName: String,
    val packageName: String?,
    val versionName: String?,
    /** z. B. "Cordova", "Capacitor/Ionic", "Flutter", "React Native", "Unity", "Java/Kotlin" */
    val framework: String,
    /** Klartext-Erklärung für den Nutzer. */
    val verdict: String,
    /** Ordner der Web-Inhalte in der APK, z. B. "assets/www/". */
    val webRoot: String? = null,
    val startPage: String = "index.html",
    /** Mögliche Webadressen (bei WebView-Hüllen und Webseiten). */
    val urls: List<String> = emptyList(),
    val icon: ByteArray? = null,
    /** APK läuft auf einem Android-TV-Stick (bei APKs immer der Fall, ggf. mit Einschränkungen). */
    val stickCompatible: Boolean = false,
    /** App hat eine eigene Fernseh-Oberfläche (Leanback). */
    val hasTvLauncher: Boolean = false,
    /** Hinweise zur Bedienbarkeit usw. */
    val notes: List<String> = emptyList(),
    /** Bei Paketen vom Handy: alle Teile (Basis + Splits). */
    val apkParts: List<File> = listOfNotNull(source),
    /** Eigene Samsung-Version dieser App (bei [SourceType.SAMSUNG_VERSION]). */
    val counterpart: TizenCounterpart? = null,
) {
    val canConvert: Boolean get() = type.convertible && (type != SourceType.WEBVIEW_WRAPPER || urls.isNotEmpty())
}

object Analyzer {

    /** Ordner für entpackte App-Bündel (.apks/.xapk); wird von der Ablaufsteuerung aufgeräumt. */
    @Volatile var tempDir: File = File(System.getProperty("java.io.tmpdir"), "tvtransfer")

    fun analyze(file: File, labelHint: String? = null, parts: List<File>? = null): Analysis {
        val lower = file.name.lowercase()
        return when {
            lower.endsWith(".wgt") || lower.endsWith(".tpk") -> analyzeTizen(file)
            lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") || isZip(file) -> analyzeApk(file, labelHint, parts)
            else -> Analysis(file, SourceType.UNKNOWN, file.name, null, null, "–", "Dieses Dateiformat wird nicht unterstützt. Möglich sind .apk, .wgt, .tpk oder eine Webadresse.")
        }
    }

    fun website(url: String, name: String? = null): Analysis {
        val clean = normalizeUrl(url)
        val host = runCatching { java.net.URI(clean).host }.getOrNull() ?: clean
        return Analysis(
            source = null,
            type = SourceType.WEBSITE,
            displayName = name?.takeIf { it.isNotBlank() } ?: host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() },
            packageName = null, versionName = "1.0.0", framework = "Webseite",
            verdict = "Die Webseite wird als eigene App auf dem Fernseher angelegt. Die Bedienung mit der Fernbedienung hängt davon ab, wie gut die Seite für Fernseher gemacht ist.",
            urls = listOf(clean),
        )
    }

    fun normalizeUrl(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun isZip(f: File) = runCatching { f.inputStream().use { val h = ByteArray(2); it.read(h) == 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() } }.getOrDefault(false)

    private fun analyzeTizen(file: File): Analysis {
        val names = runCatching { Zip.names(file) }.getOrElse {
            return Analysis(file, SourceType.UNKNOWN, file.name, null, null, "–", "Die Datei ist beschädigt oder kein Tizen-Paket.")
        }
        val isWgt = "config.xml" in names
        val manifest = Zip.entry(file, if (isWgt) "config.xml" else "tizen-manifest.xml")?.toString(Charsets.UTF_8) ?: ""
        val name = Regex("<name[^>]*>([^<]+)</name>").find(manifest)?.groupValues?.get(1)?.trim()
            ?: Regex("<label[^>]*>([^<]+)</label>").find(manifest)?.groupValues?.get(1)?.trim()
            ?: file.nameWithoutExtension
        val version = Regex("\\sversion=\"([^\"]+)\"").find(manifest)?.groupValues?.get(1)
        val signed = "author-signature.xml" in names
        return Analysis(
            file, SourceType.TIZEN_PACKAGE, name, null, version,
            if (isWgt) "Tizen Web-App" else "Tizen native App",
            "Bereits eine Samsung/Tizen-App. Sie wird mit Ihrem Zertifikat für den Fernseher neu signiert und installiert." +
                if (signed) " (Die vorhandene Signatur wird ersetzt.)" else "",
        )
    }

    private fun analyzeApk(file: File, labelHint: String?, parts: List<File>?): Analysis {
        val names = runCatching { Zip.names(file) }.getOrElse {
            return Analysis(file, SourceType.UNKNOWN, file.name, null, null, "–", "Die Datei ist beschädigt oder keine gültige APK.")
        }

        // Bündel (.apks/.xapk): enthält mehrere APKs → die Basis-APK untersuchen
        if ("AndroidManifest.xml" !in names) {
            val inner = names.filter { it.endsWith(".apk") }
            if (inner.isNotEmpty()) {
                val dir = File(tempDir, file.nameWithoutExtension.hashCode().toString()).apply { mkdirs() }
                val extracted = inner.map { n -> File(dir, n.substringAfterLast('/')).also { it.writeBytes(Zip.entry(file, n)!!) } }
                val base = extracted.firstOrNull { it.name == "base.apk" } ?: extracted.maxBy { it.length() }
                return analyzeApk(base, labelHint, listOf(base) + (extracted - base))
            }
            return Analysis(file, SourceType.UNKNOWN, file.name, null, null, "–", "Keine gültige APK (AndroidManifest.xml fehlt).")
        }

        val manifest = runCatching { BinaryXml.parse(Zip.entry(file, "AndroidManifest.xml")!!) }.getOrDefault(emptyList())
        val pkg = manifest.firstOrNull { it.name == "manifest" }?.attrs?.get("package")
        val version = manifest.firstOrNull { it.name == "manifest" }?.attrs?.get("versionName")?.takeIf { !it.startsWith("@") }
        val appLabel = manifest.firstOrNull { it.name == "application" }?.attrs?.get("label")?.takeIf { !it.startsWith("@") }
        val leanback = manifest.any { it.name == "category" && it.attrs["name"] == "android.intent.category.LEANBACK_LAUNCHER" } ||
            manifest.any { it.name == "uses-feature" && it.attrs["name"] == "android.software.leanback" }
        val displayName = labelHint ?: appLabel ?: prettyName(pkg) ?: file.nameWithoutExtension

        val libs = names.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.substringAfterLast('/') }
        val icon = findIcon(file, names)
        val allParts = parts ?: listOf(file)
        val stickNote = if (leanback) "Die App hat eine eigene Fernseh-Oberfläche – ideal für einen Android-TV-Stick."
        else "Die App ist für Handys gemacht. Auf einem Android-Stick läuft sie, ist aber eventuell nur eingeschränkt mit der Fernbedienung bedienbar."

        // 0) Gibt es von dieser App eine eigene Samsung-Version? Dann diese verwenden.
        TizenCounterparts.forPackage(pkg)?.let { c ->
            return Analysis(
                file, SourceType.SAMSUNG_VERSION, displayName, pkg, version, "Android-App mit Samsung-Version",
                "Umwandelbar: Für ${c.name} gibt es eine eigene Samsung-Fassung. Sie wird automatisch geladen, " +
                    "mit Ihrem Zertifikat für den Fernseher signiert und installiert – mit Fernbedienung und Samsung-Videoplayer.",
                icon = icon, stickCompatible = true, hasTvLauncher = leanback, apkParts = allParts, counterpart = c,
                notes = listOf("Ihre Zugänge und Einstellungen übertragen Sie danach in der App selbst (z. B. per QR-Code / Portiva Link)."),
            )
        }

        fun native(framework: String, why: String) = Analysis(
            file, SourceType.NATIVE_ANDROID, displayName, pkg, version, framework,
            "Nicht umwandelbar: $why Samsung-Fernseher (Tizen) können keine Android-Apps ausführen. " +
                "Lösung: Einen Android-TV-Stick (z. B. Fire TV Stick oder Google TV Streamer) an den Samsung-Fernseher anschließen – " +
                "dorthin überträgt dieses Programm die APK direkt. Gibt es die App auch als Webseite, können Sie oben deren Adresse eingeben.",
            icon = icon, stickCompatible = true, hasTvLauncher = leanback, notes = listOf(stickNote), apkParts = allParts,
        )

        // 1) Frameworks, die sich nicht übertragen lassen
        if (libs.any { it == "libflutter.so" } || names.any { it.startsWith("assets/flutter_assets/") })
            return native("Flutter", "Die App ist mit Flutter gebaut (eigener Grafik-Motor).")
        if (names.any { it == "assets/index.android.bundle" } || libs.any { it.startsWith("libreactnative") || it == "libhermes.so" })
            return native("React Native", "Die App ist mit React Native gebaut (nutzt Android-Bausteine).")
        if (libs.any { it == "libunity.so" || it == "libil2cpp.so" })
            return native("Unity", "Die App/das Spiel ist mit Unity gebaut.")
        if (libs.any { it == "libmonodroid.so" } || names.any { it.startsWith("assemblies/") })
            return native(".NET/Xamarin", "Die App ist mit .NET/Xamarin gebaut.")

        // 1b) Schutzmaßnahmen (Lizenzprüfung, Kopierschutz): Eine Umwandlung würde sie aushebeln → nicht umwandeln.
        //     Die unveränderte APK darf weiterhin auf einen Android-Stick (dort bleibt der Schutz vollständig wirksam).
        protectionMarker(file, names)?.let { what ->
            return Analysis(
                file, SourceType.PROTECTED, displayName, pkg, version, "Geschützte App",
                "Diese App enthält eine technische Schutzmaßnahme ($what). Eine Umwandlung für Samsung-Fernseher würde diese " +
                    "Schutzmaßnahme umgehen und wird daher nicht durchgeführt. Die unveränderte App kann auf ein kompatibles " +
                    "Android-TV-Gerät übertragen werden.",
                icon = icon, stickCompatible = true, hasTvLauncher = leanback, apkParts = allParts,
            )
        }

        // 2) Web-Apps im Paket
        val webRoots = listOf(
            "assets/www/" to "Cordova/PhoneGap",
            "assets/public/" to "Capacitor/Ionic",
        )
        for ((root, fw) in webRoots) {
            if ((root + "index.html") in names) {
                val isCordova = (root + "cordova.js") in names
                val framework = if (root == "assets/www/" && !isCordova) "Web-App" else fw
                val pluginNote = if (names.any { it.startsWith(root + "plugins/") })
                    listOf("Die App nutzt Zusatzmodule (Plugins) für Handy-Funktionen. Diese gibt es auf dem Fernseher nicht – einzelne Funktionen (Kamera, GPS, Benachrichtigungen …) können fehlen.")
                else emptyList()
                return Analysis(
                    file, SourceType.WEB_APP_IN_APK, displayName, pkg, version, framework,
                    "Umwandelbar: Die App besteht aus Web-Technik ($framework). Die Inhalte werden in eine Samsung-App übernommen und für die Fernbedienung angepasst.",
                    webRoot = root, icon = icon ?: findWebIcon(file, names, root), stickCompatible = true, hasTvLauncher = leanback,
                    notes = pluginNote + "Handy-Apps sind für Touch gemacht. Die Fernbedienung steuert die App über die Pfeiltasten (Fokus springt zum nächsten Element) und OK.",
                    apkParts = allParts,
                )
            }
        }

        // 3) WebView-Hülle: zeigt eine Webseite an
        val dexes = names.filter { Regex("^classes\\d*\\.dex$").matches(it) }
        val dexBytes = dexes.sumOf { n -> runCatching { java.util.zip.ZipFile(file).use { z -> z.getEntry(n).size } }.getOrDefault(0L) }
        val usesWebView = dexes.any { n -> Zip.entry(file, n)?.let { containsAscii(it, "Landroid/webkit/WebView;") } == true }
        if (usesWebView) {
            val urls = dexes.flatMap { n -> Zip.entry(file, n)?.let { extractUrls(it) } ?: emptyList() }
            val ranked = rankUrls(urls, pkg)
            // Kleine App mit WebView und passender Adresse → sehr wahrscheinlich nur eine Hülle
            val likelyWrapper = ranked.isNotEmpty() && libs.isEmpty() && dexBytes < 6 * 1024 * 1024
            if (likelyWrapper) {
                return Analysis(
                    file, SourceType.WEBVIEW_WRAPPER, displayName, pkg, version, "WebView-Hülle",
                    "Wahrscheinlich umwandelbar: Die App scheint hauptsächlich eine Webseite anzuzeigen. " +
                        "Diese Webseite wird als Samsung-App verpackt. Bitte unten die richtige Adresse prüfen.",
                    urls = ranked, icon = icon, stickCompatible = true, hasTvLauncher = leanback,
                    notes = listOf("Die Adresse wurde aus der App gelesen und ist eine Vermutung. Funktionen, die die App selbst (nicht die Webseite) bereitstellt, fehlen."),
                    apkParts = allParts,
                )
            }
        }

        return native(
            if (libs.isNotEmpty()) "Android (mit nativem Code)" else "Android (Java/Kotlin)",
            "Die App ist eine echte Android-App (Programmcode für Android).",
        )
    }

    /** Bekannte Lizenz-/Kopierschutz-Bausteine in Android-Apps (Klassennamen im Programmcode). */
    private val PROTECTION_MARKERS = listOf(
        "Lcom/google/android/vending/licensing/" to "Google-Play-Lizenzprüfung",
        "Lcom/android/vending/licensing/" to "Google-Play-Lizenzprüfung",
        "Lcom/pairip/" to "Google-Play-Integritätsschutz",
        "Lcom/google/android/play/core/integrity/" to "Play-Integrity-Prüfung",
        "Lcom/dexprotector/" to "Kopierschutz (DexProtector)",
        "Lcom/secneo/" to "Kopierschutz",
        "Lcom/stub/StubApp;" to "Kopierschutz (Packer)",
    )

    private fun protectionMarker(file: File, names: List<String>): String? {
        if (names.any { it.startsWith("lib/") && it.contains("libpairipcore") }) return "Google-Play-Integritätsschutz"
        val dexes = names.filter { Regex("^classes\\d*\\.dex$").matches(it) }
        for (n in dexes) {
            val data = Zip.entry(file, n) ?: continue
            PROTECTION_MARKERS.firstOrNull { containsAscii(data, it.first) }?.let { return it.second }
        }
        return null
    }

    fun prettyName(pkg: String?): String? {
        if (pkg == null) return null
        val parts = pkg.split('.').filter { it !in setOf("com", "de", "org", "net", "app", "android", "www", "io", "tv", "mobile") }
        val p = parts.lastOrNull() ?: return null
        return p.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun findIcon(file: File, names: List<String>): ByteArray? {
        val candidates = names.filter {
            it.startsWith("res/") && it.endsWith(".png") &&
                (it.contains("ic_launcher") || it.contains("app_icon") || it.contains("/icon")) &&
                !it.contains("foreground") && !it.contains("background") && !it.contains("monochrome")
        }
        val best = candidates.maxByOrNull { n -> java.util.zip.ZipFile(file).use { z -> z.getEntry(n).size } } ?: return null
        return Zip.entry(file, best)
    }

    private fun findWebIcon(file: File, names: List<String>, root: String): ByteArray? {
        val best = names.filter { it.startsWith(root) && it.endsWith(".png") && (it.contains("icon") || it.contains("logo")) }
            .maxByOrNull { n -> java.util.zip.ZipFile(file).use { z -> z.getEntry(n).size } } ?: return null
        return Zip.entry(file, best)
    }

    internal fun containsAscii(data: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - n.size) {
            for (j in n.indices) if (data[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    internal fun extractUrls(data: ByteArray): List<String> {
        val out = ArrayList<String>()
        val text = String(data, Charsets.ISO_8859_1)
        Regex("https?://[A-Za-z0-9.\\-]+(?::\\d+)?(?:/[A-Za-z0-9._~%!$&'()*+,;=:@/\\-?#]*)?").findAll(text).forEach { out += it.value }
        return out
    }

    private val IGNORED_HOSTS = listOf(
        "google", "android.com", "gstatic", "googleapis", "firebase", "crashlytics", "facebook", "fb.com", "fbcdn",
        "w3.org", "apache.org", "xmlpull", "schemas.", "json-schema", "example.", "localhost", "127.0.0.1", "github",
        "jquery", "cloudflare", "jsdelivr", "unpkg", "appsflyer", "adjust.com", "onesignal", "doubleclick", "youtube",
        "twitter", "instagram", "mozilla", "microsoft", "apple.com", "ietf.org", "ns.adobe", "purl.org", "opensource",
        "openssl", "bouncycastle", "kotlinlang", "jetbrains", "squareup", "sentry", "branch.io", "amplitude", "mixpanel",
        "schema.org", "play.google", "goo.gl", "bit.ly", "w3c", "xml.org", "unicode.org", "ogp.me", "creativecommons", "gnu.org",
    )

    fun rankUrls(urls: List<String>, pkg: String?): List<String> {
        val tokens = pkg?.split('.')?.filter { it.length > 2 && it !in setOf("com", "app", "android", "mobile", "www") } ?: emptyList()
        return urls.asSequence()
            .map { it.trimEnd('/', '.', ',', ';', ')') }
            .filter { u ->
                val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull() ?: return@filter false
                host.contains('.') && IGNORED_HOSTS.none { host.contains(it) } && !u.contains("%s") && !u.endsWith(".js") && !u.endsWith(".png")
            }
            .groupingBy { it }.eachCount().entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { e -> tokens.count { e.key.lowercase().contains(it) } }
                    .thenByDescending { e -> e.value }
                    .thenBy { e -> if (e.key.startsWith("https://")) 0 else 1 }
                    .thenBy { e -> e.key.length },
            )
            .map { it.key }
            .take(8)
            .toList()
    }
}
