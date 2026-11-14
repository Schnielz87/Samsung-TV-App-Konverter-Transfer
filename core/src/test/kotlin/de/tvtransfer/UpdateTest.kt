package de.tvtransfer

import de.tvtransfer.update.UpdateChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateTest {
    private fun rel(tag: String, pre: Boolean = false) =
        """{"tag_name":"$tag","prerelease":$pre,"draft":false,"body":"x","html_url":"u",
           "assets":[{"name":"TV-App-Transfer.apk","browser_download_url":"https://d/$tag/TV-App-Transfer.apk"}]}"""

    @Test
    fun newestOwnIgnoresOtherSoftwareAndPrereleases() {
        val json = "[" + listOf(
            rel("tv-app-transfer-v1.0.7"),
            rel("tv-app-transfer-v1.0.12"),
            rel("tv-app-transfer-v1.0.20", pre = true),
            rel("ki-transkribierung-v1.3.400"),
            rel("v1.0.99"),
        ).joinToString(",") + "]"
        val r = UpdateChecker.newestOwn(json)!!
        assertEquals(12, r.build)
        assertEquals("1.0.12", r.version)
        assertEquals("https://d/tv-app-transfer-v1.0.12/TV-App-Transfer.apk", r.assets[UpdateChecker.APK_NAME])
        assertNull(UpdateChecker.newestOwn("[]"))
        assertEquals(5, UpdateChecker.buildOf("1.0.5"))
    }
}
