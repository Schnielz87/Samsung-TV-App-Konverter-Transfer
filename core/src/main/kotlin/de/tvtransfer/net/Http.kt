package de.tvtransfer.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object Http {
    const val USER_AGENT = "TV-App-Transfer/1.0"

    class Response(val code: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
        val ok: Boolean get() = code in 200..299
    }

    fun get(url: String, timeoutMs: Int = 15_000, headers: Map<String, String> = emptyMap()): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return finish(c)
    }

    /** Einzelner Teil eines Formulars: entweder Text oder Datei. */
    class Part(val name: String, val value: ByteArray, val fileName: String? = null, val contentType: String? = null) {
        constructor(name: String, text: String) : this(name, text.toByteArray(Charsets.UTF_8))
    }

    fun postMultipart(url: String, parts: List<Part>, timeoutMs: Int = 60_000): Response {
        val boundary = "----tvtransfer" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        for (p in parts) {
            body.write("--$boundary\r\n".toByteArray())
            if (p.fileName != null) {
                body.write("Content-Disposition: form-data; name=\"${p.name}\"; filename=\"${p.fileName}\"\r\n".toByteArray())
                body.write("Content-Type: ${p.contentType ?: "application/octet-stream"}\r\n\r\n".toByteArray())
            } else {
                body.write("Content-Disposition: form-data; name=\"${p.name}\"\r\n\r\n".toByteArray())
            }
            body.write(p.value)
            body.write("\r\n".toByteArray())
        }
        body.write("--$boundary--\r\n".toByteArray())
        val bytes = body.toByteArray()
        val contentType = "multipart/form-data; boundary=$boundary"
        return try {
            postBytes(url, bytes, contentType, timeoutMs)
        } catch (e: java.net.UnknownHostException) {
            // Name nicht auflösbar – oft blockiert ein Werbe-/Tracking-Filter (Privates DNS, Pi-hole, Router) die Adresse.
            // Dann die Adresse selbst über verschlüsseltes DNS erfragen; das Zertifikat wird weiterhin gegen den echten Namen geprüft.
            val u = URL(url)
            val ip = SecureDns.resolve(u.host) ?: throw IOException(
                "Die Adresse ${u.host} wird in Ihrem Netz blockiert (z. B. durch einen Werbefilter, \"Privates DNS\" am Handy oder Pi-hole). " +
                    "Bitte den Filter kurz ausschalten oder ein anderes Netz (z. B. mobile Daten) verwenden.", e,
            )
            DirectHttps.request(u, ip, "POST", mapOf("Content-Type" to contentType), bytes, timeoutMs)
        }
    }

    private fun postBytes(url: String, bytes: ByteArray, contentType: String, timeoutMs: Int): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Content-Type", contentType)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        return finish(c)
    }

    private fun finish(c: HttpURLConnection): Response {
        try {
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Response(code, data)
        } catch (e: IOException) {
            throw e
        } finally {
            c.disconnect()
        }
    }
}

/** Fragt die IP-Adresse eines Namens über verschlüsseltes DNS (DoH) bei Cloudflare bzw. Google ab – direkt per IP. */
object SecureDns {
    private val servers = listOf(
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
        "https://1.0.0.1/dns-query?type=A&name=",
    )

    fun resolve(host: String): String? {
        for (base in servers) {
            val ip = runCatching {
                val r = Http.get(base + java.net.URLEncoder.encode(host, "UTF-8"), 8000, mapOf("Accept" to "application/dns-json"))
                if (!r.ok) return@runCatching null
                @Suppress("UNCHECKED_CAST")
                val answers = (Json.parse(r.text) as Map<String, Any?>)["Answer"] as? List<Map<String, Any?>> ?: emptyList()
                answers.firstOrNull { (it["type"] as? Number)?.toInt() == 1 }?.get("data")?.toString()
            }.getOrNull()
            if (ip != null && Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(ip)) return ip
        }
        return null
    }
}

/**
 * HTTPS-Anfrage an eine bekannte IP-Adresse, aber mit dem echten Namen für SNI und Zertifikatsprüfung
 * (wie ein normaler Aufruf – nur ohne DNS). Unterstützt Content-Length und chunked Antworten.
 */
object DirectHttps {
    fun request(url: URL, ip: String, method: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): Http.Response {
        val port = if (url.port > 0) url.port else 443
        val raw = java.net.Socket()
        raw.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val ssl = factory.createSocket(raw, url.host, port, true) as javax.net.ssl.SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        ssl.use { sock ->
            sock.startHandshake()
            val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
            val head = StringBuilder()
            head.append("$method $path HTTP/1.1\r\nHost: ${url.host}\r\nUser-Agent: ${Http.USER_AGENT}\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("Content-Length: ${body.size}\r\n\r\n")
            val out = sock.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
            val input = java.io.BufferedInputStream(sock.getInputStream())
            val status = readLine(input) ?: throw IOException("Keine Antwort vom Server.")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Ungültige Antwort: $status")
            val respHeaders = HashMap<String, String>()
            while (true) {
                val l = readLine(input) ?: break
                if (l.isEmpty()) break
                respHeaders[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
            }
            val data = when {
                respHeaders["transfer-encoding"]?.contains("chunked", true) == true -> {
                    val bos = ByteArrayOutputStream()
                    while (true) {
                        val size = (readLine(input) ?: break).substringBefore(';').trim().toInt(16)
                        if (size == 0) break
                        bos.write(readN(input, size))
                        readLine(input)
                    }
                    bos.toByteArray()
                }
                respHeaders["content-length"] != null -> readN(input, respHeaders["content-length"]!!.toInt())
                else -> input.readBytes()
            }
            return Http.Response(code, data)
        }
    }

    private fun readLine(i: java.io.InputStream): String? {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = i.read()
            if (b < 0) return if (bos.size() == 0) null else bos.toString("ISO-8859-1")
            if (b == '\n'.code) return bos.toString("ISO-8859-1").trimEnd('\r')
            bos.write(b)
        }
    }

    private fun readN(i: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }
}
