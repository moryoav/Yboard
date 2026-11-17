package com.yboard.keyboard.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.update.AppUpdater
import com.yboard.keyboard.update.UpdateRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdatesSection() {
    val context = LocalContext.current
    val updater = remember { AppUpdater(context.applicationContext) }
    val installedCode = remember { AppUpdater.versionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }
    val scope = rememberCoroutineScope()
    var releaseJson by rememberSaveable { mutableStateOf<String?>(null) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingInstall by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadedCode by rememberSaveable { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    val release = releaseJson?.let { runCatching { UpdateRelease.restore(it) }.getOrNull() }

    fun install(info: UpdateRelease) {
        scope.launch {
            busy = "Verifying update…"
            error = null
            try {
                val file = updater.cachedFile(info)
                withContext(Dispatchers.IO) { updater.verifyApk(file, info) }
                context.startActivity(updater.installerIntent(file))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = AppUpdater.friendly(e); downloadedCode = null }
            finally { busy = null }
        }
    }

    val allowInstalls = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pending = pendingInstall?.let { json -> runCatching { UpdateRelease.restore(json) }.getOrNull() }
        pendingInstall = null
        if (context.packageManager.canRequestPackageInstalls() && pending != null) install(pending)
        else error = "Allow Yboard to install updates, then tap Install update again."
    }

    fun beginInstall(info: UpdateRelease) {
        if (context.packageManager.canRequestPackageInstalls()) install(info)
        else {
            pendingInstall = info.toJson()
            try {
                allowInstalls.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) {
                pendingInstall = null
                error = "Open Android settings and allow Yboard to install unknown apps, then try again."
            }
        }
    }

    Section("Updates", searchable = true) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val newer = release?.isNewerThan(installedCode) == true
            val ready = release != null && downloadedCode == release.versionCode && updater.cachedFile(release).isFile
            val status = when {
                busy != null -> busy!!
                newer && ready -> "Version ${release!!.versionName} is ready to install."
                newer -> "Version ${release!!.versionName} is available."
                checked && release == null -> "No public release is available yet."
                checked && release!!.versionCode < installedCode -> "This version is newer than the latest public release."
                checked -> "You're up to date."
                else -> "Check the public GitHub releases for a newer version of Yboard."
            }
            Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
            if (busy == "Downloading update…") {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("$progress%", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (newer) {
                Button(onClick = {
                    val info = release ?: return@Button
                    if (ready) beginInstall(info)
                    else scope.launch {
                        busy = "Downloading update…"
                        progress = 0
                        error = null
                        try {
                            withContext(Dispatchers.IO) {
                                updater.download(info) { percent -> withContext(Dispatchers.Main) { progress = percent } }
                            }
                            downloadedCode = info.versionCode
                            busy = null
                            beginInstall(info)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = AppUpdater.friendly(e) }
                        finally { if (busy == "Downloading update…") busy = null }
                    }
                }, enabled = busy == null) { Text(if (ready) "Install update" else "Download and install") }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    busy = "Checking for updates…"
                    error = null
                    try {
                        val latest = withContext(Dispatchers.IO) { updater.check() }
                        releaseJson = latest?.toJson()
                        checked = true
                        downloadedCode = null
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = AppUpdater.friendly(e) }
                    finally { busy = null }
                }
            }, enabled = busy == null) { Text("Check for updates") }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateRelease.RELEASES_URL))) }
                    .onFailure { error = "No browser is available to open GitHub." }
            }) { Text("View releases on GitHub") }
            Text("Android asks you to approve installation. Your settings, words and saved API key are kept.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
