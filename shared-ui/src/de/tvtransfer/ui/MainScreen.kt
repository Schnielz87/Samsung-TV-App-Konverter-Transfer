package de.tvtransfer.ui

// Gemeinsame Oberfläche für Windows (Compose Desktop) und Android (Jetpack Compose).
// Nur androidx.compose.* verwenden – Gerätespezifisches läuft über [UiHost].

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.tvtransfer.app.Signing
import de.tvtransfer.app.TransferController
import de.tvtransfer.app.UiState
import de.tvtransfer.convert.SourceType
import de.tvtransfer.net.DeviceKind
import de.tvtransfer.tv.TvModel

/** Was nur die jeweilige Plattform kann. */
interface UiHost {
    /** Datei auswählen (.apk/.wgt/.tpk …). */
    fun pickFile()
    /** Installierte App vom Handy wählen – nur Android, sonst null. */
    val pickInstalledApp: (() -> Unit)?
    /** Ordner für "Als .wgt speichern". */
    fun exportWgt()
    /** Hinweis im Ablagebereich (z. B. "Datei hierher ziehen"). */
    val dropHint: String
}

object Brand {
    val bg = Color(0xFF0B1220)
    val card = Color(0xFF131C2E)
    val border = Color(0xFF24324D)
    val accent = Color(0xFF4DA3FF)
    val ok = Color(0xFF34D399)
    val warn = Color(0xFFFBBF24)
    val bad = Color(0xFFF87171)
    val text = Color(0xFFE2E8F0)
    val muted = Color(0xFF94A3B8)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Brand.accent, onPrimary = Color(0xFF0B1220), background = Brand.bg, surface = Brand.card,
            onSurface = Brand.text, onBackground = Brand.text, secondary = Brand.ok, error = Brand.bad,
        ),
    ) {
        Surface(color = Brand.bg, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
fun MainScreen(controller: TransferController, host: UiHost, wide: Boolean, dropHighlight: Boolean = false) {
    val s by controller.state.collectAsState()
    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header()
                UpdateBanner(s, controller)
                SourceCard(s, controller, host, dropHighlight)
                TargetCard(s, controller)
                ActionCard(s, controller, host)
                UpdateCard(s, controller)
            }
            Column(Modifier.weight(1f)) { LogCard(s, Modifier.fillMaxSize()) }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header()
            UpdateBanner(s, controller)
            SourceCard(s, controller, host, dropHighlight)
            TargetCard(s, controller)
            ActionCard(s, controller, host)
            LogCard(s, Modifier.fillMaxWidth().height(260.dp))
            UpdateCard(s, controller)
        }
    }
    LoginDialog(s, controller)
}

@Composable
private fun Header() {
    Column {
        Text("TV-App-Transfer", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Brand.text)
        Text("Apps auf Samsung-Fernseher bringen – umwandeln, signieren, installieren. Oder APKs direkt auf einen Android-TV-Stick.", color = Brand.muted, fontSize = 14.sp)
    }
}

