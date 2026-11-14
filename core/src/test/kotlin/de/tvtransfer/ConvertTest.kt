package de.tvtransfer

import de.tvtransfer.convert.Analyzer
import de.tvtransfer.convert.SourceType
import de.tvtransfer.convert.WgtBuilder
import de.tvtransfer.convert.WgtOptions
import de.tvtransfer.convert.Zip
import de.tvtransfer.sign.LoginReceiver
import de.tvtransfer.tv.SamsungTv
import de.tvtransfer.tv.TvModel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConvertTest {

    private fun apk(vararg files: Pair<String, ByteArray>): File {
        val f = File.createTempFile("test", ".apk")
        f.deleteOnExit()
        f.writeBytes(Zip.write(linkedMapOf("AndroidManifest.xml" to ByteArray(16), *files)))
        return f
    }

    @Test
    fun cordovaApkIsConvertible() {
        val f = apk(
            "assets/www/index.html" to "<html><head><title>x</title></head><body>Hallo</body></html>".toByteArray(),
            "assets/www/cordova.js" to "native".toByteArray(),
            "assets/www/js/app.js" to "1".toByteArray(),
            "classes.dex" to ByteArray(10),
        )
        val a = Analyzer.analyze(f, labelHint = "Meine App")
        assertEquals(SourceType.WEB_APP_IN_APK, a.type)
        assertTrue(a.canConvert)
        assertEquals("Cordova/PhoneGap", a.framework)

        val r = WgtBuilder.build(a, WgtOptions("Meine App", "2.5.1-beta", TvModel.Y2019))
        val files = Zip.read(r.wgt)
        assertEquals("config.xml", files.keys.first())
        assertTrue(String(files["index.html"]!!).contains("<head><script src=\"tv-bridge.js\"></script><title>"))
        assertTrue(String(files["cordova.js"]!!).contains("__tvShim"))
        assertNotNull(files["js/app.js"])
        val config = String(files["config.xml"]!!)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(config.byteInputStream())
        assertEquals("2.5.1", doc.documentElement.getAttribute("version"))
        assertEquals(r.packageId, SamsungTv.packageId(r.wgt))
        assertEquals(r.appId, SamsungTv.applicationId(r.wgt))
        assertTrue(Regex("^[A-Za-z][A-Za-z0-9]{9}$").matches(r.packageId))
        assertEquals("${r.packageId}.MeineApp", r.appId)
    }

    @Test
    fun flutterAndNativeAreRejectedButStickCompatible() {
        val flutter = Analyzer.analyze(apk("lib/arm64-v8a/libflutter.so" to ByteArray(4), "classes.dex" to ByteArray(4)))
        assertEquals(SourceType.NATIVE_ANDROID, flutter.type)
        assertFalse(flutter.canConvert)
        assertTrue(flutter.stickCompatible)
        assertEquals("Flutter", flutter.framework)

        val native = Analyzer.analyze(apk("classes.dex" to "Lcom/foo/Main;".toByteArray()))
        assertEquals(SourceType.NATIVE_ANDROID, native.type)
    }

    @Test
    fun webViewWrapperFindsUrl() {
        val dex = "xx Landroid/webkit/WebView; yy https://schema.org/x http://www.w3.org/1999/xhtml https://shop.meinladen.de/app zz https://shop.meinladen.de/app".toByteArray()
        val a = Analyzer.analyze(apk("classes.dex" to dex))
        assertEquals(SourceType.WEBVIEW_WRAPPER, a.type)
        assertEquals("https://shop.meinladen.de/app", a.urls.first())
        val r = WgtBuilder.build(a, WgtOptions("Laden"))
        val config = String(Zip.read(r.wgt)["config.xml"]!!)
        assertTrue(config.contains("<content src=\"https://shop.meinladen.de/app\"/>"))
        assertTrue(config.contains("<tizen:allow-navigation>shop.meinladen.de</tizen:allow-navigation>"))
    }

    @Test
    fun websiteAndIds() {
        val a = Analyzer.website("www.beispiel.de")
        assertEquals("https://www.beispiel.de", a.urls.single())
        assertEquals("Beispiel", a.displayName)
        assertEquals(WgtBuilder.ids("x", "Name"), WgtBuilder.ids("x", "Name"))
        assertEquals("1.0.0", WgtBuilder.sanitizeVersion(null))
        assertEquals("255.3.0", WgtBuilder.sanitizeVersion("300.3"))
    }

    @Test
    fun tvModelDetection() {
        assertEquals(TvModel.Y2019, TvModel.detect("19_MUSEL_UHD", null))
        assertEquals(TvModel.Y2020, TvModel.detect(null, "UE55TU8079"))
        assertEquals(TvModel.Y2021, TvModel.detect(null, "GQ65QN90AAT"))
        assertEquals(TvModel.Y2018, TvModel.detect(null, "UE49NU7179"))
    }

    @Test
    fun installOutputIsInterpreted() {
        assertTrue(SamsungTv.interpret("app_id[abc] install start\napp_id[abc] install completed\nspend time for wascmd is [3022]ms").success)
        val fail = SamsungTv.interpret("app_id[abc] install failed[118, -12], reason: Check certificate error")
        assertFalse(fail.success)
        assertTrue(fail.message.contains("Zertifikat"))
    }

    @Test
    fun loginReceiverAcceptsSamsungCallback() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val receiver = LoginReceiver(port)
        var result: de.tvtransfer.sign.SamsungAccess? = null
        val t = thread { result = receiver.await(10_000) }
        Thread.sleep(300)
        val json = """{"access_token":"tok123","userId":"u42","inputEmailID":"a@b.de"}"""
        val c = URL("http://127.0.0.1:$port/signin/callback").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(("code=" + URLEncoder.encode(json, "UTF-8")).toByteArray()) }
        assertTrue(c.inputStream.bufferedReader().readText().contains("erfolgreich"))
        t.join(5000)
        assertEquals("tok123", result?.accessToken)
        assertEquals("u42", result?.userId)
        assertEquals("a@b.de", result?.email)
    }
}
