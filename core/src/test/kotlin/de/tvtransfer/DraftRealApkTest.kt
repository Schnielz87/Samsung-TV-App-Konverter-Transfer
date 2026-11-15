package de.tvtransfer

import de.tvtransfer.convert.Analyzer
import de.tvtransfer.convert.Validator
import de.tvtransfer.convert.WgtBuilder
import de.tvtransfer.convert.WgtOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Prüft den Entwurf-Konverter mit echten APKs, falls TVT_TEST_APKS gesetzt ist (Pfade, durch : getrennt). */
class DraftRealApkTest {
    @Test
    fun draftFromRealApks() {
        val paths = System.getenv("TVT_TEST_APKS")?.split(':')?.filter { it.isNotBlank() } ?: return
        val outDir = System.getenv("TVT_TEST_OUT")?.let { File(it) }
        for (p in paths) {
            val a = Analyzer.analyze(File(p))
            println("== ${a.displayName} (${a.packageName}) type=${a.type} canDraft=${a.canDraft}")
            if (!a.canDraft) continue
            val (r, d) = WgtBuilder.buildDraft(a, WgtOptions(a.displayName))
            d.summary.forEach { println("  $it") }
            println("  Bildschirme: ${d.screens.take(12)}")
            val v = Validator.validate(r.wgt)
            v.findings.filter { it.status.name != "OK" }.forEach { println("  Prüfung: ${it.status} ${it.title}") }
            assertTrue(v.ok, "Entwurf ungültig: ${v.blockers()}")
            assertTrue(d.screens.isNotEmpty(), "keine Bildschirme")
            outDir?.let { File(it, File(p).nameWithoutExtension + ".wgt").apply { parentFile.mkdirs(); writeBytes(r.wgt) } }
        }
    }
}