@Composable
private fun Section(title: String, step: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Brand.card),
        border = BorderStroke(1.dp, Brand.border),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(Brand.accent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text("$step", color = Brand.bg, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(s: UiState, c: TransferController, host: UiHost, dropHighlight: Boolean) {
    Section("Was soll auf den Fernseher?", 1) {
        Box(
            Modifier.fillMaxWidth()
                .background(if (dropHighlight) Brand.accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable(enabled = !s.busy) { host.pickFile() }
                .padding(4.dp),
        ) {
            Surface(
                color = Color.Transparent,
                border = BorderStroke(2.dp, if (dropHighlight) Brand.accent else Brand.border),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(host.dropHint, color = Brand.text, fontWeight = FontWeight.Medium)
                    Text(".apk · .apks/.xapk · .wgt · .tpk", color = Brand.muted, fontSize = 12.sp)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = host::pickFile, enabled = !s.busy) { Text("Datei wählen") }
            host.pickInstalledApp?.let { pick -> OutlinedButton(onClick = pick, enabled = !s.busy) { Text("Installierte App vom Handy") } }
        }
        var url by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text("…oder Webadresse (z. B. Web-Player, Mediathek)") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { c.openWebsite(url) }, enabled = url.isNotBlank() && !s.busy) { Text("Übernehmen") }
        }

        val a = s.analysis ?: return@Section
        HorizontalDivider(color = Brand.border)
        val color = when {
            a.canConvert -> Brand.ok
            a.type == SourceType.NATIVE_ANDROID -> Brand.warn
            else -> Brand.bad
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    a.canConvert -> "Für Samsung umwandelbar"
                    a.type == SourceType.NATIVE_ANDROID -> "Nicht für Samsung umwandelbar – aber für einen Android-Stick geeignet"
                    else -> "Nicht unterstützt"
                },
                color = color, fontWeight = FontWeight.SemiBold,
            )
        }
        Text("${a.framework}${a.packageName?.let { " · $it" } ?: ""}${a.versionName?.let { " · Version $it" } ?: ""}", color = Brand.muted, fontSize = 12.sp)
        Text(a.verdict, color = Brand.text, fontSize = 14.sp)
        a.notes.forEach { Text("• $it", color = Brand.muted, fontSize = 13.sp) }
        if (a.canConvert) {
            OutlinedTextField(value = s.appName, onValueChange = c::setAppName, singleLine = true, label = { Text("Name auf dem Fernseher") }, modifier = Modifier.fillMaxWidth())
        }
        if (a.urls.size > 1 || (a.type == SourceType.WEBVIEW_WRAPPER && a.urls.isNotEmpty())) {
            Text("Welche Adresse soll die App öffnen?", color = Brand.text, fontSize = 14.sp)
            a.urls.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { c.chooseUrl(u) }) {
                    RadioButton(selected = s.chosenUrl == u, onClick = { c.chooseUrl(u) })
                    Text(u, fontSize = 13.sp, color = Brand.text)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(s: UiState, c: TransferController) {
    Section("Wohin?", 2) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = c::scan, enabled = !s.scanning && !s.busy) { Text(if (s.scanning) "Suche läuft …" else "Im WLAN suchen") }
        }
        if (s.scanning) LinearProgressIndicator(progress = { s.scanProgress }, modifier = Modifier.fillMaxWidth())
        var ip by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = ip, onValueChange = { ip = it.trim() }, singleLine = true, label = { Text("IP-Adresse manuell (z. B. 192.168.178.40)") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { c.addHost(ip) }, enabled = ip.isNotBlank() && !s.busy) { Text("Hinzufügen") }
        }
        if (s.devices.isEmpty()) Text("Noch keine Geräte. Fernseher einschalten und \"Im WLAN suchen\" drücken.", color = Brand.muted, fontSize = 13.sp)
        s.devices.forEach { d ->
            val selected = s.selected?.host == d.host && s.selected?.kind == d.kind
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .background(if (selected) Brand.accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { c.select(d) }.padding(6.dp),
            ) {
                RadioButton(selected = selected, onClick = { c.select(d) })
                Column {
                    Text(d.title, color = Brand.text, fontWeight = FontWeight.Medium)
                    val sub = when (d.kind) {
                        DeviceKind.SAMSUNG_TV -> listOfNotNull(
                            d.tvInfo?.modelName,
                            d.tvInfo?.let { if (it.developerMode) "Entwicklermodus an" else "Entwicklermodus AUS" },
                            if (d.sdbOpen) "bereit" else null,
                        ).joinToString(" · ")
                        DeviceKind.ANDROID_STICK -> "Fire TV / Google TV / Android TV – APKs direkt"
                    }
                    if (sub.isNotEmpty()) Text(sub, color = Brand.muted, fontSize = 12.sp)
                }
            }
        }

        s.deviceWarning?.takeIf { s.selected?.kind == DeviceKind.SAMSUNG_TV }?.let { Message(it, Brand.warn) }
        if (s.selected?.kind == DeviceKind.SAMSUNG_TV) {
            HorizontalDivider(color = Brand.border)
            ModelPicker(s, c)
            Text("Signierweg", color = Brand.text, fontWeight = FontWeight.Medium)
            Signing.entries.forEach { sg ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable { c.setSigning(sg) }) {
                    RadioButton(selected = s.signing == sg, onClick = { c.setSigning(sg) })
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(sg.label, color = Brand.text)
                        Text(sg.description, color = Brand.muted, fontSize = 12.sp)
                    }
                }
            }
            Text(s.certInfo, color = if (s.hasCertificate) Brand.ok else Brand.muted, fontSize = 12.sp)
            DevModeHelp(s)
        } else if (s.selected?.kind == DeviceKind.ANDROID_STICK) {
            HorizontalDivider(color = Brand.border)
            Text(
                "Am Stick einmalig: Einstellungen → Mein Fire TV (bzw. Info) → 7× auf den Gerätenamen tippen → Entwickleroptionen → \"ADB-Debugging\" EIN. " +
                    "Beim ersten Übertragen erscheint am Fernseher \"USB-Debugging zulassen?\" – \"Immer zulassen\" anhaken und OK.",
                color = Brand.muted, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ModelPicker(s: UiState, c: TransferController) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text("Fernseher-Modelljahr", color = Brand.text, fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(s.model.label + if (s.modelAutoDetected) "  (erkannt)" else "  ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                TvModel.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.label) }, onClick = { c.setModel(m); open = false })
                }
            }
        }
        Text(s.model.note, color = if (s.model.supported && s.model.chromium >= 60) Brand.muted else Brand.warn, fontSize = 12.sp)
    }
}

