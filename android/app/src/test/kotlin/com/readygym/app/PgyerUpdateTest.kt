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
        // Remote must be strictly newer than whatever versionCode this build carries, so the
        // fixture derives from BuildConfig.VERSION_CODE instead of a hard-coded number.
        val remote = BuildConfig.VERSION_CODE + 1
        val body = """
            {"code":0,"message":"","data":{"buildKey":"x","buildType":"2","buildVersion":"0.5.0",
             "buildVersionNo":"$remote","buildBuildVersion":"$remote","forceUpdateVersion":"","forceUpdateVersionNo":"",
             "downloadURL":"https://www.pgyer.com/abc","buildUpdateDescription":"加了器械GIF和反馈入口",
             "buildHaveNewVersion":true,"buildFileHash":"h"}}
        """.trimIndent()
        val result = PgyerUpdate.parseCheckReply(body)
        assertTrue(result is PgyerUpdate.CheckResult.Newer)
        val info = (result as PgyerUpdate.CheckResult.Newer).info
        assertEquals("0.5.0", info.versionName)
        assertEquals(remote.toLong(), info.versionCode)
        assertEquals("https://www.pgyer.com/abc", info.downloadUrl)
        assertEquals("加了器械GIF和反馈入口", info.notes)
    }

    @Test fun sameBuildIsUpToDate() {
        // Remote versionCode equals this build's VERSION_CODE; remote says no new version.
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

    @Test fun unreliableBuildHaveNewVersionFlagIsIgnored() {
        // AGENTS.md 4.5: this field lies. Only remote versionCode > local may decide.
        val liedTrue = """{"code":0,"data":{"buildVersionNo":"${BuildConfig.VERSION_CODE}",
            "buildHaveNewVersion":true,"downloadURL":"https://www.pgyer.com/abc",
            "buildVersion":"0.4.1","buildUpdateDescription":""}}"""
        assertEquals(PgyerUpdate.CheckResult.UpToDate, PgyerUpdate.parseCheckReply(liedTrue))

        val liedFalse = """{"code":0,"data":{"buildVersionNo":"${BuildConfig.VERSION_CODE + 1}",
            "buildHaveNewVersion":false,"downloadURL":"https://www.pgyer.com/abc",
            "buildVersion":"0.4.2","buildUpdateDescription":""}}"""
        assertTrue(PgyerUpdate.parseCheckReply(liedFalse) is PgyerUpdate.CheckResult.Newer)
    }
}
