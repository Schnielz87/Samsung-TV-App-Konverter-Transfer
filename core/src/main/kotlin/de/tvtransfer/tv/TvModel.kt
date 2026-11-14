package de.tvtransfer.tv

/**
 * Samsung-Modelljahre mit Tizen-Version und eingebautem Browser.
 * Die Auswahl steuert Warnhinweise (ältere Fernseher können moderne Web-Apps oft nicht darstellen).
 */
enum class TvModel(
    val year: Int,
    val tizen: String,
    val chromium: Int,
    /** Buchstabe im Modellnamen, z. B. UE55**RU**7179 → R = 2019. */
    val letter: String,
    val supported: Boolean = true,
) {
    Y2016(2016, "2.4", 38, "K", supported = false),
    Y2017(2017, "3.0", 47, "M"),
    Y2018(2018, "4.0", 56, "N"),
    Y2019(2019, "5.0", 63, "R"),
    Y2020(2020, "5.5", 69, "T"),
    Y2021(2021, "6.0", 76, "A"),
    Y2022(2022, "6.5", 85, "B"),
    Y2023(2023, "7.0", 94, "C"),
    Y2024(2024, "8.0", 108, "D"),
    Y2025(2025, "9.0", 120, "F"),
    Y2026(2026, "10.0", 128, "H");

    val label: String
        get() = "$year (Tizen $tizen)" + if (!supported) " – eingeschränkt" else ""

    /** Hinweis, der vor dem Umwandeln angezeigt wird. */
    val note: String
        get() = when {
            !supported -> "Fernseher von 2016 und älter unterstützen die Installation über den Entwicklermodus nur teilweise. Ein Versuch ist möglich, klappt aber oft nicht."
            chromium < 60 -> "Älteres Gerät (Browser-Stand Chromium $chromium): Moderne Web-Apps laufen hier eventuell nicht oder nur langsam."
            year >= 2024 -> "Neuere Geräte (ab Tizen 8) prüfen Zertifikate besonders streng. Ein Samsung-Zertifikat ist zwingend nötig."
            else -> "Gut unterstützt."
        }

    companion object {
        val DEFAULT = Y2021

        /**
         * Ermittelt das Modelljahr aus den Angaben des Fernsehers:
         *  - "model": "19_MUSEL_UHD" (die ersten zwei Ziffern sind das Jahr)
         *  - oder aus dem Modellnamen ("UE55RU7179", "GQ65QN90AAT" …)
         */
        fun detect(model: String?, modelName: String?): TvModel? {
            model?.let { m ->
                Regex("^(\\d{2})_").find(m)?.groupValues?.get(1)?.toIntOrNull()?.let { yy ->
                    fromYear(2000 + yy)?.let { return it }
                }
            }
            modelName?.let { name ->
                // Neuere QLED/Neo-QLED: GQ65QN90AAT → Buchstabe nach der Seriennummer (A = 2021)
                Regex("^[A-Z]{2}\\d{2}(Q[NA-Z]?|LS)\\d{2,3}([A-Z])").find(name)?.groupValues?.get(2)?.let { l ->
                    entries.firstOrNull { it.letter == l }?.let { return it }
                }
                // Klassisch: UE55RU7179 / UE55TU8079 / UE43AU7179 → erster Buchstabe nach der Zollzahl
                Regex("^[A-Z]{2}\\d{2}([A-Z])").find(name)?.groupValues?.get(1)?.let { l ->
                    entries.firstOrNull { it.letter == l }?.let { return it }
                }
            }
            return null
        }

        fun fromYear(year: Int): TvModel? = entries.firstOrNull { it.year == year }
            ?: if (year > Y2026.year) Y2026 else if (year in 2000 until Y2016.year) Y2016 else null
    }
}