@Composable
private fun DevModeHelp(s: UiState) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Anleitung Entwicklermodus ausblenden" else "Anleitung: Entwicklermodus am Fernseher einschalten (einmalig)") }
    if (open) {
        val ip = s.ownAddresses.firstOrNull() ?: "(IP dieses Geräts)"
        listOf(
            "Fernseher und dieses Gerät müssen im selben WLAN/Netz sein.",
            "Am Fernseher \"Apps\" öffnen (bei neueren Modellen: Home → Apps).",
            "Auf der Fernbedienung nacheinander 1 2 3 4 5 drücken (bei Fernbedienung ohne Zahlen: Ziffernfeld über die 123-Taste).",
            "Entwicklermodus auf EIN stellen und als \"Host PC IP\" diese Adresse eintragen: $ip",
            "OK drücken und den Fernseher komplett neu starten (Ein/Aus-Taste ca. 3 Sekunden gedrückt halten, bis das Samsung-Logo erscheint).",
            "Hier \"Im WLAN suchen\" drücken. Fertig – das ist nur einmal nötig.",
        ).forEachIndexed { i, t -> Text("${i + 1}. $t", color = Brand.text, fontSize = 13.sp) }
        if (s.ownAddresses.size > 1) Text("Dieses Gerät hat mehrere Adressen: ${s.ownAddresses.joinToString()} – nehmen Sie die aus dem Heimnetz.", color = Brand.muted, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionCard(s: UiState, c: TransferController, host: UiHost) {
    Section("Übertragen", 3) {
        val target = s.selected
        val label = when {
            target == null -> "Übertragen"
            target.kind == DeviceKind.ANDROID_STICK -> "Auf den Android-Stick übertragen"
            s.signing == Signing.TIZENBREW && s.analysis == null -> "TizenBrew auf den Fernseher installieren"
            else -> "Auf den Samsung-Fernseher übertragen"
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = c::transfer, enabled = !s.busy && target != null,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.ok, contentColor = Brand.bg),
            ) { Text(label, fontWeight = FontWeight.Bold) }
            if (target?.kind == DeviceKind.SAMSUNG_TV && s.signing == Signing.TIZENBREW && s.analysis != null && !s.busy && !s.tizenBrewInstalled)
                OutlinedButton(onClick = c::installTizenBrew, enabled = !s.busy) { Text("TizenBrew installieren") }
            if (s.analysis?.canConvert == true && s.analysis?.type != SourceType.TIZEN_PACKAGE)
                OutlinedButton(onClick = host::exportWgt, enabled = !s.busy) { Text("Nur als .wgt speichern") }
        }
        if (s.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(s.status, color = Brand.text)
            }
            s.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        } else {
            Text(s.status, color = Brand.muted, fontSize = 13.sp)
        }
        if (s.waitingForStickConfirm) Message("Bitte jetzt am Fernseher \"USB-Debugging zulassen\" bestätigen.", Brand.warn)
        s.error?.let { Message(it, Brand.bad) }
        s.success?.let { Message(it, Brand.ok) }
    }
}

