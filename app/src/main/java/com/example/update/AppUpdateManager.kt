package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
        currentVersion: String,
        preferReleaseApk: Boolean = !BuildConfig.DEBUG
    ): Result<ReleaseUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedRepo = repo.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removeSuffix(".git")
                .trim('/')
            require(
                normalizedRepo.count { it == '/' } == 1 &&
                    !normalizedRepo.startsWith("usuario/", ignoreCase = true) &&
                    !normalizedRepo.startsWith("tu-usuario/", ignoreCase = true)
            ) {
                "Configura un repositorio GitHub válido (ej. icaro81/AndAmp) en MISC → Actualizaciones"
            }

            // 1) Try official /releases/latest first
            val latestReq = Request.Builder()
                .url("https://api.github.com/repos/$normalizedRepo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AndAmp-Updater/$currentVersion")
                .build()

            client.newCall(latestReq).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val body = resp.body?.string().orEmpty()
                        val obj = JSONObject(body)
                        val parsed = parseReleaseObject(
                            obj = obj,
                            currentVersion = currentVersion,
                            allowPrerelease = false,
                            preferReleaseApk = preferReleaseApk
                        )
                        if (parsed != null) return@runCatching parsed
                        // If /releases/latest already points to an APK-bearing release <= currentVersion,
                        // double-check list only if it lacked an APK asset.
                        val assets = obj.optJSONArray("assets")
                        val hasApk = assets != null && (0 until assets.length()).any { idx ->
                            assets.optJSONObject(idx)?.optString("name")?.endsWith(".apk", ignoreCase = true) == true
                        }
                        if (hasApk) return@runCatching null
                    }
                    404 -> Unit // Fall through to /releases list (handles prerelease-only repos)
                    403 -> error("Límite de consultas de GitHub alcanzado (HTTP 403). Intenta en unos minutos.")
                    else -> error("Error HTTP ${resp.code} al comprobar actualizaciones")
                }
            }

            // 2) Fallback: scan recent releases (supports pre-releases or CI releases without latest tag)
            val listReq = Request.Builder()
                .url("https://api.github.com/repos/$normalizedRepo/releases?per_page=10")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AndAmp-Updater/$currentVersion")
                .build()

            client.newCall(listReq).execute().use { resp ->
                if (resp.code == 404) {
                    error("Repositorio $normalizedRepo no encontrado o privado en GitHub.")
                }
                if (!resp.isSuccessful) {
                    error("Error HTTP ${resp.code} al consultar releases de $normalizedRepo")
                }
                val body = resp.body?.string().orEmpty()
                val arr = JSONArray(body)
                if (arr.length() == 0) {
                    error("Aún no hay ningún Release publicado en $normalizedRepo.")
                }
                parseReleasesListJson(
                    jsonArrayText = body,
                    currentVersion = currentVersion,
                    preferReleaseApk = preferReleaseApk
                )
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

            if (!isValidApkFile(targetFile)) {
                runCatching { targetFile.delete() }
                error("El archivo descargado no es un paquete APK válido.")
            }
            targetFile
        }
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(2)
                input.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
    }

    fun launchApkInstaller(apkFile: File): ApkInstallResult {
        if (!isValidApkFile(apkFile)) return ApkInstallResult.ERROR

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
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
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
        private val VERSION_FROM_ASSET_REGEX = Regex("""v?(\d+\.\d+(?:\.\d+)*)""")

        fun parseLatestReleaseJson(
            jsonText: String,
            currentVersion: String,
            preferReleaseApk: Boolean = false
        ): ReleaseUpdateInfo? {
            if (jsonText.isBlank()) return null
            val obj = JSONObject(jsonText)
            return parseReleaseObject(
                obj = obj,
                currentVersion = currentVersion,
                allowPrerelease = false,
                preferReleaseApk = preferReleaseApk
            )
        }

        fun parseReleasesListJson(
            jsonArrayText: String,
            currentVersion: String,
            preferReleaseApk: Boolean = false
        ): ReleaseUpdateInfo? {
            if (jsonArrayText.isBlank()) return null
            val array = JSONArray(jsonArrayText)
            var bestStable: ReleaseUpdateInfo? = null
            var bestAny: ReleaseUpdateInfo? = null

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft", false)) continue
                val isPrerelease = obj.optBoolean("prerelease", false)
                val candidate = parseReleaseObject(
                    obj = obj,
                    currentVersion = currentVersion,
                    allowPrerelease = true,
                    preferReleaseApk = preferReleaseApk
                ) ?: continue

                if (!isPrerelease) {
                    if (bestStable == null || isNewerVersion(candidate.version, bestStable.version)) {
                        bestStable = candidate
                    }
                }
                if (bestAny == null || isNewerVersion(candidate.version, bestAny.version)) {
                    bestAny = candidate
                }
            }
            return bestStable ?: bestAny
        }

        private fun parseReleaseObject(
            obj: JSONObject,
            currentVersion: String,
            allowPrerelease: Boolean,
            preferReleaseApk: Boolean
        ): ReleaseUpdateInfo? {
            if (obj.optBoolean("draft", false)) return null
            if (!allowPrerelease && obj.optBoolean("prerelease", false)) return null

            val assets = obj.optJSONArray("assets") ?: return null
            var chosenName = ""
            var chosenUrl = ""
            var chosenSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    val isReleaseApk = name.contains("-release", ignoreCase = true) ||
                        name.contains("_release", ignoreCase = true)
                    val matchesPreferred = if (preferReleaseApk) isReleaseApk else !isReleaseApk
                    if (chosenUrl.isBlank() || matchesPreferred) {
                        chosenName = name
                        chosenUrl = url
                        chosenSize = asset.optLong("size", 0L)
                        if (matchesPreferred) break
                    }
                }
            }
            if (chosenUrl.isBlank()) return null

            val rawTag = obj.optString("tag_name").trim()
            val cleanRemote = extractSemver(rawTag).ifBlank { extractSemver(chosenName) }
            if (cleanRemote.isBlank() || !isNewerVersion(cleanRemote, currentVersion)) {
                return null
            }

            return ReleaseUpdateInfo(
                version = cleanRemote,
                releaseName = obj.optString("name").ifBlank { "AndAmp v$cleanRemote" },
                releaseNotes = obj.optString("body").ifBlank { "Nueva versión disponible de AndAmp." },
                apkUrl = chosenUrl,
                fileSizeBytes = chosenSize
            )
        }

        private fun extractSemver(raw: String): String {
            val cleaned = cleanVersion(raw)
            if (cleaned.split('.').all { it.toIntOrNull() != null } && cleaned.isNotBlank()) {
                return cleaned
            }
            return VERSION_FROM_ASSET_REGEX.find(raw)?.groupValues?.getOrNull(1).orEmpty()
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
