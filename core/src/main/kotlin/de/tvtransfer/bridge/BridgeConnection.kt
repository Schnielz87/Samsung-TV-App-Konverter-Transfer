package de.tvtransfer.bridge

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gemeinsame Umsetzung des Geräte-Protokolls von
 *  - Samsung "sdb" (Smart Development Bridge, Port 26101, Fernseher im Entwicklermodus) und
 *  - Android "adb" über WLAN (Port 5555, Fire TV / Google TV / Android-TV-Sticks).
 *
 * Beide sprechen dasselbe Paketformat (24-Byte-Kopf + Nutzdaten, Befehle CNXN/OPEN/OKAY/WRTE/CLSE/AUTH).
 * Die Verbindung arbeitet bewusst einfach und synchron: es ist immer nur ein Datenstrom gleichzeitig offen.
 */
class BridgeConnection private constructor(
    private val socket: Socket,
    private val authKey: AdbKey?,
    private val ourMaxData: Int,
) : AutoCloseable {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream().buffered()

    /** Maximale Nutzdatengröße, die die Gegenstelle annimmt (aus ihrem CNXN). */
    var remoteMaxData: Int = 4096
        private set

    /** Kennung, die die Gegenstelle beim Verbinden meldet (z. B. "device::ro.product.name=..."). */
    var banner: String = ""
        private set

    private var nextLocalId = 1

    class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray) {
        val name: String get() = commandName(command)
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val AUTH = 0x48545541

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        const val VERSION = 0x01000000

        const val SDB_PORT = 26101
        const val ADB_PORT = 5555

        fun commandName(c: Int): String {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c).array()
            return String(b, Charsets.US_ASCII)
        }

        /**
         * Verbindet sich mit einem Gerät.
         * @param authKey nur für Android (adb): Schlüssel, mit dem sich dieses Programm ausweist.
         * @param onAuthPrompt wird aufgerufen, wenn das Gerät eine Bestätigung auf dem Bildschirm verlangt.
         */
        fun connect(
            host: String,
            port: Int,
            authKey: AdbKey? = null,
            connectTimeoutMs: Int = 5000,
            authWaitMs: Int = 60_000,
            onAuthPrompt: () -> Unit = {},
            /** Größte Paketgröße, die wir ankündigen und selbst senden (Samsung: 4096). */
            maxData: Int = if (port == SDB_PORT) 4096 else 256 * 1024,
        ): BridgeConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: IOException) {
                socket.close()
                throw BridgeException.Unreachable(host, port, e)
            }
            val conn = BridgeConnection(socket, authKey, maxData)
            try {
                conn.handshake(connectTimeoutMs.coerceAtLeast(8000), authWaitMs, onAuthPrompt)
            } catch (e: Exception) {
                conn.close()
                throw e
            }
            return conn
        }
    }

    // ------------------------------------------------------------------ Pakete

    fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xFF)
        head.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command.inv())
        output.write(head.array())
        if (data.isNotEmpty()) output.write(data)
        output.flush()
    }

    fun receive(timeoutMs: Int): Message {
        socket.soTimeout = timeoutMs
        val head = ByteArray(24)
        try {
            input.readFully(head)
        } catch (e: SocketTimeoutException) {
            throw BridgeException.Timeout("Keine Antwort vom Gerät (Zeitüberschreitung).")
        } catch (e: IOException) {
            throw BridgeException.Closed("Das Gerät hat die Verbindung beendet.", e)
        }
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val a0 = bb.int
        val a1 = bb.int
        val len = bb.int
        bb.int // Prüfsumme – wird von neueren Geräten nicht mehr gesetzt, daher nicht prüfen
        val magic = bb.int
        if (magic != cmd.inv()) throw BridgeException.Protocol("Ungültiges Paket vom Gerät (${commandName(cmd)}).")
        if (len < 0 || len > 16 * 1024 * 1024) throw BridgeException.Protocol("Ungültige Paketgröße ($len).")
        val data = ByteArray(len)
        if (len > 0) input.readFully(data)
        return Message(cmd, a0, a1, data)
    }

    private fun handshake(timeoutMs: Int, authWaitMs: Int, onAuthPrompt: () -> Unit) {
        send(CNXN, VERSION, ourMaxData, "host::\u0000".toByteArray(Charsets.US_ASCII))
        var sentSignature = false
        var sentPublicKey = false
        while (true) {
            val m = receive(if (sentPublicKey) authWaitMs else timeoutMs)
            when (m.command) {
                CNXN -> {
                    remoteMaxData = if (m.arg1 > 0) m.arg1 else 4096
                    banner = String(m.data, Charsets.UTF_8).trimEnd('\u0000')
                    return
                }
                AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) continue
                    val key = authKey ?: throw BridgeException.AuthRequired()
                    if (!sentSignature) {
                        send(AUTH, AUTH_SIGNATURE, 0, key.signToken(m.data))
                        sentSignature = true
                    } else if (!sentPublicKey) {
                        send(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyForDevice())
                        sentPublicKey = true
                        onAuthPrompt()
                    } else {
                        throw BridgeException.AuthRejected()
                    }
                }
                else -> { /* ignorieren */ }
            }
        }
    }

    // ------------------------------------------------------------------ Datenströme

    inner class Stream internal constructor(val localId: Int, val remoteId: Int) : AutoCloseable {
        private val pending = ByteArrayOutputStream()
        var closed = false
            private set

        /** Verarbeitet ein eingehendes Paket für diesen Strom. */
        internal fun accept(m: Message) {
            when (m.command) {
                WRTE -> {
                    pending.write(m.data)
                    send(OKAY, localId, remoteId)
                }
                CLSE -> closed = true
                else -> {}
            }
        }

        fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
            var pos = off
            val end = off + len
            while (pos < end) {
                if (closed) throw BridgeException.Closed("Das Gerät hat den Datenstrom geschlossen.")
                // Nie größer als beide Seiten erlauben – Samsung-Fernseher kündigen teils mehr an,
                // trennen aber bei Paketen über 4096 Byte die Verbindung.
                val n = minOf(remoteMaxData, ourMaxData, end - pos)
                send(WRTE, localId, remoteId, data.copyOfRange(pos, pos + n))
                pos += n
                // auf Bestätigung warten (Flusskontrolle)
                while (true) {
                    val m = receive(60_000)
                    if (m.arg1 != localId && m.command != CLSE) continue
                    if (m.command == OKAY) break
                    accept(m)
                    if (closed) throw BridgeException.Closed("Das Gerät hat den Datenstrom während der Übertragung geschlossen.")
                }
            }
        }

        /** Liest, was bisher angekommen ist; wartet höchstens [timeoutMs] auf neue Daten. Leeres Ergebnis + [closed] = Ende. */
        fun read(timeoutMs: Int): ByteArray {
            if (pending.size() == 0 && !closed) {
                val m = receive(timeoutMs)
                if (m.arg1 == localId || m.command == CLSE) accept(m)
            }
            val out = pending.toByteArray()
            pending.reset()
            return out
        }

        /** Liest genau [n] Bytes. */
        fun readExact(n: Int, timeoutMs: Int): ByteArray {
            val out = ByteArrayOutputStream()
            while (out.size() < n) {
                if (pending.size() == 0) {
                    if (closed) throw BridgeException.Closed("Datenstrom unerwartet beendet.")
                    val m = receive(timeoutMs)
                    if (m.arg1 == localId || m.command == CLSE) accept(m)
                    continue
                }
                val buf = pending.toByteArray()
                pending.reset()
                val take = minOf(n - out.size(), buf.size)
                out.write(buf, 0, take)
                if (take < buf.size) pending.write(buf, take, buf.size - take)
            }
            return out.toByteArray()
        }

        override fun close() {
            if (!closed) {
                try { send(CLSE, localId, remoteId) } catch (_: IOException) {}
                closed = true
            }
        }
    }

    /** Öffnet einen Dienst auf dem Gerät, z. B. "shell:ls" oder "sync:". */
    fun open(service: String, timeoutMs: Int = 15_000): Stream {
        val local = nextLocalId++
        send(OPEN, local, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
        while (true) {
            val m = receive(timeoutMs)
            if (m.arg1 != local) continue
            when (m.command) {
                OKAY -> return Stream(local, m.arg0)
                CLSE -> throw BridgeException.Protocol("Das Gerät hat den Dienst \"${service.substringBefore(':')}\" abgelehnt.")
            }
        }
    }

    /**
     * Führt einen Befehl aus und liefert die Ausgabe.
     * @param stopWhen beendet das Lesen vorzeitig, wenn die bisherige Ausgabe die Bedingung erfüllt
     *                 (manche Samsung-Befehle schließen den Strom nicht selbst).
     */
    fun shell(
        command: String,
        totalTimeoutMs: Long = 60_000,
        onOutput: (String) -> Unit = {},
        stopWhen: (String) -> Boolean = { false },
    ): String {
        val stream = open("shell:$command")
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        stream.use {
            while (!stream.closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val chunk = try {
                    stream.read(left.coerceAtMost(30_000).toInt())
                } catch (e: BridgeException.Timeout) {
                    continue
                }
                if (chunk.isNotEmpty()) {
                    val s = String(chunk, Charsets.UTF_8)
                    out.append(s)
                    onOutput(s)
                    if (stopWhen(out.toString())) break
                }
            }
        }
        return out.toString()
    }

    /**
     * Überträgt eine Datei auf das Gerät (Sync-Protokoll: SEND / DATA / DONE).
     * @param mode Unix-Dateirechte inkl. Dateityp, z. B. 0x81A4 (= 0100644).
     */
    fun push(
        source: InputStream,
        size: Long,
        remotePath: String,
        mode: Int = 0x81A4,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val stream = open("sync:")
        stream.use {
            val pathMode = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            stream.write(syncHeader("SEND", pathMode.size) + pathMode)
            val chunk = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                val n = source.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                stream.write(syncHeader("DATA", n) + chunk.copyOf(n))
                sent += n
                onProgress(sent, size)
            }
            stream.write(syncHeader("DONE", (System.currentTimeMillis() / 1000).toInt()))
            val reply = stream.readExact(8, 120_000)
            val id = String(reply, 0, 4, Charsets.US_ASCII)
            if (id != "OKAY") {
                val len = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val msg = if (len in 1..4096) String(stream.readExact(len, 10_000), Charsets.UTF_8) else id
                throw BridgeException.Protocol("Übertragung abgelehnt: $msg")
            }
            try { stream.write(syncHeader("QUIT", 0)) } catch (_: Exception) {}
        }
    }

    private fun syncHeader(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }
}

sealed class BridgeException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Unreachable(host: String, port: Int, cause: Throwable?) :
        BridgeException("Gerät $host ist auf Port $port nicht erreichbar.", cause)
    class Timeout(message: String) : BridgeException(message)
    class Closed(message: String, cause: Throwable? = null) : BridgeException(message, cause)
    class Protocol(message: String) : BridgeException(message)
    class AuthRequired : BridgeException("Das Gerät verlangt eine Anmeldung (ADB-Schlüssel).")
    class AuthRejected : BridgeException("Das Gerät hat den Zugriff nicht bestätigt. Bitte auf dem Fernseher \"Zulassen\" wählen.")
}
