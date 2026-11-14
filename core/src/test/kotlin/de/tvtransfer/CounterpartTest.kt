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

class ChainTest {
    @org.junit.jupiter.api.Test
    fun bundledCasCoverAuthorAndDistributorIssuers() {
        val names = listOf("Samsung VD Author CA", "Samsung Author CA Class", "VD DEVELOPER Public CA Class", "VD DEVELOPER Partner CA Class", "VD DEVELOPER Public CA Class", "VD DEVELOPER Partner CA Class")
        val subjects = listOf("vd_tizen_dev_author_ca2.cer", "vd_tizen_dev_author_ca.cer", "vd_tizen_dev_public2_2075.crt", "vd_tizen_dev_partner2_2075.crt", "vd_tizen_dev_public2.crt", "vd_tizen_dev_partner2.crt")
            .map { de.tvtransfer.sign.SamsungCertificates.cn(de.tvtransfer.sign.SamsungCertificates.caCert(it)) }
        kotlin.test.assertEquals(names, subjects)
    }
}

class ProfileTest {
    @org.junit.jupiter.api.Test
    fun duidsAreReadFromDeviceProfile() {
        val xml = "<Profile><TestDeviceInfo><TestDevice>2DCEPZPT36VQE</TestDevice>\n<TestDevice> 43CKFKKBJBU64 </TestDevice></TestDeviceInfo></Profile>"
        kotlin.test.assertEquals(listOf("2DCEPZPT36VQE", "43CKFKKBJBU64"), de.tvtransfer.sign.SamsungCertificates.profileDuids(xml.toByteArray()))
    }
}
