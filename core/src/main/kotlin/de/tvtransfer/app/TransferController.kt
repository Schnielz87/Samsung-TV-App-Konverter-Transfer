package de.tvtransfer.app

import de.tvtransfer.bridge.AdbKey
import de.tvtransfer.convert.Analysis
import de.tvtransfer.convert.Analyzer
import de.tvtransfer.convert.SourceType
import de.tvtransfer.convert.WgtBuilder
import de.tvtransfer.convert.WgtOptions
import de.tvtransfer.net.DeviceKind
import de.tvtransfer.net.Discovery
import de.tvtransfer.net.FoundDevice
import de.tvtransfer.net.Http
import de.tvtransfer.net.Json
import de.tvtransfer.sign.CertStore
import de.tvtransfer.sign.LoginReceiver
import de.tvtransfer.sign.SamsungAccess
import de.tvtransfer.sign.SamsungCertificates
import de.tvtransfer.sign.WidgetSigner
import de.tvtransfer.stick.AndroidStick
import de.tvtransfer.tv.SamsungTv
import de.tvtransfer.tv.TvModel
import de.tvtransfer.update.ReleaseInfo
import de.tvtransfer.update.UpdateChecker
import kotlinx.coroutines.delay
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Was die jeweilige Plattform (Windows / Android) bereitstellt. */
interface Platform {
    /** Dauerhafter, privater Programmordner (Zertifikate, Schlüssel, Einstellungen). */
    val dataDir: File
    /** Zwischenspeicher (heruntergeladene/entpackte Dateien). */
    val cacheDir: File
    /** Name dieses Geräts (erscheint z. B. beim "Debugging zulassen?"-Dialog am Stick). */
    val deviceLabel: String
    fun openBrowser(url: String)

    /** Installierte Version, z. B. "1.0.12" (0 als letzte Zahl = Entwicklerversion ohne Update-Prüfung). */
    val appVersion: String
    /** Datei im Release, die diese Plattform zum Aktualisieren braucht (APK bzw. Windows-Setup). */
    val updateAssetName: String
    /** Startet die Installation der heruntergeladenen Datei. Gibt bei Problemen einen Hinweis zurück, sonst null. */
    fun installUpdate(file: File): String?
}

enum class Signing(val label: String, val description: String) {
    SAMSUNG(
        "Samsung-Zertifikat",
        "Einmalig mit Ihrem kostenlosen Samsung-Konto anmelden. Danach installiert das Programm jede umgewandelte App mit einem Klick.",
    ),
    TIZENBREW(
        "TizenBrew",
        "Installiert einmalig den offenen App-Starter TizenBrew (nutzt ebenfalls das Samsung-Zertifikat). " +
            "Danach lassen sich Community-Apps (z. B. TizenTube) direkt am Fernseher hinzufügen – ohne erneutes Signieren.",
    ),
}

data class UiState(
    val analysis: Analysis? = null,
    val appName: String = "",
    val chosenUrl: String? = null,
    val model: TvModel = TvModel.DEFAULT,
    val modelAutoDetected: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val selected: FoundDevice? = null,
    val scanning: Boolean = false,
    val scanProgress: Float = 0f,
    val busy: Boolean = false,
    val progress: Float? = null,
    val status: String = "Bereit. Ziehen Sie eine Datei hierher oder wählen Sie eine App.",
    val error: String? = null,
    val success: String? = null,
    val log: List<String> = emptyList(),
    val certInfo: String = "",
    val hasCertificate: Boolean = false,
    val waitingForLogin: Boolean = false,
    val waitingForStickConfirm: Boolean = false,
    val signing: Signing = Signing.SAMSUNG,
    val ownAddresses: List<String> = emptyList(),
    val lastWgt: File? = null,
    val appVersion: String = "",
    val update: ReleaseInfo? = null,
    val updateStatus: String? = null,
    val updateProgress: Float? = null,
    val checkingUpdate: Boolean = false,
    val autoUpdateCheck: Boolean = true,
)

