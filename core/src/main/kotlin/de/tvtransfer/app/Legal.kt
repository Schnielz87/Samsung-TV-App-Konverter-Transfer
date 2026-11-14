package de.tvtransfer.app

/**
 * Anbieter- und Rechtstexte – zentral für Windows- und Android-App.
 * Anbieterangaben hier nachtragen (leer = "folgt"); die Texte unter resources/de/tvtransfer/legal/ nutzen die Platzhalter.
 * Verbindliche Produktregeln: docs/RECHTLICHE_LEITPLANKEN.md
 */
object AppInfo {
    const val PRODUCT = "TV-App-Transfer"

    // ---- Anbieter (bitte ausfüllen) ----
    const val PROVIDER = ""          // z. B. Firmenname
    const val LEGAL_FORM_OWNERS = "" // z. B. "GbR, vertreten durch …"
    const val STREET = ""
    const val CITY = ""
    const val EMAIL = ""
    const val PHONE = ""
    const val WEBSITE = ""

    val hasProvider: Boolean get() = PROVIDER.isNotBlank()

    /** Anbieterblock für Impressum/Kontakt. */
    val providerBlock: String
        get() = if (!hasProvider) "Anbieterangaben folgen." else listOf(
            PROVIDER, LEGAL_FORM_OWNERS, STREET, CITY,
            EMAIL.takeIf { it.isNotBlank() }?.let { "E-Mail: $it" },
            PHONE.takeIf { it.isNotBlank() }?.let { "Telefon: $it" },
            WEBSITE,
        ).filter { !it.isNullOrBlank() }.joinToString("\n")
}

object Legal {
    /** Bei inhaltlicher Änderung der Nutzungsbedingungen erhöhen → Nutzer bestätigen erneut. */
    const val TERMS_VERSION = "1"

    /** Positionierung – so (und nur so) wird das Produkt beschrieben. */
    const val POSITIONING =
        "Deployment- und Kompatibilitätswerkzeug für vom Nutzer rechtmäßig bereitgestellte Anwendungen auf unterstützten Samsung-TV-Geräten."

    /** Wortlaut der Rechte-Bestätigung (Bestandteil der Nutzungsbedingungen). */
    const val RIGHTS_CONFIRMATION =
        "Ich bestätige, dass ich berechtigt bin, die ausgewählte Software zu verwenden, zu verarbeiten und auf dem Zielgerät zu " +
            "installieren. Ich werde die Anwendung nicht zur Umgehung technischer Schutzmaßnahmen oder zur Verletzung von Urheber-, " +
            "Lizenz- oder sonstigen Rechten Dritter verwenden."

    const val TRADEMARK_NOTICE =
        "Samsung und Tizen sind Marken ihrer jeweiligen Rechteinhaber. Dieses Produkt ist ein unabhängiges Softwarewerkzeug und " +
            "steht in keiner Verbindung zu Samsung, sofern nicht ausdrücklich anders angegeben."

    /** Rechtstexte (Abschnitte) mit eingesetzten Anbieterangaben. */
    fun text(name: String): String {
        val raw = Legal::class.java.getResourceAsStream("/de/tvtransfer/legal/$name.txt")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return raw
            .replace("{ANBIETER}", if (AppInfo.hasProvider) AppInfo.PROVIDER else "dem Anbieter (Angaben folgen)")
            .replace("{ANBIETERBLOCK}", AppInfo.providerBlock)
            .replace("{BESTAETIGUNG}", RIGHTS_CONFIRMATION)
            .replace("{MARKE}", TRADEMARK_NOTICE)
            .replace("{POSITIONIERUNG}", POSITIONING)
    }

    val SECTIONS = listOf(
        "nutzungsbedingungen" to "Nutzungsbedingungen",
        "datenschutz" to "Datenschutz",
        "drittinhalte" to "Marken, Lizenzen & Drittinhalte",
        "impressum" to "Anbieter / Impressum",
    )
}