/** Hinweis oben, sobald eine neue Version bereitsteht (wie in PowerIPTV/Portiva). */
@Composable
private fun UpdateBanner(s: UiState, c: TransferController) {
    val u = s.update ?: return
    Surface(color = Brand.accent.copy(alpha = 0.14f), border = BorderStroke(1.dp, Brand.accent), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Neue Version ${u.version} verfügbar", color = Brand.text, fontWeight = FontWeight.Bold)
            Text("Installiert: ${s.appVersion}. Ihre Zertifikate, Geräte und Einstellungen bleiben beim Update erhalten.", color = Brand.muted, fontSize = 13.sp)
            UpdateProgress(s)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = c::updateNow, enabled = s.updateProgress == null) { Text("Jetzt aktualisieren") }
                TextButton(onClick = c::dismissUpdate) { Text("Später") }
            }
        }
    }
}

@Composable
private fun UpdateProgress(s: UiState) {
    s.updateProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    s.updateStatus?.let { Text(it, color = Brand.muted, fontSize = 12.sp) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateCard(s: UiState, c: TransferController) {
    Card(colors = CardDefaults.cardColors(containerColor = Brand.card), border = BorderStroke(1.dp, Brand.border), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Version ${s.appVersion}", color = Brand.text, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.checkForUpdates() }, enabled = !s.checkingUpdate && s.updateProgress == null) {
                    Text(if (s.checkingUpdate) "Suche …" else "Nach Updates suchen")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { c.setAutoUpdateCheck(!s.autoUpdateCheck) }) {
                    androidx.compose.material3.Checkbox(checked = s.autoUpdateCheck, onCheckedChange = c::setAutoUpdateCheck)
                    Text("Automatisch prüfen (alle 24 Stunden)", color = Brand.text, fontSize = 13.sp)
                }
            }
            if (s.update == null) UpdateProgress(s)
        }
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), border = BorderStroke(1.dp, color), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        SelectionContainer { Text(text, color = Brand.text, modifier = Modifier.padding(12.dp), fontSize = 14.sp) }
    }
}

@Composable
private fun LogCard(s: UiState, modifier: Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F1A)), border = BorderStroke(1.dp, Brand.border), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text("Protokoll", color = Brand.muted, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    s.log.forEach { Text(it, color = Brand.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}

@Composable
private fun LoginDialog(s: UiState, c: TransferController) {
    if (!s.waitingForLogin) return
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Samsung-Anmeldung") },
        text = {
            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Im Browser hat sich die offizielle Samsung-Anmeldung geöffnet. Melden Sie sich dort mit Ihrem (kostenlosen) Samsung-Konto an. " +
                    "Danach geht es hier automatisch weiter.")
                Text("Ihr Passwort geben Sie nur bei Samsung ein – dieses Programm sieht es nie.", color = Brand.muted, fontSize = 12.sp)
                Text("Falls die Samsung-Seite stattdessen einen Text wie {\"access_token\": …} anzeigt, diesen hier einfügen:", fontSize = 13.sp)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(100.dp))
            }
        },
        confirmButton = { Button(onClick = { c.submitLoginText(text) }, enabled = text.isNotBlank()) { Text("Text übernehmen") } },
        dismissButton = { TextButton(onClick = c::cancelLogin) { Text("Abbrechen") } },
    )
}
