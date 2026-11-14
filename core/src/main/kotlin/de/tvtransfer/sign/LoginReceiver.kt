package de.tvtransfer.sign

import de.tvtransfer.net.Json
import de.tvtransfer.net.str
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Nimmt die Rückmeldung der offiziellen Samsung-Anmeldung entgegen.
 * Samsung leitet den Browser nach dem Login auf http://localhost:4794/signin/callback weiter –
 * dieses Programm lauscht dort kurz und liest das Token aus. Passwörter sieht das Programm nie.
 *
 * Falls die automatische Weiterleitung nicht klappt, zeigt dieselbe Adresse ein Feld zum Einfügen.
 */
class LoginReceiver(private val port: Int = SamsungCertificates.LOGIN_PORT) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var delivered: SamsungAccess? = null

    /** Übernimmt manuell eingefügte Anmeldedaten (falls die Browser-Weiterleitung nicht ankommt). */
    fun deliver(access: SamsungAccess) { delivered = access }

    /** Wartet bis zu [timeoutMs] auf die Anmeldung. Gibt null zurück bei Abbruch/Zeitüberschreitung. */
    fun await(timeoutMs: Long = 10 * 60_000L): SamsungAccess? {
        val ss = ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))
        server = ss
        val deadline = System.currentTimeMillis() + timeoutMs
        ss.use {
            while (System.currentTimeMillis() < deadline) {
                delivered?.let { return it }
                ss.soTimeout = 1000
                val client = try { ss.accept() } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { return null }
                val access = client.use { handle(it) }
                if (access != null) return access
            }
        }
        return null
    }

    fun cancel() {
        runCatching { server?.close() }
    }

    private fun handle(s: Socket): SamsungAccess? {
        s.soTimeout = 10_000
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val method = requestLine.substringBefore(' ')
        val target = requestLine.split(' ').getOrNull(1) ?: "/"
        val length = lines.firstOrNull { it.lowercase().startsWith("content-length:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = if (length > 0) String(input.readNBytesCompat(length), Charsets.UTF_8) else ""

        val params = parseForm(body) + parseForm(target.substringAfter('?', ""))
        val access = params["code"]?.let { parseAccess(it) }
            ?: params["access_token"]?.let { tok ->
                SamsungAccess(tok, params["userId"] ?: params["user_id"] ?: "", params["inputEmailID"] ?: "")
            }?.takeIf { it.userId.isNotEmpty() }

        val html = when {
            access != null -> page("Anmeldung erfolgreich", "<p>Sie können dieses Fenster schließen und zur App zurückkehren. Das Zertifikat wird jetzt erstellt.</p>")
            method == "POST" -> page("Daten nicht erkannt", "<p>Die Anmeldedaten konnten nicht gelesen werden. Bitte erneut versuchen.</p>" + PASTE_FORM)
            else -> page("Samsung-Anmeldung abschließen", "<p>Falls die automatische Weiterleitung nicht geklappt hat: Den angezeigten Text der Samsung-Seite hier einfügen.</p>" + PASTE_FORM)
        }
        val bytes = html.toByteArray(Charsets.UTF_8)
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
        return access
    }

    companion object {
        fun parseAccess(json: String): SamsungAccess? = runCatching {
            val o = Json.parse(json.trim())
            val token = o.str("access_token") ?: return null
            val user = o.str("userId") ?: o.str("user_id") ?: return null
            SamsungAccess(token, user, o.str("inputEmailID") ?: o.str("email") ?: "")
        }.getOrNull()

        fun parseForm(s: String): Map<String, String> =
            s.split('&').filter { it.contains('=') }.associate {
                val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(it.substringAfter('='), "UTF-8")
                k to v
            }

        private const val PASTE_FORM =
            "<form method=\"post\" action=\"/\"><textarea name=\"code\" rows=\"6\" style=\"width:100%\" placeholder='{\"access_token\": …}'></textarea>" +
                "<p><button type=\"submit\" style=\"padding:12px 24px\">Übernehmen</button></p></form>"

        private fun page(title: String, body: String) =
            "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>$title</title></head><body style=\"font-family:system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 16px;background:#0f172a;color:#e2e8f0\">" +
                "<h1>$title</h1>$body</body></html>"

        private fun readHead(input: BufferedInputStream): String? {
            val out = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                out.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (out.size() > 64 * 1024) return null
            }
            return String(out.toByteArray(), Charsets.ISO_8859_1)
        }

        private fun BufferedInputStream.readNBytesCompat(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            return buf.copyOf(off)
        }
    }
}
