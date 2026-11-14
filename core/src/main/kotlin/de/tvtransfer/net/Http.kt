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

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
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
