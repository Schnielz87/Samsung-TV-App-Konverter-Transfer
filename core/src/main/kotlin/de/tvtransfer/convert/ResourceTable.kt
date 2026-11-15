package de.tvtransfer.convert

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Liest die kompilierte Ressourcentabelle einer APK (resources.arsc): Texte, Farben, Bild- und Layout-Dateien.
 * Nur lesend; es werden die Standardwerte (ohne Sprach-/Ländervariante) bevorzugt.
 */
class ResourceTable private constructor() {

    /** Ein Eintrag: Typ (string, color, layout, drawable …), Name und Wert. */
    data class Entry(val id: Int, val type: String, val name: String, val dataType: Int, val data: Int, val text: String?, val defaultConfig: Boolean, val density: Int)

    private val byId = HashMap<Int, MutableList<Entry>>()

    val entries: List<Entry> get() = byId.values.flatten()

    fun name(id: Int): String? = byId[id]?.firstOrNull()?.let { "${it.type}/${it.name}" }

    /** Text einer @string-Ressource (Standardsprache bevorzugt). */
    fun string(id: Int): String? = best(id)?.let { e -> if (e.dataType == TYPE_STRING) e.text else if (e.dataType == TYPE_REFERENCE) string(e.data) else null }

    /** Farbe als CSS (#RRGGBB oder rgba(...)). */
    fun color(id: Int): String? = best(id)?.let { e ->
        when (e.dataType) {
            in 0x1c..0x1f -> css(e.data)
            TYPE_REFERENCE -> color(e.data)
            else -> null
        }
    }

    /** Farbe als ARGB-Zahl. */
    fun colorArgb(id: Int): Int? = best(id)?.let { e ->
        when (e.dataType) {
            in 0x1c..0x1f -> e.data
            TYPE_REFERENCE -> colorArgb(e.data)
            else -> null
        }
    }

    /** Pfade der Dateien hinter einer Ressource (z. B. res/drawable-xxhdpi/logo.png), dichteste zuerst. */
    fun files(id: Int): List<String> = byId[id].orEmpty()
        .filter { it.dataType == TYPE_STRING && it.text?.startsWith("res/") == true }
        .sortedByDescending { it.density }
        .mapNotNull { it.text }

    fun idByName(type: String, name: String): Int? = byId.entries.firstOrNull { (_, l) -> l.any { it.type == type && it.name == name } }?.key

    private fun best(id: Int): Entry? = byId[id]?.let { l -> l.firstOrNull { it.defaultConfig } ?: l.firstOrNull() }

