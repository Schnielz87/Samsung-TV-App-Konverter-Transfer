package de.tvtransfer

import de.tvtransfer.app.Legal
import de.tvtransfer.convert.Analyzer
import de.tvtransfer.convert.SourceType
import de.tvtransfer.convert.Zip
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegalTest {
    @Test
    fun protectedWebAppIsNotConverted() {
        val f = File.createTempFile("geschuetzt", ".apk").apply { deleteOnExit() }
        f.writeBytes(Zip.write(linkedMapOf(
            "AndroidManifest.xml" to ByteArray(16),
            "assets/www/index.html" to "<html></html>".toByteArray(),
            "classes.dex" to "xx Lcom/google/android/vending/licensing/LicenseChecker; yy".toByteArray(),
        )))
        val a = Analyzer.analyze(f)
        assertEquals(SourceType.PROTECTED, a.type)
        assertFalse(a.canConvert)
        assertTrue(a.stickCompatible)
    }

    @Test
    fun legalTextsHaveNoOpenPlaceholders() {
        for ((key, _) in Legal.SECTIONS) {
            val t = Legal.text(key)
            assertTrue(t.isNotBlank(), key)
            assertFalse(Regex("\\{[A-Z]+\\}").containsMatchIn(t), "Platzhalter offen in $key")
        }
        assertTrue(Legal.text("nutzungsbedingungen").contains(Legal.RIGHTS_CONFIRMATION))
    }
}
