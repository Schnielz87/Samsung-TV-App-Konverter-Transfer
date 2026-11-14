package de.tvtransfer

import de.tvtransfer.convert.TizenCounterparts
import de.tvtransfer.convert.Zip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CounterpartTest {
    @Test
    fun powerIptvIsKnown() {
        assertNotNull(TizenCounterparts.forPackage("com.poweriptv.app"))
        assertNull(TizenCounterparts.forPackage("com.netflix.mediaclient"))
    }

    @Test
    fun zipWithFolderBecomesWgt() {
        val zip = Zip.write(linkedMapOf(
            "PowerIPTV-Tizen/index.html" to "<html/>".toByteArray(),
            "PowerIPTV-Tizen/config.xml" to "<widget/>".toByteArray(),
            "PowerIPTV-Tizen/js/app.js" to "1".toByteArray(),
            "PowerIPTV-Tizen/author-signature.xml" to "alt".toByteArray(),
        ))
        val files = Zip.read(TizenCounterparts.zipToWgt(zip))
        assertEquals(listOf("config.xml", "index.html", "js/app.js"), files.keys.toList())
    }
}
