package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseUpdateInfo(
    val version: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val fileSizeBytes: Long
)

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data class UpToDate(val currentVersion: String) : AppUpdateUiState
    data class Available(val info: ReleaseUpdateInfo) : AppUpdateUiState
    data class Downloading(
        val version: String,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateUiState
    data class ReadyToInstall(val version: String, val apkFile: File) : AppUpdateUiState
    data class NeedsInstallPermission(val version: String, val apkFile: File) : AppUpdateUiState
    data class Error(val message: String) : AppUpdateUiState
}

enum class ApkInstallResult {
    LAUNCHED_INSTALLER,
    NEEDS_PERMISSION,
    ERROR
}

class AppUpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkLatestRelease(
        repo: String,
        currentVersion: String
    ): Result<ReleaseUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedRepo = repo.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .trim('/')
            require(normalizedRepo.count { it == '/' } == 1 && !normalizedRepo.startsWith("usuario/")) {
                "Configura tu repositorio GitHub (ej. tu-usuario/AndAmp) en MISC → Actualizaciones"
            }

            val req = Request.Builder()
                .url("https://api.github.com/repos/$normalizedRepo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AndAmp-Updater/$currentVersion")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    return@use null
                }
                if (!resp.isSuccessful) {
                    error("Error HTTP ${resp.code} al comprobar actualizaciones")
                }
                val body = resp.body?.string().orEmpty()
                parseLatestReleaseJson(body, currentVersion)
            }
        }
    }

    suspend fun downloadApk(
        info: ReleaseUpdateInfo,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && old.name.endsWith(".apk")) {
                    runCatching { old.delete() }
                }
            }

            val safeVer = info.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val targetFile = File(updatesDir, "AndAmp-$safeVer.apk")

            val req = Request.Builder()
                .url(info.apkUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "AndAmp-Updater")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("No se pudo descargar el APK (HTTP ${resp.code})")
                }
                val body = resp.body ?: error("Respuesta de descarga vacía")
                val total = body.contentLength().takeIf { it > 0L } ?: info.fileSizeBytes
                var downloaded = 0L
                val buf = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val fraction = if (total > 0L) {
                                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            onProgress(fraction, downloaded, total)
                        }
                        output.flush()
                    }
                }
            }
            targetFile
        }
    }

    fun launchApkInstaller(apkFile: File): ApkInstallResult {
        if (!apkFile.exists()) return ApkInstallResult.ERROR

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return ApkInstallResult.NEEDS_PERMISSION
        }

        return runCatching {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            ApkInstallResult.LAUNCHED_INSTALLER
        }.getOrElse { ApkInstallResult.ERROR }
    }

    fun openInstallUnknownAppsSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    companion object {
        fun parseLatestReleaseJson(jsonText: String, currentVersion: String): ReleaseUpdateInfo? {
            if (jsonText.isBlank()) return null
            val obj = JSONObject(jsonText)
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) {
                return null
            }
            val rawTag = obj.optString("tag_name").trim()
            if (rawTag.isBlank()) return null
            val cleanRemote = cleanVersion(rawTag)
            if (!isNewerVersion(cleanRemote, currentVersion)) {
                return null
            }

            val assets = obj.optJSONArray("assets") ?: return null
            var chosenUrl = ""
            var chosenSize = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    val isReleaseApk = name.contains("release", ignoreCase = true)
                    if (chosenUrl.isBlank() || isReleaseApk) {
                        chosenUrl = url
                        chosenSize = asset.optLong("size", 0L)
                        if (isReleaseApk) break
                    }
                }
            }
            if (chosenUrl.isBlank()) return null

            return ReleaseUpdateInfo(
                version = cleanRemote,
                releaseName = obj.optString("name").ifBlank { "AndAmp v$cleanRemote" },
                releaseNotes = obj.optString("body").ifBlank { "Nueva versión disponible de AndAmp." },
                apkUrl = chosenUrl,
                fileSizeBytes = chosenSize
            )
        }

        fun cleanVersion(raw: String): String =
            raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .trim()

        fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
            val remoteParts = cleanVersion(remoteVersion)
                .split('.')
                .mapNotNull { it.toIntOrNull() }
            val currentParts = cleanVersion(currentVersion)
                .split('.')
                .mapNotNull { it.toIntOrNull() }
            if (remoteParts.isEmpty() || currentParts.isEmpty()) return false
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}