class TransferController(private val platform: Platform) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val certs = CertStore(File(platform.dataDir, "zertifikat"))
    private val settingsFile = File(platform.dataDir, "einstellungen.json")
    private var login: LoginReceiver? = null
    private var job: Job? = null
    private val updater = UpdateChecker()
    private val updateFile = File(platform.dataDir, "update.properties")
    private val updateProps: Properties by lazy { Properties().apply { if (updateFile.exists()) runCatching { updateFile.inputStream().use { load(it) } } } }
    private val currentBuild: Int get() = UpdateChecker.buildOf(platform.appVersion) ?: 0

    init {
        loadSettings()
        refreshCertInfo()
        _state.update { it.copy(ownAddresses = Discovery.localAddresses(), appVersion = platform.appVersion, autoUpdateCheck = updateProps.getProperty("auto") != "false") }
        startAutoUpdateCheck()
    }

    // ------------------------------------------------------------------ Updates (wie PowerIPTV/Portiva)


    private fun saveUpdateProps() = runCatching { updateFile.parentFile.mkdirs(); updateFile.outputStream().use { updateProps.store(it, null) } }

    /** Beim Start und danach stündlich nachsehen, ob 24 Stunden seit der letzten Prüfung vergangen sind. */
    private fun startAutoUpdateCheck() {
        if (currentBuild <= 0) return // Entwicklerversion
        scope.launch {
            while (true) {
                val last = updateProps.getProperty("last")?.toLongOrNull() ?: 0L
                if (_state.value.autoUpdateCheck && System.currentTimeMillis() - last > UpdateChecker.INTERVAL) checkForUpdates(silent = true)
                delay(3600_000L)
            }
        }
    }

    fun setAutoUpdateCheck(on: Boolean) {
        updateProps.setProperty("auto", on.toString()); saveUpdateProps()
        _state.update { it.copy(autoUpdateCheck = on) }
    }

    /** Prüft auf eine neuere Version. Mit [installIfNew] wird sie gleich geladen und installiert. */
    fun checkForUpdates(silent: Boolean = false, installIfNew: Boolean = false) {
        if (_state.value.checkingUpdate) return
        _state.update { it.copy(checkingUpdate = true, updateStatus = if (silent) it.updateStatus else "Suche nach Updates …") }
        scope.launch {
            try {
                val r = updater.latest()
                updateProps.setProperty("last", System.currentTimeMillis().toString()); saveUpdateProps()
                val newer = r.takeIf { it.build > currentBuild && it.assets.containsKey(platform.updateAssetName) }
                val dismissed = (updateProps.getProperty("dismissed")?.toIntOrNull() ?: 0) >= (newer?.build ?: Int.MAX_VALUE)
                _state.update {
                    it.copy(
                        update = if (silent && dismissed) null else newer,
                        updateStatus = if (newer != null) "Neue Version ${newer.version} verfügbar." else "Sie haben die neueste Version (${platform.appVersion}).",
                    )
                }
                if (newer != null) log("Update verfügbar: Version ${newer.version}")
                if (installIfNew && newer != null) downloadAndInstall(newer)
            } catch (e: Exception) {
                if (!silent) _state.update { it.copy(updateStatus = e.message ?: "Update-Prüfung fehlgeschlagen.") }
            } finally {
                _state.update { it.copy(checkingUpdate = false) }
            }
        }
    }

    /** Ein Klick: prüfen bzw. die gefundene Version laden und installieren. */
    fun updateNow() {
        val r = _state.value.update
        if (r == null) checkForUpdates(silent = false, installIfNew = true) else scope.launch { downloadAndInstall(r) }
    }

    fun dismissUpdate() {
        _state.value.update?.let { updateProps.setProperty("dismissed", it.build.toString()); saveUpdateProps() }
        _state.update { it.copy(update = null) }
    }

    private fun downloadAndInstall(r: ReleaseInfo) {
        if (_state.value.updateProgress != null) return
        val url = r.assets[platform.updateAssetName] ?: return
        _state.update { it.copy(updateProgress = 0f, updateStatus = "Lade Version ${r.version} …") }
        try {
            val dir = File(platform.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name != platform.updateAssetName) it.delete() }
            val file = updater.download(url, File(dir, "${r.version}-${platform.updateAssetName}")) { done, total ->
                if (total > 0) _state.update { it.copy(updateProgress = done.toFloat() / total) }
            }
            val problem = platform.installUpdate(file)
            _state.update { it.copy(updateStatus = problem ?: "Installation wird gestartet …") }
        } catch (e: Exception) {
            _state.update { it.copy(updateStatus = e.message ?: "Download fehlgeschlagen.") }
        } finally {
            _state.update { it.copy(updateProgress = null) }
        }
    }

    // ------------------------------------------------------------------ Quelle

    fun openFile(file: File, labelHint: String? = null, parts: List<File>? = null) = run("Analysiere ${file.name} …") {
        val a = Analyzer.analyze(file, labelHint, parts)
        setAnalysis(a)
    }

    fun openWebsite(url: String, name: String? = null) {
        if (url.isBlank()) return
        setAnalysis(Analyzer.website(url, name))
    }

    private fun setAnalysis(a: Analysis) {
        _state.update {
            it.copy(
                analysis = a, appName = a.displayName, chosenUrl = a.urls.firstOrNull(),
                error = null, success = null,
                status = if (a.canConvert) "Bereit zum Übertragen." else a.verdict.substringBefore(". ") + ".",
            )
        }
        log("Datei: ${a.displayName} – ${a.framework}")
    }

    fun setAppName(name: String) = _state.update { it.copy(appName = name) }
    fun chooseUrl(url: String) = _state.update { it.copy(chosenUrl = url) }
    fun setModel(m: TvModel) = _state.update { it.copy(model = m, modelAutoDetected = false) }.also { saveSettings() }
    fun setSigning(s: Signing) = _state.update { it.copy(signing = s) }
    fun clearMessages() = _state.update { it.copy(error = null, success = null) }

    // ------------------------------------------------------------------ Geräte

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, scanProgress = 0f, ownAddresses = Discovery.localAddresses()) }
        scope.launch {
            try {
                val found = Discovery.scan { done, total -> _state.update { s -> s.copy(scanProgress = done.toFloat() / total) } }
                val merged = (found + _state.value.devices).distinctBy { it.host to it.kind }
                _state.update { it.copy(devices = merged, status = if (found.isEmpty()) "Keine Geräte gefunden. IP-Adresse manuell eingeben?" else "${found.size} Gerät(e) gefunden.") }
                if (_state.value.selected == null) merged.firstOrNull()?.let { select(it) }
                saveSettings()
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun addHost(ip: String) = run("Prüfe $ip …") {
        val host = ip.trim()
        val found = Discovery.probe(host, 2000)
        if (found.isEmpty()) {
            fail("Unter $host wurde kein Samsung-Fernseher und kein Android-Stick gefunden. Ist das Gerät eingeschaltet und im selben WLAN?")
        } else {
            _state.update { s -> s.copy(devices = (found + s.devices).distinctBy { it.host to it.kind }) }
            select(found.first())
            saveSettings()
        }
    }

    fun select(d: FoundDevice) {
        val detected = d.tvInfo?.detectedModel
        _state.update {
            it.copy(
                selected = d,
                model = detected ?: it.model,
                modelAutoDetected = detected != null,
            )
        }
        if (d.kind == DeviceKind.SAMSUNG_TV) {
            val info = d.tvInfo
            val own = Discovery.localAddresses()
            when {
                info != null && !info.developerMode -> warn("Am Fernseher ist der Entwicklermodus noch nicht aktiv. Anleitung: Apps öffnen → 1 2 3 4 5 eingeben → Entwicklermodus EIN → IP-Adresse dieses Geräts (${own.firstOrNull() ?: "siehe Einstellungen"}) eintragen → Fernseher neu starten.")
                info?.developerIp != null && info.developerIp !in own && own.isNotEmpty() ->
                    warn("Am Fernseher ist als Entwickler-IP ${info.developerIp} eingetragen, dieses Gerät hat aber ${own.joinToString()}. Bitte am Fernseher (Apps → 12345) die IP-Adresse ändern und den Fernseher neu starten.")
            }
        }
        saveSettings()
    }

    // ------------------------------------------------------------------ Übertragen

    /** Hauptfunktion: umwandeln (falls nötig) und auf das gewählte Gerät bringen. */
    fun transfer() {
        val s = _state.value
        val device = s.selected ?: return fail("Bitte zuerst ein Zielgerät wählen (Suchen oder IP eingeben).")
        if (s.signing == Signing.TIZENBREW && device.kind == DeviceKind.SAMSUNG_TV && s.analysis == null) return installTizenBrew()
        val a = s.analysis ?: return fail("Bitte zuerst eine Datei, eine App oder eine Webadresse wählen.")
        when (device.kind) {
            DeviceKind.SAMSUNG_TV -> {
                if (!a.canConvert) return fail(a.verdict)
                run("Wandle um …") { transferToSamsung(device, a) }
            }
            DeviceKind.ANDROID_STICK -> {
                if (a.type == SourceType.WEBSITE || a.type == SourceType.TIZEN_PACKAGE || a.apkParts.isEmpty())
                    return fail("Auf einen Android-Stick lassen sich nur APK-Dateien übertragen.")
                run("Verbinde mit dem Stick …") { transferToStick(device, a) }
            }
        }
    }

    /** Erzeugt nur die .wgt-Datei (zum Weitergeben oder für USB). */
    fun exportWgt(targetDir: File) = run("Erzeuge Samsung-App …") {
        val s = _state.value
        val a = s.analysis ?: return@run fail("Keine Quelle gewählt.")
        if (!a.canConvert) return@run fail(a.verdict)
        val built = WgtBuilder.build(a, WgtOptions(s.appName, a.versionName ?: "1.0.0", s.model, s.chosenUrl))
        val f = WgtBuilder.save(built, targetDir, s.appName)
        _state.update { it.copy(lastWgt = f) }
        succeed("Gespeichert: ${f.absolutePath} (noch nicht signiert – wird beim Installieren automatisch signiert).")
    }

    private fun transferToSamsung(device: FoundDevice, a: Analysis) {
        val s = _state.value
        status("Erzeuge Samsung-App …")
        val built = WgtBuilder.build(a, WgtOptions(s.appName.ifBlank { a.displayName }, a.versionName ?: "1.0.0", s.model, s.chosenUrl))
        val isWgt = a.source?.name?.lowercase()?.endsWith(".tpk") != true
        log("Paket ${built.packageId} (${built.wgt.size / 1024} KB) erstellt.")
        if (s.model.note.isNotBlank() && s.model.chromium < 60 && a.type != SourceType.TIZEN_PACKAGE) log("Hinweis: ${s.model.note}")

        status("Verbinde mit dem Fernseher …")
        var conn = SamsungTv.connect(device.host)
        try {
            val duid = SamsungTv.duid(conn)
            log("Geräte-ID des Fernsehers: $duid")
            if (!certs.covers(duid)) {
                conn.close()
                val access = obtainLogin() ?: return fail("Samsung-Anmeldung abgebrochen. Ohne Zertifikat lässt der Fernseher keine Installation zu.")
                status("Erstelle Zertifikat …")
                certs.create(access, duid, "TV-App-Transfer", ::log)
                refreshCertInfo()
                conn = SamsungTv.connect(device.host)
            }
            status("Signiere …")
            val signed = WidgetSigner.sign(built.wgt, certs.author(), certs.distributor())
            status("Installiere auf ${device.name} …")
            val res = SamsungTv.install(conn, signed, isWgt, ::log) { sent, total -> progress(sent, total) }
            if (res.success) {
                runCatching { if (built.appId.isNotEmpty()) SamsungTv.launch(conn, built.appId) }
                succeed("\"${s.appName.ifBlank { a.displayName }}\" ist auf dem Fernseher installiert. Sie finden die App unter Apps (ganz unten bzw. in der App-Leiste).")
            } else {
                fail(res.message + if (res.rawOutput.isNotBlank()) "\n\nAntwort des Fernsehers: ${res.rawOutput.trim().takeLast(400)}" else "")
            }
        } finally {
            conn.close()
        }
    }

    private fun transferToStick(device: FoundDevice, a: Analysis) {
        val key = AdbKey.loadOrCreate(File(platform.dataDir, "adbkey"), "tvtransfer@${platform.deviceLabel}")
        val conn = AndroidStick.connect(device.host, key) {
            _state.update { it.copy(waitingForStickConfirm = true) }
            status("Bitte am Fernseher \"USB-Debugging zulassen\" bestätigen (Haken bei \"Immer zulassen\" setzen).")
        }
        _state.update { it.copy(waitingForStickConfirm = false) }
        conn.use {
            log("Verbunden mit ${AndroidStick.deviceName(conn)}")
            val (ok, msg) = AndroidStick.install(conn, a.apkParts, ::log) { sent, total -> progress(sent, total) }
            if (ok) {
                a.packageName?.let { p -> runCatching { AndroidStick.launch(conn, p) } }
                succeed("\"${a.displayName}\" ist auf dem Stick installiert.")
            } else fail(msg)
        }
    }

    // ------------------------------------------------------------------ TizenBrew

    fun installTizenBrew() = run("Lade TizenBrew herunter …") {
        val device = _state.value.selected?.takeIf { it.kind == DeviceKind.SAMSUNG_TV }
            ?: return@run fail("Bitte zuerst einen Samsung-Fernseher wählen.")
        val rel = Http.get("https://api.github.com/repos/reisxd/TizenBrew/releases/latest", 20_000, mapOf("Accept" to "application/vnd.github+json"))
        if (!rel.ok) return@run fail("TizenBrew konnte nicht abgerufen werden (HTTP ${rel.code}).")
        @Suppress("UNCHECKED_CAST")
        val assets = (Json.parse(rel.text) as Map<String, Any?>)["assets"] as? List<Map<String, Any?>> ?: emptyList()
        val wgt = assets.firstOrNull { it["name"].toString().endsWith(".wgt") }
            ?: return@run fail("In der aktuellen TizenBrew-Version wurde keine .wgt-Datei gefunden.")
        val dl = Http.get(wgt["browser_download_url"].toString(), 120_000)
        if (!dl.ok) return@run fail("Download fehlgeschlagen (HTTP ${dl.code}).")
        val f = File(platform.cacheDir, wgt["name"].toString()).apply { parentFile.mkdirs(); writeBytes(dl.body) }
        log("TizenBrew ${rel.text.let { Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: ""} geladen.")
        val a = Analyzer.analyze(f)
        _state.update { it.copy(analysis = a, appName = "TizenBrew") }
        transferToSamsung(device, a)
        log("TizenBrew: Am Fernseher öffnen und dort über \"Module\" z. B. @foxreis/tizentube hinzufügen.")
    }

    // ------------------------------------------------------------------ Samsung-Anmeldung

    private fun obtainLogin(): SamsungAccess? {
        login?.cancel()
        val receiver = LoginReceiver()
        login = receiver
        _state.update { it.copy(waitingForLogin = true) }
        status("Bitte im Browser mit Ihrem Samsung-Konto anmelden …")
        log("Öffne Samsung-Anmeldung im Browser. Ihr Passwort geben Sie nur auf der Samsung-Seite ein.")
        return try {
            var access: SamsungAccess? = null
            val t = Thread { access = runCatching { receiver.await() }.getOrNull() }
            t.start()
            Thread.sleep(300)
            platform.openBrowser(SamsungCertificates.LOGIN_URL)
            t.join()
            access?.also { log("Samsung-Anmeldung erhalten (${it.email.ifBlank { "Konto" }}).") }
        } finally {
            _state.update { it.copy(waitingForLogin = false) }
            login = null
        }
    }

    /** Falls die Weiterleitung nicht klappt: Text der Samsung-Seite manuell übernehmen. */
    fun submitLoginText(text: String) {
        val access = LoginReceiver.parseAccess(text) ?: return fail("Der eingefügte Text enthält keine gültigen Anmeldedaten.")
        val receiver = login ?: return fail("Es läuft gerade keine Samsung-Anmeldung. Bitte zuerst \"Übertragen\" starten.")
        receiver.deliver(access)
    }

    fun cancelLogin() { login?.cancel() }

    fun deleteCertificate() {
        certs.delete()
        refreshCertInfo()
        log("Zertifikat gelöscht.")
    }

    private fun refreshCertInfo() {
        val info = if (certs.hasCertificate) {
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(certs.createdAt))
            "Samsung-Zertifikat vom $date für ${certs.duids.size} Fernseher (${certs.email ?: "–"})"
        } else "Noch kein Samsung-Zertifikat – wird bei der ersten Installation automatisch erstellt."
        _state.update { it.copy(certInfo = info, hasCertificate = certs.hasCertificate) }
    }

    // ------------------------------------------------------------------ Einstellungen

    private fun saveSettings() = runCatching {
        val s = _state.value
        settingsFile.parentFile.mkdirs()
        settingsFile.writeText(
            Json.write(
                mapOf(
                    "model" to s.model.name,
                    "selected" to s.selected?.let { mapOf("host" to it.host, "kind" to it.kind.name, "name" to it.name) },
                    "devices" to s.devices.map { mapOf("host" to it.host, "kind" to it.kind.name, "name" to it.name) },
                ),
            ),
        )
    }

    private fun loadSettings() = runCatching {
        if (!settingsFile.exists()) return@runCatching
        @Suppress("UNCHECKED_CAST")
        val m = Json.parse(settingsFile.readText()) as Map<String, Any?>
        val model = runCatching { TvModel.valueOf(m["model"].toString()) }.getOrDefault(TvModel.DEFAULT)
        fun dev(o: Any?): FoundDevice? = (o as? Map<*, *>)?.let {
            FoundDevice(it["host"].toString(), DeviceKind.valueOf(it["kind"].toString()), it["name"].toString())
        }
        val devices = (m["devices"] as? List<*>)?.mapNotNull { runCatching { dev(it) }.getOrNull() } ?: emptyList()
        _state.update { it.copy(model = model, devices = devices, selected = runCatching { dev(m["selected"]) }.getOrNull()) }
    }

    // ------------------------------------------------------------------ Hilfen

    private fun run(startStatus: String, block: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, success = null, progress = null, status = startStatus) }
        job = scope.launch {
            try {
                block()
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            } finally {
                _state.update { it.copy(busy = false, progress = null, waitingForLogin = false, waitingForStickConfirm = false) }
            }
        }
    }

    private fun status(s: String) { _state.update { it.copy(status = s) }; log(s) }
    private fun warn(s: String) { _state.update { it.copy(error = s) }; log("Hinweis: $s") }
    private fun fail(s: String) { _state.update { it.copy(error = s, success = null, status = "Nicht erfolgreich.") }; log("Fehler: $s") }
    private fun succeed(s: String) { _state.update { it.copy(success = s, error = null, status = "Fertig.") }; log(s) }
    private fun progress(sent: Long, total: Long) { if (total > 0) _state.update { it.copy(progress = sent.toFloat() / total) } }

    fun log(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
        _state.update { it.copy(log = (it.log + "$t  $line").takeLast(300)) }
    }
}
