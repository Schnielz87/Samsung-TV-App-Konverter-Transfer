package de.tvtransfer.convert

import java.io.File

/** Portierbarkeit eines einzelnen Bestandteils auf Samsung-TV (Tizen). */
enum class Portability(val label: String) {
    OK("übertragbar"),
    LIMITED("eingeschränkt"),
    BLOCKER("nicht portierbar"),
    INFO("Info"),
}

/** Ein Befund der Analyse, z. B. "Medien: ExoPlayer – nicht portierbar". */
data class Finding(val area: String, val title: String, val status: Portability, val detail: String)

/**
 * Schritt 2 des Konverters: untersucht eine APK im Detail (Architektur, Oberfläche, Medien, Netzwerk,
 * Schnittstellen, Abhängigkeiten) und bewertet jeden Bestandteil für Samsung-TV.
 * Rein lesend – es wird nichts verändert und nichts übertragen.
 */
object Inspector {

    private class Marker(val area: String, val needle: String, val title: String, val status: Portability, val detail: String)

    /** Typische Android-Bausteine (Klassennamen im Programmcode) und ihre Bewertung für Tizen-TV. */
    private val MARKERS = listOf(
        Marker("Medien", "Landroidx/media3/", "Media3/ExoPlayer (Android-Videoplayer)", Portability.BLOCKER,
            "Android-eigener Player. Samsung-TV nutzt einen eigenen Player (AVPlay) – die Wiedergabe müsste neu programmiert werden."),
        Marker("Medien", "Lcom/google/android/exoplayer2/", "ExoPlayer (Android-Videoplayer)", Portability.BLOCKER,
            "Android-eigener Player. Samsung-TV nutzt einen eigenen Player (AVPlay) – die Wiedergabe müsste neu programmiert werden."),
        Marker("Medien", "Lorg/videolan/libvlc/", "VLC-Bibliothek", Portability.BLOCKER, "Nativer Android-Player ohne Tizen-Entsprechung."),
        Marker("Medien", "Landroid/media/MediaPlayer;", "Android MediaPlayer", Portability.BLOCKER, "Android-Schnittstelle ohne Tizen-Entsprechung."),
        Marker("Schutz", "Landroid/media/MediaDrm;", "DRM (Widevine/MediaDrm)", Portability.BLOCKER,
            "Geschützte Wiedergabe. Wird nicht nachgebildet oder umgangen."),
        Marker("Dienste", "Lcom/google/android/gms/", "Google-Play-Dienste", Portability.BLOCKER,
            "Auf Samsung-Fernsehern gibt es keine Google-Dienste (Anmeldung, Karten, Push …)."),
        Marker("Dienste", "Lcom/google/firebase/", "Firebase", Portability.LIMITED,
            "Firebase-Funktionen der Android-Bibliothek fehlen auf dem Fernseher."),
        Marker("Dienste", "Lcom/android/billingclient/", "Google-Play-Käufe", Portability.BLOCKER,
            "In-App-Käufe über Google Play sind auf Samsung-TV nicht möglich."),
        Marker("Oberfläche", "Landroidx/compose/ui/", "Jetpack Compose (Android-Oberfläche)", Portability.BLOCKER,
            "Die Oberfläche ist Android-Programmcode und müsste für Tizen neu gebaut werden."),
        Marker("Oberfläche", "Landroidx/leanback/", "Android-TV-Oberfläche (Leanback)", Portability.BLOCKER,
            "Android-TV-Bausteine, auf Tizen nicht vorhanden."),
        Marker("Oberfläche", "Landroid/webkit/WebView;", "WebView (Webinhalte)", Portability.INFO,
            "Die App zeigt (auch) Webinhalte an."),
        Marker("Hardware", "Landroid/hardware/camera2/", "Kamera", Portability.LIMITED, "Fernseher haben keine Kamera."),
        Marker("Hardware", "Landroid/bluetooth/", "Bluetooth", Portability.LIMITED, "Für Apps auf dem Fernseher nicht verfügbar."),
        Marker("Hardware", "Landroid/location/", "Standort/GPS", Portability.LIMITED, "Fernseher haben kein GPS."),
        Marker("Hardware", "Landroid/nfc/", "NFC", Portability.LIMITED, "Auf dem Fernseher nicht vorhanden."),
    )

