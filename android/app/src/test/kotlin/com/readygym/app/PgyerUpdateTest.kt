package com.readygym.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure reply-shape contract for PGYER's check API, so a changed field fails here, not in users' hands. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PgyerUpdateTest {

    @Test fun newerBuildIsReportedWithDownloadUrlAndNotes() {
        val body = """
            {"code":0,"message":"","data":{"buildKey":"x","buildType":"2","buildVersion":"0.5.0",
             "buildVersionNo":"7","buildBuildVersion":"7","forceUpdateVersion":"","forceUpdateVersionNo":"",
             "downloadURL":"https://www.pgyer.com/abc","buildUpdateDescription":"加了器械GIF和反馈入口",
             "buildHaveNewVersion":true,"buildFileHash":"h"}}
        """.trimIndent()
        val result = PgyerUpdate.parseCheckReply(body)
        assertTrue(result is PgyerUpdate.CheckResult.Newer)
        val info = (result as PgyerUpdate.CheckResult.Newer).info
        assertEquals("0.5.0", info.versionName)
        assertEquals(7L, info.versionCode)
        assertEquals("https://www.pgyer.com/abc", info.downloadUrl)
        assertEquals("加了器械GIF和反馈入口", info.notes)
    }

    @Test fun sameBuildIsUpToDate() {
        // VERSION_CODE of this test build equals the released one (6); remote says no new version.
        val body = """{"code":0,"data":{"buildVersionNo":"${BuildConfig.VERSION_CODE}","buildHaveNewVersion":false,
            "downloadURL":"https://www.pgyer.com/abc","buildVersion":"0.4.1","buildUpdateDescription":""}}"""
        assertEquals(PgyerUpdate.CheckResult.UpToDate, PgyerUpdate.parseCheckReply(body))
    }

    @Test fun errorPayloadBecomesAFriendlyFailure() {
        assertEquals(PgyerUpdate.CheckResult.Failed::class.java,
            PgyerUpdate.parseCheckReply("""{"code":1203,"message":"api key 无效"}""")::class.java)
    }

    @Test fun noBuildsYetCountsAsUpToDate() {
        assertEquals(PgyerUpdate.CheckResult.UpToDate,
            PgyerUpdate.parseCheckReply("""{"code":0,"data":{}}"""))
    }
}
