package de.tvtransfer.convert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Zip {
    /** Liest alle Dateien eines ZIP-Archivs (Reihenfolge bleibt erhalten, Ordner werden übersprungen). */
    fun read(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) out[safeName(e.name) ?: continue] = zin.readBytes()
            }
        }
        return out
    }

    /** Listet nur die Namen (schnell, ohne alles zu entpacken). */
    fun names(file: File): List<String> = ZipFile(file).use { z -> z.entries().toList().filter { !it.isDirectory }.map { it.name } }

    fun entry(file: File, name: String): ByteArray? = ZipFile(file).use { z -> z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }

    /** Alle Dateien unterhalb von [prefix] (Pfad relativ dazu). */
    fun subtree(file: File, prefix: String): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(file).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !e.name.startsWith(prefix)) continue
                val rel = safeName(e.name.removePrefix(prefix)) ?: continue
                if (rel.isEmpty()) continue
                out[rel] = z.getInputStream(e).use { it.readBytes() }
            }
        }
        return out
    }

    fun write(files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zout ->
            for ((name, data) in files) {
                val e = ZipEntry(name)
                e.time = 0L + 315532800000L // fester Zeitstempel (1980) → gleiche Eingabe ergibt gleiches Paket
                zout.putNextEntry(e)
                zout.write(data)
                zout.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Schützt vor Pfaden wie "../../x" in manipulierten Archiven. */
    private fun safeName(name: String): String? {
        val n = name.replace('\\', '/').trimStart('/')
        if (n.split('/').any { it == ".." }) return null
        return n
    }
}
