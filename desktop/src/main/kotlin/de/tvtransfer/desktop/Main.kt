package de.tvtransfer.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.DisposableEffect
import de.tvtransfer.app.Platform
import de.tvtransfer.app.TransferController
import de.tvtransfer.ui.AppTheme
import de.tvtransfer.ui.MainScreen
import de.tvtransfer.ui.UiHost
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.net.URI
import javax.swing.JFileChooser

class DesktopPlatform : Platform {
    private val base: File = run {
        val appData = System.getenv("APPDATA")
        if (appData != null) File(appData, "TV-App-Transfer") else File(System.getProperty("user.home"), ".tv-app-transfer")
    }
    override val dataDir: File = base.apply { mkdirs() }
    override val cacheDir: File = File(base, "cache").apply { mkdirs() }
    override val deviceLabel: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("PC")

    override fun openBrowser(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure {
            // Fallback für Systeme ohne Desktop-Unterstützung
            runCatching { Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url)) }
        }
    }
}

fun main() = application {
    val controller = remember { TransferController(DesktopPlatform()) }
    val state = rememberWindowState(size = DpSize(1180.dp, 820.dp))
    var dropHighlight by remember { mutableStateOf(false) }

    Window(onCloseRequest = ::exitApplication, title = "TV-App-Transfer – Apps auf Samsung-TV & Android-Stick", state = state) {
        val frame = window
        DisposableEffect(Unit) {
            frame.contentPane.dropTarget = DropTarget(frame.contentPane, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                override fun dragEnter(dtde: DropTargetDragEvent) { dropHighlight = true }
                override fun dragExit(dte: DropTargetEvent) { dropHighlight = false }
                override fun drop(e: DropTargetDropEvent) {
                    dropHighlight = false
                    e.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST")
                    val files = runCatching { e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull()
                    val text = runCatching { e.transferable.getTransferData(DataFlavor.stringFlavor) as String }.getOrNull()
                    when {
                        !files.isNullOrEmpty() -> controller.openFile(files.first())
                        text != null && text.trim().startsWith("http") -> controller.openWebsite(text.trim())
                    }
                    e.dropComplete(true)
                }
            }, true)
            onDispose { frame.contentPane.dropTarget = null }
        }

        val host = remember {
            object : UiHost {
                override fun pickFile() {
                    val d = FileDialog(frame as Frame, "App-Datei wählen", FileDialog.LOAD)
                    d.setFilenameFilter { _, name -> name.lowercase().let { n -> listOf(".apk", ".apks", ".xapk", ".wgt", ".tpk").any { n.endsWith(it) } } }
                    d.isVisible = true
                    val f = d.files.firstOrNull() ?: return
                    controller.openFile(f)
                }
                override val pickInstalledApp: (() -> Unit)? = null
                override fun exportWgt() {
                    val ch = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Ordner für die .wgt-Datei wählen"
                    }
                    if (ch.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) controller.exportWgt(ch.selectedFile)
                }
                override val dropHint = "Datei hierher ziehen oder klicken"
            }
        }

        AppTheme {
            BoxWithConstraints {
                MainScreen(controller, host, wide = maxWidth > 900.dp, dropHighlight = dropHighlight)
            }
        }
    }
}