    companion object {
        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03

        fun css(argb: Int): String {
            val a = (argb ushr 24) and 0xFF
            val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
            return if (a == 0xFF) String.format("#%02X%02X%02X", r, g, b) else String.format("rgba(%d,%d,%d,%.2f)", r, g, b, a / 255.0)
        }

        /** Leere Tabelle, falls die APK keine (lesbare) resources.arsc hat. */
        fun empty() = ResourceTable()

        fun parse(data: ByteArray): ResourceTable {
            val t = ResourceTable()
            runCatching { t.read(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)) }
            return t
        }
    }

    private fun read(bb: ByteBuffer) {
        if ((bb.getShort(0).toInt() and 0xFFFF) != 0x0002) return
        val headerSize = bb.getShort(2).toInt() and 0xFFFF
        var pos = headerSize
        var global: List<String> = emptyList()
        while (pos + 8 <= bb.limit()) {
            val type = bb.getShort(pos).toInt() and 0xFFFF
            val size = bb.getInt(pos + 4)
            if (size <= 0) break
            when (type) {
                0x0001 -> global = StringPool.read(bb, pos)
                0x0200 -> readPackage(bb, pos, size, global)
            }
            pos += size
        }
    }

    private fun readPackage(bb: ByteBuffer, start: Int, size: Int, global: List<String>) {
        val pkgId = bb.getInt(start + 8)
        val headerSize = bb.getShort(start + 2).toInt() and 0xFFFF
        val typeStringsOff = bb.getInt(start + 8 + 4 + 256)
        val keyStringsOff = bb.getInt(start + 8 + 4 + 256 + 8)
        val typeNames = StringPool.read(bb, start + typeStringsOff)
        val keyNames = StringPool.read(bb, start + keyStringsOff)
        var pos = start + headerSize
        val end = start + size
        while (pos + 8 <= end) {
            val type = bb.getShort(pos).toInt() and 0xFFFF
            val chunkSize = bb.getInt(pos + 4)
            if (chunkSize <= 0) break
            if (type == 0x0201) runCatching { readType(bb, pos, pkgId, typeNames, keyNames, global) }
            pos += chunkSize
        }
    }

    private fun readType(bb: ByteBuffer, start: Int, pkgId: Int, typeNames: List<String>, keyNames: List<String>, global: List<String>) {
        val headerSize = bb.getShort(start + 2).toInt() and 0xFFFF
        val typeId = bb.get(start + 8).toInt() and 0xFF
        val flags = bb.get(start + 9).toInt() and 0xFF
        val entryCount = bb.getInt(start + 12)
        val entriesStart = bb.getInt(start + 16)
        val cfg = start + 20
        val language = bb.getShort(cfg + 8).toInt()
        val country = bb.getShort(cfg + 10).toInt()
        val density = bb.getShort(cfg + 14).toInt() and 0xFFFF
        val isDefault = language == 0 && country == 0
        val typeName = typeNames.getOrNull(typeId - 1) ?: return
        val offsetsStart = start + headerSize

        val indexed = ArrayList<Pair<Int, Int>>() // Eintragsnummer -> Offset
        when {
            flags and 0x01 != 0 -> for (i in 0 until entryCount) { // sparse
                val idx = bb.getShort(offsetsStart + i * 4).toInt() and 0xFFFF
                val off = (bb.getShort(offsetsStart + i * 4 + 2).toInt() and 0xFFFF) * 4
                indexed += idx to off
            }
            flags and 0x02 != 0 -> for (i in 0 until entryCount) { // 16-Bit-Offsets
                val o = bb.getShort(offsetsStart + i * 2).toInt() and 0xFFFF
                if (o != 0xFFFF) indexed += i to o * 4
            }
            else -> for (i in 0 until entryCount) {
                val o = bb.getInt(offsetsStart + i * 4)
                if (o != -1) indexed += i to o
            }
        }
        for ((idx, off) in indexed) {
            val e = start + entriesStart + off
            val eFlags = bb.getShort(e + 2).toInt() and 0xFFFF
            val id = (pkgId shl 24) or (typeId shl 16) or idx
            val keyName: String
            val dataType: Int
            val data: Int
            if (eFlags and 0x0008 != 0) { // kompakter Eintrag
                keyName = keyNames.getOrNull(bb.getShort(e).toInt() and 0xFFFF) ?: continue
                dataType = (eFlags ushr 8) and 0xFF
                data = bb.getInt(e + 4)
            } else {
                keyName = keyNames.getOrNull(bb.getInt(e + 4)) ?: continue
                if (eFlags and 0x0001 != 0) { // komplexer Eintrag (Stil, Liste …) – nur Name merken
                    byId.getOrPut(id) { ArrayList() } += Entry(id, typeName, keyName, -1, 0, null, isDefault, density)
                    continue
                }
                val v = e + (bb.getShort(e).toInt() and 0xFFFF)
                dataType = bb.get(v + 3).toInt() and 0xFF
                data = bb.getInt(v + 4)
            }
            val text = if (dataType == TYPE_STRING) global.getOrNull(data) else null
            byId.getOrPut(id) { ArrayList() } += Entry(id, typeName, keyName, dataType, data, text, isDefault, density)
        }
    }
}

/** Zeichenketten-Tabelle in Android-Binärformaten (UTF-8 oder UTF-16). */
internal object StringPool {
    fun read(bb: ByteBuffer, start: Int): List<String> {
        val headerSize = bb.getShort(start + 2).toInt() and 0xFFFF
        val count = bb.getInt(start + 8)
        val flags = bb.getInt(start + 16)
        val stringsStart = bb.getInt(start + 20)
        val utf8 = flags and 0x100 != 0
        val list = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = start + stringsStart + bb.getInt(start + headerSize + i * 4)
            list += runCatching { if (utf8) utf8At(bb, off) else utf16At(bb, off) }.getOrDefault("")
        }
        return list
    }

    private fun utf8At(bb: ByteBuffer, off: Int): String {
        var p = off
        p += if (bb.get(p).toInt() and 0x80 != 0) 2 else 1
        var len = bb.get(p).toInt() and 0xFF
        if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (bb.get(p + 1).toInt() and 0xFF); p += 2 } else p += 1
        val bytes = ByteArray(len) { bb.get(p + it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun utf16At(bb: ByteBuffer, off: Int): String {
        var p = off
        var len = bb.getShort(p).toInt() and 0xFFFF
        if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (bb.getShort(p + 2).toInt() and 0xFFFF); p += 4 } else p += 2
        return String(CharArray(len) { bb.getChar(p + it * 2) })
    }
}
