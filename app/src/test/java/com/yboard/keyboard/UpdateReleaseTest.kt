package com.yboard.keyboard

import com.yboard.keyboard.update.UpdateRelease
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateReleaseTest {
    private val tag = "v1.3.0"
    private val content = "synthetic APK bytes".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private fun url(name: String) = "https://github.com/moryoav/Yboard/releases/download/$tag/$name"
    private fun release() = JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(asset("update.json", 300)).put(asset("Yboard-$tag.apk", content.size)))
    private fun asset(name: String, size: Int) = JSONObject().put("name", name).put("state", "uploaded")
        .put("size", size).put("browser_download_url", url(name))
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("tag", tag).put("applicationId", "com.yboard.keyboard")
        .put("versionName", "1.3.0").put("versionCode", 461166).put("apkName", "Yboard-$tag.apk")
        .put("sha256", hash).put("size", content.size)
    private fun parse(r: JSONObject = release(), m: JSONObject = manifest()) = UpdateRelease.parse(r.toString(), m.toString())

    @Test fun usesAndroidVersionCodesRatherThanReleaseDatesOrVersionText() {
        val info = parse()
        assertTrue(info.isNewerThan(461164))
        assertFalse(info.isNewerThan(461166))
        assertFalse(info.isNewerThan(461167))
        assertEquals(url("update.json"), UpdateRelease.manifestUrl(release().toString()))
        assertEquals(info, UpdateRelease.restore(info.toJson()))
    }

    @Test fun rejectsDraftsAndPrereleases() {
        assertThrows(IllegalArgumentException::class.java) { parse(release().put("draft", true)) }
        assertThrows(IllegalArgumentException::class.java) { parse(release().put("prerelease", true)) }
    }

    @Test fun onlyAcceptsAssetsFromThisRepositoryAndRelease() {
        val valid = url("update.json")
        for (bad in listOf(valid.replace("https:", "http:"), valid.replace("github.com", "github.com.example.org"),
            valid.replace("moryoav", "someone-else"), valid.replace(tag, "v1.1.0"), "$valid?token=abc", "$valid#other",
            valid.replace("github.com/", "user@github.com/"))) {
            assertFalse(bad, UpdateRelease.trustedAssetUrl(bad, tag, "update.json"))
        }
        val r = release()
        r.getJSONArray("assets").getJSONObject(1).put("browser_download_url", "https://example.org/foreign.apk")
        assertThrows(IllegalArgumentException::class.java) { parse(r) }
    }

    @Test fun requiresMatchingAppReleaseAssetSizeAndChecksum() {
        val changes = listOf("applicationId" to "another.app", "tag" to "v1.2.0", "apkName" to "other.apk",
            "size" to 999, "sha256" to "bad", "versionCode" to 0, "schemaVersion" to 2)
        changes.forEach { (key, value) ->
            assertThrows("Rejected $key", IllegalArgumentException::class.java) { parse(m = manifest().put(key, value)) }
        }
    }

    @Test fun refusesMissingAndUnfinishedAssets() {
        val r = release()
        r.getJSONArray("assets").getJSONObject(1).put("state", "open")
        assertThrows(IllegalArgumentException::class.java) { parse(r) }
        assertThrows(IllegalArgumentException::class.java) { parse(release().put("assets", JSONArray())) }
    }

    @Test fun rejectsTamperedAndIncompleteDownloads() {
        val file = File.createTempFile("yboard-update", ".apk")
        try {
            file.writeBytes(content)
            UpdateRelease.verifyFile(file, parse())
            file.writeBytes(content.copyOf(content.size - 1))
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyFile(file, parse()) }
            file.writeBytes(content.copyOf().also { it[0] = (it[0] + 1).toByte() })
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verifyFile(file, parse()) }
        } finally { file.delete() }
    }
}
