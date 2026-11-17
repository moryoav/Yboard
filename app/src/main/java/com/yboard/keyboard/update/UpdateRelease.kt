package com.yboard.keyboard.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UpdateRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val size: Long,
    val sha256: String,
    val downloadUrl: String,
) {
    fun isNewerThan(installedCode: Long) = versionCode > installedCode
    fun toJson(): String = JSONObject().apply {
        put("tag", tag); put("versionName", versionName); put("versionCode", versionCode)
        put("apkName", apkName); put("size", size); put("sha256", sha256); put("downloadUrl", downloadUrl)
    }.toString()

    companion object {
        const val REPOSITORY = "moryoav/Yboard"
        const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
        const val LATEST_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val APP_ID = "com.yboard.keyboard"
        const val MAX_APK_BYTES = 64L * 1024 * 1024

        private fun validateTag(tag: String) {
            require(Regex("v[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:-[A-Za-z0-9.-]+)?").matches(tag)) { "Invalid release tag" }
        }

        fun trustedAssetUrl(url: String, tag: String, name: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.isHttps && parsed.host == "github.com" && parsed.port == 443 &&
                parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null &&
                parsed.pathSegments == listOf("moryoav", "Yboard", "releases", "download", tag, name)
        }

        private fun asset(release: JSONObject, name: String): JSONObject {
            val assets = release.getJSONArray("assets")
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name") == name && it.optString("state") == "uploaded" }
            require(matches.size == 1) { "The release is missing $name" }
            val found = matches.single()
            require(trustedAssetUrl(found.getString("browser_download_url"), release.getString("tag_name"), name)) {
                "Unexpected release download address"
            }
            return found
        }

        fun manifestUrl(releaseJson: String): String {
            val release = JSONObject(releaseJson)
            require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Only stable releases can be installed" }
            validateTag(release.getString("tag_name"))
            val manifest = asset(release, "update.json")
            require(manifest.getLong("size") in 1..65536) { "Invalid update information size" }
            return manifest.getString("browser_download_url")
        }

        fun parse(releaseJson: String, manifestJson: String): UpdateRelease {
            manifestUrl(releaseJson)
            val release = JSONObject(releaseJson)
            val manifest = JSONObject(manifestJson)
            require(manifest.getInt("schemaVersion") == 1 && manifest.getString("applicationId") == APP_ID) {
                "This release is not a Yboard update"
            }
            val tag = release.getString("tag_name")
            require(manifest.getString("tag") == tag) { "The update information does not match the release" }
            val name = manifest.getString("apkName")
            require(name == "Yboard-$tag.apk") { "Unexpected APK name" }
            val apk = asset(release, name)
            require(apk.getLong("size") == manifest.getLong("size")) { "The APK size does not match the release" }
            return restore(JSONObject(manifest.toString()).put("downloadUrl", apk.getString("browser_download_url")).toString())
        }

        fun restore(json: String): UpdateRelease {
            val o = JSONObject(json)
            val tag = o.getString("tag")
            validateTag(tag)
            val info = UpdateRelease(tag, o.getString("versionName"), o.getLong("versionCode"), o.getString("apkName"),
                o.getLong("size"), o.getString("sha256").lowercase(), o.getString("downloadUrl"))
            require(info.versionCode > 0 && info.versionName.isNotBlank() && info.versionName.length <= 80) { "Invalid app version" }
            require(info.size in 1..MAX_APK_BYTES && Regex("[0-9a-f]{64}").matches(info.sha256)) { "Invalid APK checksum or size" }
            require(info.apkName == "Yboard-$tag.apk" && trustedAssetUrl(info.downloadUrl, tag, info.apkName)) { "Unexpected APK download address" }
            return info
        }

        fun verifyFile(file: File, info: UpdateRelease) {
            require(file.length() == info.size) { "The download is incomplete. Download the update again." }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == info.sha256) { "The download checksum does not match. Download the update again." }
        }
    }
}
