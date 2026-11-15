package de.tvtransfer.app

import de.tvtransfer.convert.Analysis
import de.tvtransfer.convert.SourceType
import de.tvtransfer.net.DeviceKind

/** Zielplattform (Reiter in der App). */
enum class TargetPlatform(
    val tab: String,
    val title: String,
    val system: String,
    /** Endung der erzeugten Datei, null = keine Umwandlung (APK läuft direkt). */
    val fileExt: String?,
    val deviceKind: DeviceKind,
    val brands: String,
) {
    SAMSUNG("Samsung", "Samsung Smart TV", "Tizen", "wgt", DeviceKind.SAMSUNG_TV, "Samsung"),
    LG("LG", "LG Smart TV", "webOS", "ipk", DeviceKind.LG_TV, "LG"),
    ANDROID_TV("Android TV", "Android TV / Google TV", "Android", null, DeviceKind.ANDROID_STICK,
        "Philips, Sony, TCL, Xiaomi, Sharp, Grundig … sowie Fire TV Stick, Google TV Streamer, Android-TV-Boxen");

    /** Kann die Quelle für diese Plattform aufbereitet werden? Liefert (ja/nein, Begründung). */
    fun support(a: Analysis): Pair<Boolean, String> = when (this) {
        SAMSUNG -> a.canConvert to a.verdict
        LG -> when (a.type) {
            SourceType.WEB_APP_IN_APK, SourceType.WEBVIEW_WRAPPER, SourceType.WEBSITE ->
                a.canConvert to "Umwandelbar für LG webOS: " + a.verdict.substringAfter(": ", a.verdict)
            SourceType.TIZEN_PACKAGE -> true to "Die Samsung-App wird für LG webOS umgepackt. Samsung-eigene Schnittstellen (z. B. Samsung-Videoplayer) gibt es auf LG nicht – solche Funktionen laufen dort nicht."
            SourceType.SAMSUNG_VERSION -> false to "Für ${a.counterpart?.name ?: "diese App"} gibt es bisher nur eine Samsung-Fassung, noch keine LG-Fassung. Diese müsste eigens programmiert werden (die Samsung-Fassung nutzt den Samsung-Videoplayer)."
            else -> false to a.verdict.replace("Samsung-Fernseher (Tizen)", "LG-Fernseher (webOS)").replace("Samsung", "LG")
        }
        ANDROID_TV -> if (a.apkParts.isNotEmpty() && a.type != SourceType.WEBSITE && a.type != SourceType.TIZEN_PACKAGE)
            true to "Läuft auf Android TV / Google TV ohne Umwandlung – die APK wird unverändert übertragen."
        else false to "Auf Android-TV-Geräte lassen sich nur APK-Dateien bzw. Android-Apps übertragen."
    }
}
