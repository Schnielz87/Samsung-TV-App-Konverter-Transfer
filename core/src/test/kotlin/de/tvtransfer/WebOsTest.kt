package de.tvtransfer

import de.tvtransfer.app.TargetPlatform
import de.tvtransfer.convert.Analyzer
import de.tvtransfer.convert.WebOsBuilder
import de.tvtransfer.convert.WgtBuilder
import de.tvtransfer.convert.WgtOptions
import de.tvtransfer.convert.Zip
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebOsTest {
    @Test
    fun ipkHasDebianStructureAndAppInfo() {
        val wgt = WgtBuilder.fromWebFiles(
            linkedMapOf("index.html" to "<html><head></head><body>Hallo</body></html>".toByteArray(), "js/app.js" to "1".toByteArray()),
            "index.html", WgtOptions("Meine App", "1.2.3"), seed = "x", icon = null, cordova = false,
        ).wgt
        val ipk = WebOsBuilder.fromWgt(wgt)
        assertTrue(ipk.appId.startsWith("com.tvtransfer."))
        assertEquals("1.2.3", ipk.version)
        val b = ipk.bytes
        assertEquals("!<arch>\n", String(b, 0, 8))
        assertTrue(String(b, 8, 16).startsWith("debian-binary"))
        System.getenv("TVT_IPK_OUT")?.let { File(it).writeBytes(b) }
    }

    @Test
    fun websiteGetsLocalStartPage() {
        val a = Analyzer.website("https://example.org/tv")
        val ipk = WebOsBuilder.fromWgt(WgtBuilder.build(a, WgtOptions("Beispiel")).wgt)
        assertTrue(ipk.bytes.size > 100)
    }

    @Test
    fun platformSupport() {
        val site = Analyzer.website("https://example.org")
        assertTrue(TargetPlatform.LG.support(site).first)
        assertFalse(TargetPlatform.ANDROID_TV.support(site).first)
    }
}
