# Rechtliche Leitplanken (verbindliche Produktanforderungen)

TV-App-Transfer ist ein **neutrales Deployment- und Kompatibilitätswerkzeug für vom Nutzer rechtmäßig bereitgestellte
Anwendungen auf unterstützten Samsung-TV-Geräten**. Diese Regeln gelten für Architektur, Quellcode, Oberfläche,
Fehlermeldungen, Dokumentation und Vermarktung.

## Grundprinzip

- Der Anbieter stellt das Werkzeug bereit, aber **keine** zu konvertierenden Apps Dritter.
- Der Nutzer wählt die Quelldatei selbst aus und muss die erforderlichen Rechte besitzen.
- Das Werkzeug umgeht **keine** technischen Schutzmaßnahmen.
- Die Vermarktung darf Rechtsverletzungen weder fördern noch als Vorteil darstellen.

## Regeln und Umsetzung

| # | Regel | Umsetzung im Code |
|---|---|---|
| 1 | Nutzer stellt die Datei bereit. Keine App-Datenbank fremder Apps, keine automatische Beschaffung, keine Downloadquellen oder Suchfunktionen für fremde Apps. | Dateiauswahl, installierte Apps des Nutzers, Webadresse. Der frühere TizenBrew-Download wurde entfernt. Einzige Ausnahme sind **eigene** Apps des Anbieters in `convert/TizenCounterparts.kt` (derzeit PowerIPTV). |
| 2 | Verarbeitung lokal. Keine Uploads fremder Apps. Zwischendateien werden gelöscht. | Die gesamte Verarbeitung läuft auf dem Gerät. `TransferController.cleanupTemp()` räumt beim Start, beim Öffnen einer neuen Datei und nach jedem Vorgang auf. |
| 3 | Keine Umgehung von DRM, Lizenzprüfungen, Kopierschutz, Zugangskontrollen, Signatur- oder Sicherheitsprüfungen. | `Analyzer.protectionMarker()` erkennt Lizenz- und Integritätsschutz (Play-Lizenzprüfung, Play Integrity/pairip, Packer) und bricht die Umwandlung ab (`SourceType.PROTECTED`). Auf Android-TV-Geräte wird nur die **unveränderte** APK übertragen. |
| 4 | Offizielle Samsung-Verfahren, keine Exploits. | Entwicklermodus, sdb, offizielle Samsung-Zertifizierungsstelle mit dem eigenen Konto des Nutzers. Berechtigungsstufe „Public“. |
| 5 | Rechte-Bestätigung vor der ersten Verarbeitung, Teil der Nutzungsbedingungen. | Dialog `TermsDialog` / `Legal.RIGHTS_CONFIRMATION`, gespeichert mit Fassung und Zeitpunkt (`rechtliches.properties`). Neue Fassung: `Legal.TERMS_VERSION` erhöhen. |
| 6 | Positionierung ohne Werbeversprechen wie „jede App“, „Sperren umgehen“ oder „kostenlos“. Keine Garantie. | `Legal.POSITIONING` im Kopf der App, im README und in den Release-Texten. |
| 7 | Keine Markenverwechslung, Markenhinweis. | Name „TV-App-Transfer“. „Samsung“ nur beschreibend. `Legal.TRADEMARK_NOTICE` steht in der App und im README. |
| 8 | Fremde Software nur so weit verändern, wie es technisch nötig ist. Keine Rechtehinweise entfernen. | Ergänzt werden nur `config.xml`, `tv-bridge.js`, der Ersatz für `cordova.js` und das Symbol. Lizenz- und Copyright-Dateien bleiben erhalten. |
| 9 | Keine Übertragung von App-Namen oder Dateien, keine Telemetrie ohne Transparenz. | Es gibt keine Telemetrie. Externe Verbindungen sind in `legal/datenschutz.txt` dokumentiert. |
| 11 | Keine falschen Erfolgsmeldungen | `Inspector` (Analysebericht mit konkreten Blockern), `Validator` (Prüfung vor dem Signieren). Rote Befunde führen zum Abbruch, gelbe stehen in der Erfolgsmeldung. |
| 10 | Vor jeder neuen Funktion prüfen: Kompatibilität oder Umgehung? Im Zweifel **LEGAL_REVIEW_REQUIRED**. | Siehe Liste unten. |

## LEGAL_REVIEW_REQUIRED (vor Vermarktung anwaltlich prüfen lassen)

1. **Neu-Signieren fremder Tizen-Pakete (.wgt/.tpk):** Die vorhandene Signatur wird durch das Entwicklerzertifikat des
   Nutzers ersetzt. So sieht es Samsungs Entwicklerverfahren vor. Ob das bei fremden Paketen „berechtigt“ ist, hängt
   von den Rechten des Nutzers ab, die er per Rechte-Bestätigung zusichert.
2. **Ausweich-DNS (Cloudflare/Google)**, wenn ein Netzfilter die Samsung-Zertifizierungsstelle sperrt. Das betrifft
   keine Samsung-Schutzmaßnahme, sondern den Filter des Nutzers. Es ist in der Datenschutzerklärung beschrieben.
3. **Webadressen aus WebView-Apps:** Das Programm liest die Adresse aus der App und verpackt nur die Webseite, nicht den
   App-Code. Zu prüfen ist, ob das Verpacken fremder Webseiten als TV-App Marken- oder Nutzungsbedingungen der
   Seitenbetreiber berührt.
4. **Repository- und Produktname:** Der Repo-Name „Samsung-TV-App-Konverter-Transfer“ enthält die Marke prominent. Für
   die Vermarktung sollte ein neutraler Name verwendet werden (wie „TV-App-Transfer“).
5. **Höhere Samsung-Berechtigungsstufe („Partner“):** Sie ist bewusst nicht aktiv und darf nur nach Prüfung verwendet werden.
6. **Anbieterangaben, Impressum und Datenschutzerklärung:** Die Felder in `core/.../app/Legal.kt` (`AppInfo`) müssen
   ausgefüllt und die Texte vor Veröffentlichung geprüft werden.
