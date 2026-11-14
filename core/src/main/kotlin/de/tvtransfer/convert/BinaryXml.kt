package de.tvtransfer.convert

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Liest die binäre AndroidManifest.xml einer APK (nur Elemente und Attribute – genug für Paketname,
 * Version, Name und TV-Kennzeichen).
 */
object BinaryXml {

    data class Element(val name: String, val attrs: Map<String, String>)

    fun parse(data: ByteArray): List<Element> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Element>()
        if (data.size < 8 || bb.getShort(0).toInt() != 0x0003) return out
        var strings: List<String> = emptyList()
        var pos = bb.getShort(2).toInt() and 0xFFFF // Kopfgröße der Datei
        while (pos + 8 <= data.size) {
            val type = bb.getShort(pos).toInt() and 0xFFFF
            val headerSize = bb.getShort(pos + 2).toInt() and 0xFFFF
            val size = bb.getInt(pos + 4)
            if (size <= 0 || pos + size > data.size) break
            when (type) {
                0x0001 -> strings = stringPool(bb, pos)
                0x0102 -> {
                    val ext = pos + headerSize
                    val nameIdx = bb.getInt(ext + 4)
                    val attrStart = bb.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = bb.getShort(ext + 10).toInt() and 0xFFFF
                    val attrCount = bb.getShort(ext + 12).toInt() and 0xFFFF
                    val attrs = LinkedHashMap<String, String>()
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val aName = strings.getOrNull(bb.getInt(a + 4)) ?: continue
                        val raw = bb.getInt(a + 8)
                        val dataType = bb.get(a + 15).toInt() and 0xFF
                        val value = bb.getInt(a + 16)
                        attrs[aName] = when {
                            raw >= 0 && raw < strings.size -> strings[raw]
                            dataType == 0x01 -> "@0x" + Integer.toHexString(value) // Verweis auf Ressource
                            dataType == 0x12 -> (value != 0).toString()
                            dataType in 0x10..0x11 -> value.toString()
                            dataType == 0x03 -> strings.getOrNull(value) ?: ""
                            else -> value.toString()
                        }
                    }
                    out += Element(strings.getOrNull(nameIdx) ?: "", attrs)
                }
            }
            pos += size
        }
        return out
    }

    private fun stringPool(bb: ByteBuffer, start: Int): List<String> {
        val count = bb.getInt(start + 8)
        val flags = bb.getInt(start + 16)
        val stringsStart = bb.getInt(start + 20)
        val utf8 = flags and 0x100 != 0
        val headerSize = bb.getShort(start + 2).toInt() and 0xFFFF
        val list = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = start + stringsStart + bb.getInt(start + headerSize + i * 4)
            list += runCatching { if (utf8) utf8At(bb, off) else utf16At(bb, off) }.getOrDefault("")
        }
        return list
    }

    private fun utf8At(bb: ByteBuffer, off: Int): String {
        var p = off
        // Länge in Zeichen (1–2 Byte), dann Länge in Bytes (1–2 Byte)
        p += if (bb.get(p).toInt() and 0x80 != 0) 2 else 1
        var len = bb.get(p).toInt() and 0xFF
        if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (bb.get(p + 1).toInt() and 0xFF); p += 2 } else p += 1
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = bb.get(p + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun utf16At(bb: ByteBuffer, off: Int): String {
        var p = off
        var len = bb.getShort(p).toInt() and 0xFFFF
        if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (bb.getShort(p + 2).toInt() and 0xFFFF); p += 4 } else p += 2
        val chars = CharArray(len) { bb.getChar(p + it * 2) }
        return String(chars)
    }
}
