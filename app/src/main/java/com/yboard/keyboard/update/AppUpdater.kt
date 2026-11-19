package com.yboard.keyboard.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateFileProvider : FileProvider()

/** Only public release data is sent to GitHub. No keyboard context or API credentials are used. */
class AppUpdater(private val context: Context, network: Network? = null) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .apply {
            if (network != null) {
                socketFactory(network.socketFactory)
                dns(object : Dns {
                    override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                })
            }
        }.build()

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Yboard-Update-Checker")
        .header("Cache-Control", "no-cache").build()

    private suspend fun readJson(url: String, limit: Int): String? = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request(url))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use { readJsonResponse(it, url == UpdateRelease.LATEST_API, limit) }
                })
            }
        })
    }

    private fun readJsonResponse(response: Response, allowMissing: Boolean, limit: Int): String? {
        if (response.code == 404 && allowMissing) return null
        if (response.code == 403 || response.code == 429) throw IOException("GitHub is busy or its request limit was reached. Try again later.")
        if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}. Try again later.")
        val body = response.body ?: throw IOException("GitHub returned an empty response.")
        return body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (output.size() <= limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= limit) { "GitHub returned too much update information." }
            bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
    }

    suspend fun check(): UpdateRelease? {
        val release = readJson(UpdateRelease.LATEST_API, 512 * 1024) ?: return null
        val manifest = readJson(UpdateRelease.manifestUrl(release), 65536) ?: error("Missing update information")
        return UpdateRelease.parse(release, manifest)
    }

    fun cachedFile(info: UpdateRelease) = File(File(context.cacheDir, "updates"), "${info.versionCode}.apk")

    suspend fun download(info: UpdateRelease, progress: suspend (Int) -> Unit): File {
        val dest = cachedFile(info)
        dest.parentFile?.mkdirs()
        if (dest.exists() && runCatching { verifyApk(dest, info) }.isSuccess) return dest
        val part = File(dest.parentFile, "${info.versionCode}.part")
        try {
            http.newCall(request(info.downloadUrl)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The update download returned HTTP ${response.code}.")
                val body = response.body ?: throw IOException("The update download was empty.")
                require(body.contentLength() == -1L || body.contentLength() == info.size) { "Unexpected update download size" }
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var received = 0L
                        var lastProgress = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= info.size) { "The update download is larger than expected." }
                            output.write(buffer, 0, count)
                            val percent = (100 * received / info.size).toInt()
                            if (percent != lastProgress) { progress(percent); lastProgress = percent }
                        }
                    }
                }
            }
            verifyApk(part, info)
            if (dest.exists()) check(dest.delete()) { "Could not replace the cached update" }
            check(part.renameTo(dest)) { "Could not save the update" }
            return dest
        } finally { part.delete() }
    }

    @Suppress("DEPRECATION")
    fun verifyApk(file: File, info: UpdateRelease) {
        UpdateRelease.verifyFile(file, info)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Android could not read this update.")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(candidate.packageName == context.packageName && candidate.versionName == info.versionName && versionCode(candidate) == info.versionCode) {
            "The APK does not match the published Yboard version."
        }
        fun signatures(p: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        val expected = signatures(installed)
        require(expected.isNotEmpty() && signatures(candidate) == expected) { "This update uses a different signing key. It cannot replace this Yboard installation." }
        require(info.versionCode >= versionCode(installed)) { "This update is older than the installed app." }
    }

    fun installerIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        fun installedVersionCode(context: Context): Long =
            versionCode(context.packageManager.getPackageInfo(context.packageName, 0))

        @Suppress("DEPRECATION")
        fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun friendly(error: Throwable): String = when (error) {
            is IllegalArgumentException, is IllegalStateException, is IOException -> error.message ?: "Could not check for updates. Try again."
            else -> "Could not check for updates. Check your internet connection and try again."
        }
    }
}
