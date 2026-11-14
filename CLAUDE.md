# TV-App-Transfer – Hinweise für Claude

- Zwei Varianten, gleicher Stand: Windows (`desktop/`) und Android (`android/`). Logik nur in `core/` (reines Kotlin, keine
  `android.*`-Importe), Oberfläche in `shared-ui/` (nur `androidx.compose.*`, Plattformdinge über `UiHost`/`Platform`).
- Ehrlich bleiben: Native Android-Apps sind nicht in Tizen umwandelbar – nie etwas anderes versprechen; Weg = Android-Stick.
- Keine Passwörter abfragen: Samsung-Login nur über die offizielle Seite + lokalen Empfänger (`LoginReceiver`).
- Signatur-Änderungen immer mit `SignerTest` absichern (XML-DSig-Prüfung + feste #prop-Prüfsummen).
- Prüfen: `gradle :core:test :desktop:compileKotlin`. Android baut nur mit SDK (CI).
- README (Kundenanleitung, Sie-Form) bei jeder Funktionsänderung mitpflegen.
- Versionen & Updates: `1.0.<Laufnummer>` aus `.github/workflows/build.yml` (nie umbenennen), Tag `tv-app-transfer-v…`,
  Releases in `Schnielz87/Portiva-Downloads` (Secret `DOWNLOADS_TOKEN`), sonst im eigenen Repo. Feste Dateinamen in
  `update/UpdateChecker.kt` nicht ändern. APK-Schlüssel `android/signing/sideload.jks` nie austauschen (sonst keine Updates).
