package app.cadence.state

import app.cadence.state.settings.UpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun semverOrderingHandlesPrereleases() {
        val v010 = SemVer.parse("v0.1.0")!!
        val v011 = SemVer.parse("0.1.1")!!
        val v020Dev1 = SemVer.parse("v0.2.0-dev.1")!!
        val v020Dev2 = SemVer.parse("v0.2.0-dev.2")!!
        val v020 = SemVer.parse("v0.2.0")!!

        assertTrue(v011 > v010)
        assertTrue(v020Dev1 > v011)
        assertTrue(v020Dev2 > v020Dev1)
        assertTrue(v020 > v020Dev2)
    }

    @Test
    fun releaseChannelDetectsNewerApk() {
        val payload = """
            {
              "tag_name": "v0.1.2",
              "name": "v0.1.2",
              "body": "Bug fixes",
              "prerelease": false,
              "draft": false,
              "html_url": "https://github.com/dstdoll/cadence/releases/tag/v0.1.2",
              "assets": [
                {
                  "name": "app-release.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://example.com/app-release.apk",
                  "size": 91860828
                }
              ]
            }
        """.trimIndent()

        val info = UpdateChecker.evaluateReleasePayload(payload, UpdateChannel.Release, "0.1.1")
        assertNotNull(info)
        assertEquals("v0.1.2", info!!.tag)
        assertEquals("https://example.com/app-release.apk", info.apkUrl)
        assertEquals(91860828L, info.apkSizeBytes)
        assertFalse(info.isChannelSwitch)
        assertFalse(info.isDowngrade)
    }

    @Test
    fun releaseChannelIgnoresSameOrOlderForStableBuild() {
        val payload = """
            {
              "tag_name": "v0.1.0",
              "name": "v0.1.0",
              "body": "",
              "prerelease": false,
              "draft": false,
              "html_url": "https://github.com/dstdoll/cadence/releases/tag/v0.1.0",
              "assets": []
            }
        """.trimIndent()

        assertNull(UpdateChecker.evaluateReleasePayload(payload, UpdateChannel.Release, "0.1.0"))
        assertNull(UpdateChecker.evaluateReleasePayload(payload, UpdateChannel.Release, "0.1.1"))
    }

    @Test
    fun devChannelPicksPrerelease() {
        val payload = """
            [
              {
                "tag_name": "v0.2.0-dev.1",
                "name": "v0.2.0-dev.1",
                "body": "Dev build",
                "prerelease": true,
                "draft": false,
                "html_url": "https://github.com/dstdoll/cadence/releases/tag/v0.2.0-dev.1",
                "assets": [
                  {
                    "name": "app-release.apk",
                    "content_type": "application/vnd.android.package-archive",
                    "browser_download_url": "https://example.com/dev.apk",
                    "size": 12345
                  }
                ]
              }
            ]
        """.trimIndent()

        val info = UpdateChecker.evaluateReleasePayload(payload, UpdateChannel.Dev, "0.1.1")
        assertNotNull(info)
        assertTrue(info!!.prerelease)
        assertEquals("v0.2.0-dev.1", info.tag)
    }

    @Test
    fun devUserSwitchingToReleaseGetsLatestReleaseEvenIfOlder() {
        val payload = """
            {
              "tag_name": "v0.1.0",
              "name": "v0.1.0",
              "body": "Stable",
              "prerelease": false,
              "draft": false,
              "html_url": "https://github.com/dstdoll/cadence/releases/tag/v0.1.0",
              "assets": []
            }
        """.trimIndent()

        val info = UpdateChecker.evaluateReleasePayload(payload, UpdateChannel.Release, "0.2.0-dev.1")
        assertNotNull(info)
        assertTrue(info!!.isChannelSwitch)
        assertTrue(info.isDowngrade)
    }

    @Test
    fun devChannelPicksHighestSemVerEvenAfterNewerStableHotfix() {
        val payload = """
            [
              {
                "tag_name": "v0.1.8",
                "name": "v0.1.8",
                "body": "Hotfix published after dev",
                "prerelease": false,
                "draft": false,
                "html_url": "https://github.com/dstdoll/cadence/releases/tag/v0.1.8",
                "assets": []
              },
              {
                "tag_name": "v0.2.0-dev.1",
                "name": "v0.2.0-dev.1",
                "body": "Next feature dev",
                "prerelease": true,
                "draft": false,
                "html_url": "https://github.com/dstdoll/cadence/releases/tag/v0.2.0-dev.1",
                "assets": []
              }
            ]
        """.trimIndent()

        val info = UpdateChecker.evaluateReleasePayload(payload, UpdateChannel.Dev, "0.1.7")
        assertNotNull(info)
        assertEquals("v0.2.0-dev.1", info!!.tag)
        assertTrue(info.prerelease)
    }
}
