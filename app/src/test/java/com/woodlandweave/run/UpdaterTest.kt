package com.woodlandweave.run

import com.woodlandweave.run.update.*
import org.junit.Assert.*
import org.junit.Test

class UpdaterTest {
    private val apk = Asset("woodland-weave-run-2026-11-10_21-30.apk", "https://github.com/Szucsy92/woodland-weave-run/releases/download/v2.0.0-x-build12/a.apk")

    @Test fun buildNumberComesFromTheTag() {
        assertEquals(12, buildFromTag("v2.0.0-2026-11-10_21-30-build12"))
        assertEquals(7, buildFromTag("v1.0.0-BUILD7"))
        assertNull(buildFromTag("v1.0.0"))
    }

    @Test fun onlyNewerBuildsWithAnApkOffer() {
        val r = Release("v2.0.0-x-build12", "Woodland Weave Run", listOf(apk))
        assertNotNull(pickUpdate(r, 11))
        assertNull(pickUpdate(r, 12))
        assertNull(pickUpdate(r, 40))
        assertNull(pickUpdate(Release("v2.0.0-x-build12", null, emptyList()), 1))
        assertNull(pickUpdate(Release("nonsense", null, listOf(apk)), 1))
    }

    @Test fun onlyThisGamesReleaseAssetsAreTrusted() {
        assertTrue(isTrustedUpdateUrl(apk.url))
        assertFalse(isTrustedUpdateUrl("http://github.com/Szucsy92/woodland-weave-run/releases/download/a.apk"))
        assertFalse(isTrustedUpdateUrl("https://evil.example/Szucsy92/woodland-weave-run/releases/download/a.apk"))
        assertFalse(isTrustedUpdateUrl("https://github.com/someone/else/releases/download/a.apk"))
        assertFalse(isTrustedUpdateUrl(null))
    }

    @Test fun releaseJsonIsParsed() {
        val json = """{"tag_name":"v2.0.0-x-build9","name":"N","assets":[{"name":"a.txt","browser_download_url":"u1"},{"name":"b.apk","browser_download_url":"u2"}]}"""
        val r = parseRelease(json)
        assertEquals("v2.0.0-x-build9", r.tag)
        assertEquals("u2", pickUpdate(r, 1)?.apkUrl)
    }
}