    /**
     * @param webRoot Ordner der Web-Inhalte in der APK (Cordova/Capacitor) oder null
     * @param webBased true, wenn die App auf Web-Inhalten beruht (Web-App im Paket oder WebView-Hülle)
     */
    fun inspectApk(file: File, names: List<String>, manifest: List<BinaryXml.Element>, webRoot: String?, webBased: Boolean = webRoot != null): List<Finding> {
        val out = ArrayList<Finding>()

        // Architektur
        val abis = names.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.distinct()
        val libs = names.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.substringAfterLast('/') }.distinct()
        val dexCount = names.count { Regex("^classes\\d*\\.dex$").matches(it) }
        out += Finding("Architektur", "Programmcode: $dexCount Android-Code-Datei(en)", if (webBased) Portability.INFO else Portability.BLOCKER,
            if (webBased) "Wird nicht übernommen – die App-Logik steckt in den Web-Inhalten."
            else "Android-Programmcode (Java/Kotlin) läuft auf Tizen nicht und kann nicht automatisch übersetzt werden.")
        if (libs.isNotEmpty()) out += Finding("Architektur", "Nativer Code (${abis.joinToString()}): ${libs.take(5).joinToString()}${if (libs.size > 5) " …" else ""}",
            Portability.BLOCKER, "Für Android-Prozessoren übersetzter Code ohne Tizen-Entsprechung.")

        // Oberfläche
        val activities = manifest.count { it.name == "activity" || it.name == "activity-alias" }
        if (activities > 0) out += Finding("Oberfläche", "$activities Android-Bildschirm(e) (Activities)",
            if (webBased) Portability.INFO else Portability.BLOCKER,
            if (webBased) "Nur Hülle um die Web-Inhalte." else "Android-Oberfläche – muss für Tizen neu gebaut werden.")
        val services = manifest.count { it.name == "service" }
        if (services > 0) out += Finding("Hintergrund", "$services Hintergrunddienst(e)", Portability.LIMITED,
            "Tizen-TV-Apps laufen nicht dauerhaft im Hintergrund.")

        // Ressourcen / Web-Inhalte
        if (webRoot != null) {
            val web = names.filter { it.startsWith(webRoot) }
            out += Finding("Ressourcen", "Web-Inhalte: ${web.size} Dateien in $webRoot", Portability.OK, "Werden in die Samsung-App übernommen.")
            cordovaPlugins(file, webRoot).forEach { p ->
                out += Finding("Abhängigkeiten", "Handy-Plugin: $p", Portability.LIMITED,
                    "Gibt es auf dem Fernseher nicht – zugehörige Funktion fällt weg (die App erhält eine Fehlermeldung statt abzustürzen).")
            }
        }
        val assetCount = names.count { it.startsWith("assets/") }
        val resCount = names.count { it.startsWith("res/") }
        out += Finding("Ressourcen", "$resCount Android-Ressourcen, $assetCount Assets", Portability.INFO,
            "Symbol wird übernommen; Android-Layouts nicht.")

        // Netzwerk & Berechtigungen
        val perms = manifest.filter { it.name == "uses-permission" }.mapNotNull { it.attrs["name"]?.substringAfterLast('.') }
        if ("INTERNET" in perms) out += Finding("Netzwerk", "Internetzugriff", Portability.OK, "Wird als Tizen-Berechtigung übernommen.")
        val hw = perms.filter { it in setOf("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "BLUETOOTH_CONNECT", "READ_CONTACTS", "CALL_PHONE", "READ_SMS") }
        if (hw.isNotEmpty()) out += Finding("Berechtigungen", "Handy-Berechtigungen: ${hw.joinToString()}", Portability.LIMITED,
            "Diese Funktionen gibt es auf dem Fernseher nicht.")

        // Schnittstellen im Programmcode
        val code = dexText(file, names)
        MARKERS.filter { code.contains(it.needle) }.distinctBy { it.title }.forEach { m ->
            val status = if (webBased && m.status == Portability.BLOCKER && m.area != "Schutz") Portability.LIMITED else m.status
            out += Finding(m.area, m.title, status, m.detail)
        }
        return out
    }

    /** Alle Code-Dateien als ein Text (für schnelle Suche nach Klassennamen). */
    private fun dexText(file: File, names: List<String>): String {
        val sb = StringBuilder()
        names.filter { Regex("^classes\\d*\\.dex$").matches(it) }.forEach { n ->
            Zip.entry(file, n)?.let { sb.append(String(it, Charsets.ISO_8859_1)) }
        }
        return sb.toString()
    }

    private fun cordovaPlugins(file: File, webRoot: String): List<String> {
        val js = Zip.entry(file, webRoot + "cordova_plugins.js")?.toString(Charsets.UTF_8) ?: return emptyList()
        return Regex("\"pluginId\"\\s*:\\s*\"([^\"]+)\"").findAll(js).map { it.groupValues[1] }.distinct().toList()
    }

    /** Konkrete Begründung aus den Befunden, z. B. für "Konvertierung nicht möglich: …". */
    fun blockerSummary(findings: List<Finding>): String =
        findings.filter { it.status == Portability.BLOCKER }.joinToString("; ") { it.title }
}
