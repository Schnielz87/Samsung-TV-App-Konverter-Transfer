package de.tvtransfer.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.tvtransfer.app.Platform
import de.tvtransfer.app.TransferController
import de.tvtransfer.ui.AppTheme
import de.tvtransfer.ui.Brand
import de.tvtransfer.ui.MainScreen
import de.tvtransfer.ui.UiHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TvTransferApp : Application() {
    val controller: TransferController by lazy { TransferController(AndroidPlatform(this)) }
}

class AndroidPlatform(private val ctx: Context) : Platform {
    override val dataDir: File = File(ctx.filesDir, "daten").apply { mkdirs() }
    override val cacheDir: File = File(ctx.cacheDir, "dateien").apply { mkdirs() }
    override val deviceLabel: String = Build.MODEL ?: "Handy"
    override fun openBrowser(url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override val appVersion: String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "1.0.0"

    override val updateAssetName: String = de.tvtransfer.update.UpdateChecker.APK_NAME

    /** Öffnet den Android-Installer (die neue APK ist mit demselben Schlüssel signiert – Daten bleiben erhalten). */
    override fun installUpdate(file: File): String? {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return "Bitte \"Unbekannte Apps installieren\" für TV-App-Transfer erlauben, zurückkehren und erneut \"Jetzt aktualisieren\" tippen."
        }
        return runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            // Direkt an den Android-Installer – ohne "Öffnen mit"-Auswahl (diese App nimmt selbst APKs an).
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            val installer = ctx.packageManager.queryIntentActivities(intent, 0)
                .firstOrNull { it.activityInfo.packageName != ctx.packageName }
            installer?.let { intent.setClassName(it.activityInfo.packageName, it.activityInfo.name) }
            ctx.startActivity(intent)
            null
        }.getOrElse { "Installer konnte nicht geöffnet werden: ${it.message}" }
    }
}

data class InstalledApp(val label: String, val pkg: String, val files: List<File>, val system: Boolean, val icon: ImageBitmap?)

class MainActivity : ComponentActivity() {

    private val controller get() = (application as TvTransferApp).controller
    private var showAppPicker by mutableStateOf(false)

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val host = object : UiHost {
            override fun pickFile() = openDocument.launch(arrayOf("*/*"))
            override val pickInstalledApp: (() -> Unit) = { showAppPicker = true }
            override fun exportWgt() {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                controller.exportWgt(dir)
            }
            override val dropHint = "Tippen, um eine Datei zu wählen"
        }
        setContent {
            AppTheme {
                BoxWithConstraints {
                    MainScreen(controller, host, wide = maxWidth > 900.dp)
                }
                if (showAppPicker) AppPicker(
                    onDismiss = { showAppPicker = false },
                    onPick = { app ->
                        showAppPicker = false
                        controller.openFile(app.files.first(), app.label, app.files)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> importUri(uri)
                    text != null -> Regex("https?://\\S+").find(text)?.let { controller.openWebsite(it.value) }
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { importUri(it) }
        }
    }

    /** Kopiert die gewählte Datei in den Zwischenspeicher (Android erlaubt sonst keinen Dateizugriff). */
    private fun importUri(uri: Uri) {
        Thread {
            runCatching {
                var name = "datei.apk"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
                if (uri.scheme == "file") uri.lastPathSegment?.let { name = it }
                val target = File(File(cacheDir, "import").apply { mkdirs() }, name.replace('/', '_'))
                contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                controller.openFile(target)
            }.onFailure { controller.log("Datei konnte nicht gelesen werden: ${it.message}") }
        }.start()
    }

    @Composable
    private fun AppPicker(onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
        var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
        var filter by remember { mutableStateOf("") }
        var withSystem by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps() } }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Installierte App wählen") },
            text = {
                Column {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true, label = { Text("Suchen") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withSystem, onCheckedChange = { withSystem = it })
                        Text("Auch vorinstallierte System-Apps", fontSize = 13.sp)
                    }
                    val list = apps
                    if (list == null) Text("Lade Apps …")
                    else LazyColumn(Modifier.height(420.dp)) {
                        items(list.filter { (withSystem || !it.system) && (filter.isBlank() || it.label.contains(filter, true) || it.pkg.contains(filter, true)) }) { app ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp),
                            ) {
                                app.icon?.let { Image(it, null, Modifier.size(36.dp)) } ?: Spacer(Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(app.label)
                                    Text(app.pkg + if (app.files.size > 1) " · ${app.files.size} Teile" else "", color = Brand.muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        )
    }

    private fun loadApps(): List<InstalledApp> {
        val pm = packageManager
        @Suppress("DEPRECATION")
        val infos = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return infos.filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { ai ->
                runCatching {
                    val files = listOf(File(ai.sourceDir)) + (ai.splitSourceDirs?.map { File(it) } ?: emptyList())
                    InstalledApp(
                        label = pm.getApplicationLabel(ai).toString(),
                        pkg = ai.packageName,
                        files = files,
                        system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                        icon = runCatching { iconBitmap(pm.getApplicationIcon(ai)) }.getOrNull(),
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun iconBitmap(d: android.graphics.drawable.Drawable): ImageBitmap {
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, 96, 96)
        d.draw(c)
        return bmp.asImageBitmap()
    }
}
