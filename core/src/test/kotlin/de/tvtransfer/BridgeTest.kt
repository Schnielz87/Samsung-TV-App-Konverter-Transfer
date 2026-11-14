package de.tvtransfer

import de.tvtransfer.bridge.AdbKey
import de.tvtransfer.bridge.BridgeConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Simuliert ein Gerät (Fernseher/Stick) und prüft Verbindungsaufbau, Befehle und Dateiübertragung. */
class BridgeTest {

    private class FakeDevice(val requireAuth: Boolean) {
        val server = ServerSocket(0)
        val pushed = ByteArrayOutputStream()
        var pushedPath = ""
        var gotPublicKey = false
        var signatureOk = false

        fun start() = thread(isDaemon = true) {
            server.accept().use { s ->
                val inp = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                fun read(): Triple<Int, IntArray, ByteArray> {
                    val h = ByteArray(24); inp.readFully(h)
                    val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                    val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
                    val d = ByteArray(len); inp.readFully(d)
                    return Triple(cmd, intArrayOf(a0, a1), d)
                }
                read() // CNXN
                if (requireAuth) {
                    val token = ByteArray(20) { it.toByte() }
                    send(out, BridgeConnection.AUTH, 1, 0, token)
                    val (_, a, sig) = read()
                    check(a[0] == 2)
                    // Signatur ist ungültig für "unbekannten" Schlüssel → nach Public Key fragen
                    send(out, BridgeConnection.AUTH, 1, 0, token)
                    val (_, a2, pub) = read()
                    check(a2[0] == 3)
                    gotPublicKey = true
                    val key = parseAndroidKey(String(pub).trimEnd('\u0000').substringBefore(' '))
                    val c = Cipher.getInstance("RSA/ECB/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
                    val em = c.doFinal(sig)
                    signatureOk = em.takeLast(20).toByteArray().contentEquals(token)
                }
                // wie manche Fernseher: großen Wert ankündigen, aber Pakete > 4096 Byte nicht vertragen
                send(out, BridgeConnection.CNXN, 0x01000000, 256 * 1024, "device::ro.product.model=FakeTV;\u0000".toByteArray())
                var syncRemote = 0
                val syncBuf = ByteArrayOutputStream()
                while (true) {
                    val (cmd, a, d) = try { read() } catch (e: Exception) { return@thread }
                    if (d.size > 4096) { s.close(); return@thread }
                    when (cmd) {
                        BridgeConnection.OPEN -> {
                            val svc = String(d).trimEnd('\u0000')
                            if (svc.startsWith("shell:")) {
                                send(out, BridgeConnection.OKAY, 100, a[0])
                                send(out, BridgeConnection.WRTE, 100, a[0], "Ausgabe von ${svc.removePrefix("shell:")}\n".toByteArray())
                                read() // OKAY
                                send(out, BridgeConnection.CLSE, 100, a[0])
                            } else if (svc == "sync:") {
                                syncRemote = a[0]
                                send(out, BridgeConnection.OKAY, 200, a[0])
                            }
                        }
                        BridgeConnection.WRTE -> {
                            send(out, BridgeConnection.OKAY, 200, a[0])
                            syncBuf.write(d)
                            // vollständige Sync-Nachrichten auswerten
                            val all = syncBuf.toByteArray()
                            var p = 0
                            var finished = false
                            while (p + 8 <= all.size) {
                                val id = String(all, p, 4)
                                val n = ByteBuffer.wrap(all, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                                when (id) {
                                    "SEND" -> { if (p + 8 + n > all.size) break; pushedPath = String(all, p + 8, n); p += 8 + n }
                                    "DATA" -> { if (p + 8 + n > all.size) break; pushed.write(all, p + 8, n); p += 8 + n }
                                    "DONE" -> { p += 8; finished = true }
                                    "QUIT" -> { p += 8 }
                                    else -> error("unbekannt $id")
                                }
                            }
                            syncBuf.reset(); syncBuf.write(all, p, all.size - p)
                            if (finished) send(out, BridgeConnection.WRTE, 200, syncRemote, "OKAY\u0000\u0000\u0000\u0000".toByteArray())
                        }
                        BridgeConnection.CLSE -> send(out, BridgeConnection.CLSE, 200, a[0])
                    }
                }
            }
        }

        fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, d: ByteArray = ByteArray(0)) {
            val b = ByteBuffer.allocate(24 + d.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(cmd).putInt(a0).putInt(a1).putInt(d.size).putInt(0).putInt(cmd.inv()).put(d)
            out.write(b.array()); out.flush()
        }
    }

    @Test
    fun shellAndPushOverSdb() {
        val dev = FakeDevice(requireAuth = false)
        dev.start()
        BridgeConnection.connect("127.0.0.1", dev.server.localPort, maxData = 4096).use { c ->
            assertTrue(c.banner.contains("FakeTV"))
            assertEquals("Ausgabe von 0 getduid\n", c.shell("0 getduid"))
            val data = ByteArray(300_000) { (it % 251).toByte() }
            c.push(ByteArrayInputStream(data), data.size.toLong(), "/home/owner/share/tmp/sdk_tools/x.wgt", 0x81ED)
            Thread.sleep(200)
            assertEquals("/home/owner/share/tmp/sdk_tools/x.wgt,${0x81ED}", dev.pushedPath)
            assertContentEquals(data, dev.pushed.toByteArray())
        }
    }

    @Test
    fun adbAuthSendsValidSignatureAndPublicKey() {
        val dev = FakeDevice(requireAuth = true)
        dev.start()
        val key = AdbKey.generate("test@pc")
        var prompted = false
        BridgeConnection.connect("127.0.0.1", dev.server.localPort, key, onAuthPrompt = { prompted = true }).use { c ->
            assertTrue(prompted)
            assertTrue(dev.gotPublicKey)
            assertTrue(dev.signatureOk, "Token-Signatur ungültig")
            assertEquals("Ausgabe von getprop\n", c.shell("getprop"))
        }
    }

    companion object {
        /** Liest das Android-Schlüsselformat zurück (prüft damit unsere Kodierung). */
        fun parseAndroidKey(b64: String): RSAPublicKey {
            val b = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
            val words = b.int
            check(words == 64)
            val n0inv = b.int.toLong() and 0xFFFFFFFFL
            val nBytes = ByteArray(words * 4)
            for (i in 0 until words) {
                val w = b.int
                val o = (words - 1 - i) * 4
                nBytes[o] = (w ushr 24).toByte(); nBytes[o + 1] = (w ushr 16).toByte(); nBytes[o + 2] = (w ushr 8).toByte(); nBytes[o + 3] = w.toByte()
            }
            val n = java.math.BigInteger(1, nBytes)
            repeat(words) { b.int } // rr
            val e = b.int
            // n0inv * n[0] ≡ -1 mod 2^32
            val n0 = n.mod(java.math.BigInteger.ONE.shiftLeft(32)).toLong()
            check(((n0inv * n0) and 0xFFFFFFFFL) == 0xFFFFFFFFL)
            return java.security.KeyFactory.getInstance("RSA").generatePublic(
                java.security.spec.RSAPublicKeySpec(n, java.math.BigInteger.valueOf(e.toLong())),
            ) as RSAPublicKey
        }
    }
}
