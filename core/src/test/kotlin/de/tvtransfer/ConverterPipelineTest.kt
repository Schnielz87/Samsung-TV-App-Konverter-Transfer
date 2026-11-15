package de.tvtransfer

import de.tvtransfer.convert.Analyzer
import de.tvtransfer.convert.Portability
import de.tvtransfer.convert.SourceType
import de.tvtransfer.convert.Validator
import de.tvtransfer.convert.WgtBuilder
import de.tvtransfer.convert.WgtOptions
import de.tvtransfer.convert.Zip
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConverterPipelineTest {
    private fun apk(vararg files: Pair<String, ByteArray>): File =
        File.createTempFile("pipeline", ".apk").apply { deleteOnExit(); writeBytes(Zip.write(linkedMapOf("AndroidManifest.xml" to ByteArray(16), *files))) }

    @Test
    fun nativeAppReportsConcreteBlockers() {
        val a = Analyzer.analyze(apk("classes.dex" to "Landroidx/media3/exoplayer/X; Lcom/google/android/gms/cast/Y;".toByteArray()))
        assertEquals(SourceType.NATIVE_ANDROID, a.type)
        assertTrue(a.verdict.startsWith("Konvertierung nicht möglich"))
        assertTrue(a.verdict.contains("Media3/ExoPlayer"))
        assertTrue(a.verdict.contains("Google-Play-Dienste"))
        assertTrue(a.findings.any { it.status == Portability.BLOCKER && it.area == "Medien" })
    }

    @Test
    fun webAppIsConvertedAndValidated() {
        val a = Analyzer.analyze(apk(
            "assets/www/index.html" to "<html><head><link href=\"css/a.css\"><script src=\"js/app.js\"></script><script src=\"cordova.js\"></script></head></html>".toByteArray(),
            "assets/www/css/a.css" to "x".toByteArray(),
            "assets/www/js/app.js" to "1".toByteArray(),
            "assets/www/cordova.js" to "x".toByteArray(),
            "assets/www/cordova_plugins.js" to "module.exports=[{\"id\":\"a\",\"pluginId\":\"cordova-plugin-camera\"}]".toByteArray(),
            "classes.dex" to "Landroid/webkit/WebView;".toByteArray(),
        ), labelHint = "Web")
        assertTrue(a.canConvert)
        assertTrue(a.findings.any { it.title.contains("cordova-plugin-camera") && it.status == Portability.LIMITED })
        assertFalse(a.findings.any { it.status == Portability.BLOCKER })
        val r = Validator.validate(WgtBuilder.build(a, WgtOptions("Web")).wgt)
        assertTrue(r.ok, r.findings.toString())
    }

    @Test
    fun brokenPackageIsNeverReportedAsSuccess() {
        val wgt = Zip.write(linkedMapOf(
            "config.xml" to """<?xml version="1.0"?><widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="1.0.0">
                <tizen:application id="Abcdefghij.App" package="Abcdefghij"/><content src="index.html"/></widget>""".toByteArray(),
            "index.html" to "<script src=\"\$WEBAPIS/webapis/webapis.js\"></script><script src=\"fehlt.js\"></script>".toByteArray(),
        ))
        val r = Validator.validate(wgt)
        assertFalse(r.ok)
        assertTrue(r.blockers().single().title.contains("fehlt.js"))
    }
}

class ApkSetTest {
    @Test
    fun filesAreFoundInSplitApks() {
        val base = File.createTempFile("basis", ".apk").apply { deleteOnExit(); writeBytes(Zip.write(linkedMapOf("res/layout/main.xml" to "B".toByteArray()))) }
        val split = File.createTempFile("teil", ".apk").apply { deleteOnExit(); writeBytes(Zip.write(linkedMapOf("res/drawable-xxhdpi/logo.png" to "S".toByteArray(), "res/layout/main.xml" to "X".toByteArray()))) }
        val set = de.tvtransfer.convert.ApkSet(listOf(base, split))
        assertEquals("B", String(set.entry("res/layout/main.xml")!!)) // Basis hat Vorrang
        assertEquals("S", String(set.entry("res/drawable-xxhdpi/logo.png")!!))
        assertTrue(set.names().size == 2)
    }
}
