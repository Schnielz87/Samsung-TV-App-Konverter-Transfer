# TV-App-Transfer – Hinweise für Claude

- Zwei Varianten, gleicher Stand: Windows (`desktop/`) und Android (`android/`). Logik nur in `core/` (reines Kotlin, keine
  `android.*`-Importe), Oberfläche in `shared-ui/` (nur `androidx.compose.*`, Plattformdinge über `UiHost`/`Platform`).
- Ehrlich bleiben: Native Android-Apps sind nicht in Tizen umwandelbar – nie etwas anderes versprechen; Weg = Android-Stick.
- Keine Passwörter abfragen: Samsung-Login nur über die offizielle Seite + lokalen Empfänger (`LoginReceiver`).
- Signatur-Änderungen immer mit `SignerTest` absichern (XML-DSig-Prüfung + feste #prop-Prüfsummen).
- Prüfen: `gradle :core:test :desktop:compileKotlin`. Android baut nur mit SDK (CI).
- README (Kundenanleitung, Sie-Form) bei jeder Funktionsänderung mitpflegen.
