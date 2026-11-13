package com.framewise.app.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.framewise.app.BuildConfig
import com.framewise.app.settings.SettingsRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(
    val tagName: String,
    val version: SemanticVersion,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256Url: String?
)

sealed interface UpdateCheckOutcome {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckOutcome
    data class UpToDate(val currentVersion: String) : UpdateCheckOutcome
    data object RateLimited : UpdateCheckOutcome
    data class Error(val message: String) : UpdateCheckOutcome
}

sealed interface DownloadOutcome {
    data class Verified(val apkFile: File, val sha256Hex: String) : DownloadOutcome
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadOutcome
    data class Error(val message: String) : DownloadOutcome
}

sealed interface InstallOutcome {
    data object SessionStarted : InstallOutcome
    data object NeedsInstallPermission : InstallOutcome
    data class Error(val message: String) : InstallOutcome
}

object UpdaterSecurityPolicy {
    private val ALLOWED_CDN_HOSTS = setOf(
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    fun isAllowedApiOrRepoUrl(rawUrl: String, owner: String, repo: String): Boolean {
        if (owner.isBlank() || repo.isBlank()) return false
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path ?: return false

        return when (host) {
            "api.github.com" -> path.startsWith("/repos/$owner/$repo/", ignoreCase = false)
            "github.com" -> path.startsWith("/$owner/$repo/", ignoreCase = false)
            else -> false
        }
    }

    fun isAllowedRedirectTarget(rawUrl: String, owner: String, repo: String): Boolean {
        if (isAllowedApiOrRepoUrl(rawUrl, owner, repo)) return true
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in ALLOWED_CDN_HOSTS
    }

    fun parseSha256Hex(rawContent: String): String? {
        val match = Regex("""\b([0-9a-fA-F]{64})\b""").find(rawContent) ?: return null
        return match.groupValues[1].lowercase()
    }

    fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class GitHubReleaseUpdater(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val owner: String = BuildConfig.GITHUB_OWNER,
    private val repo: String = BuildConfig.GITHUB_REPO
) {

    suspend fun checkForUpdate(
        currentVersion: String = BuildConfig.VERSION_NAME,
        forceRefresh: Boolean = false
    ): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        if (!UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(apiUrl, owner, repo)) {
            return@withContext UpdateCheckOutcome.Error("Invalid GitHub repository configuration")
        }

        val currentSemVer = SemanticVersion.parseOrNull(currentVersion)
            ?: return@withContext UpdateCheckOutcome.Error("Invalid current version '$currentVersion'")

        val settings = settingsRepository.settingsFlow.first()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Framewise-Android/$currentVersion")
                if (!forceRefresh && !settings.cachedReleaseEtag.isNullOrBlank()) {
                    setRequestProperty("If-None-Match", settings.cachedReleaseEtag)
                }
            }

            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    val cachedJson = settings.cachedReleaseJson
                        ?: return@withContext UpdateCheckOutcome.UpToDate(currentVersion)
                    return@withContext evaluateReleaseJson(cachedJson, currentSemVer, currentVersion)
                }

                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val etag = connection.getHeaderField("ETag")
                    settingsRepository.cacheReleaseResponse(etag, body)
                    return@withContext evaluateReleaseJson(body, currentSemVer, currentVersion)
                }

                HttpURLConnection.HTTP_FORBIDDEN, 429 -> {
                    return@withContext UpdateCheckOutcome.RateLimited
                }

                HttpURLConnection.HTTP_NOT_FOUND -> {
                    return@withContext UpdateCheckOutcome.UpToDate(currentVersion)
                }

                else -> {
                    return@withContext UpdateCheckOutcome.Error("HTTP $code")
                }
            }
        } catch (t: Throwable) {
            return@withContext UpdateCheckOutcome.Error(t.message ?: "Network error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun evaluateReleaseJson(
        rawJson: String,
        currentSemVer: SemanticVersion,
        currentVersionRaw: String
    ): UpdateCheckOutcome {
        val release = parseReleaseJson(rawJson, owner, repo)
            ?: return UpdateCheckOutcome.UpToDate(currentVersionRaw)

        return if (release.version > currentSemVer) {
            UpdateCheckOutcome.UpdateAvailable(release)
        } else {
            UpdateCheckOutcome.UpToDate(currentVersionRaw)
        }
    }

    suspend fun downloadAndVerifyApk(
        release: ReleaseInfo,
        onProgress: (Int) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        if (!UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(release.apkUrl, owner, repo)) {
            return@withContext DownloadOutcome.Error("Blocked untrusted APK URL")
        }
        val shaUrl = release.sha256Url
            ?: return@withContext DownloadOutcome.Error("Release is missing a .sha256 verification file")
        if (!UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(shaUrl, owner, repo)) {
            return@withContext DownloadOutcome.Error("Blocked untrusted SHA-256 URL")
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeTag = release.tagName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val partFile = File(updatesDir, "Framewise-$safeTag.apk.part")
        val finalFile = File(updatesDir, "Framewise-$safeTag.apk")

        return@withContext try {
            val expectedShaText = downloadTextWithPolicy(shaUrl)
            val expectedSha = UpdaterSecurityPolicy.parseSha256Hex(expectedShaText)
                ?: return@withContext DownloadOutcome.Error("Invalid SHA-256 file format")

            downloadFileWithResume(
                urlString = release.apkUrl,
                targetFile = partFile,
                expectedTotalBytes = release.apkSizeBytes,
                onProgress = onProgress
            )

            val actualSha = UpdaterSecurityPolicy.computeSha256Hex(partFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                partFile.delete()
                return@withContext DownloadOutcome.ChecksumMismatch(expectedSha, actualSha)
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            onProgress(100)
            DownloadOutcome.Verified(finalFile, actualSha)
        } catch (t: Throwable) {
            DownloadOutcome.Error(t.message ?: "Download failed")
        }
    }

    fun installApk(apkFile: File): InstallOutcome {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return InstallOutcome.Error("APK file is missing")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallOutcome.NeedsInstallPermission
        }

        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                FileInputStream(apkFile).use { input ->
                    session.openWrite("framewise_update.apk", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, PackageInstallReceiver::class.java).apply {
                    action = PackageInstallReceiver.ACTION_INSTALL_RESULT
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pendingIntent.intentSender)
            }
            InstallOutcome.SessionStarted
        } catch (_: Throwable) {
            launchFileProviderInstaller(apkFile)
        }
    }

    private fun launchFileProviderInstaller(apkFile: File): InstallOutcome {
        return try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            InstallOutcome.SessionStarted
        } catch (t: Throwable) {
            InstallOutcome.Error(t.message ?: "Installer launch failure")
        }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun openValidatedConnection(
        initialUrl: String,
        rangeStartBytes: Long = 0L
    ): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects <= 5) {
            val allowed = if (redirects == 0) {
                UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(currentUrl, owner, repo)
            } else {
                UpdaterSecurityPolicy.isAllowedRedirectTarget(currentUrl, owner, repo)
            }
            require(allowed) { "Blocked redirect or untrusted URL: $currentUrl" }

            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Framewise-Android/${BuildConfig.VERSION_NAME}")
                if (rangeStartBytes > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStartBytes-")
                }
            }
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                require(!next.isNullOrBlank()) { "Redirect missing Location header" }
                currentUrl = URI(currentUrl).resolve(next).toString()
                redirects++
                continue
            }
            return conn
        }
        error("Too many redirects while downloading update")
    }

    private fun downloadTextWithPolicy(urlString: String): String {
        val conn = openValidatedConnection(urlString)
        try {
            val code = conn.responseCode
            require(code == HttpURLConnection.HTTP_OK) { "SHA-256 download returned HTTP $code" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFileWithResume(
        urlString: String,
        targetFile: File,
        expectedTotalBytes: Long,
        onProgress: (Int) -> Unit
    ) {
        val existingBytes = if (targetFile.exists()) targetFile.length() else 0L
        val conn = openValidatedConnection(urlString, rangeStartBytes = existingBytes)
        try {
            val code = conn.responseCode
            val isResumed = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            if (!isResumed && code != HttpURLConnection.HTTP_OK) {
                // Handle stale range by retrying from scratch if server replied 416.
                if (code == 416 && existingBytes > 0L) {
                    conn.disconnect()
                    targetFile.delete()
                    downloadFileWithResume(urlString, targetFile, expectedTotalBytes, onProgress)
                    return
                }
                error("APK download failed with HTTP $code")
            }

            val contentLength = conn.contentLengthLong
            val totalSize = when {
                isResumed && contentLength > 0L -> existingBytes + contentLength
                contentLength > 0L -> contentLength
                else -> expectedTotalBytes
            }

            var downloaded = if (isResumed) existingBytes else 0L
            conn.inputStream.use { input ->
                FileOutputStream(targetFile, isResumed).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalSize > 0L) {
                            val pct = ((downloaded * 100L) / totalSize).toInt().coerceIn(0, 99)
                            onProgress(pct)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parseReleaseJson(rawJson: String, owner: String, repo: String): ReleaseInfo? {
            val root = runCatching { JsonParser.parseString(rawJson).asJsonObject }.getOrNull() ?: return null
            val tagName = root.get("tag_name")?.asString?.trim() ?: return null
            val semVer = SemanticVersion.parseOrNull(tagName) ?: return null
            val body = root.get("body")?.asString.orEmpty()
            val htmlUrl = root.get("html_url")?.asString.orEmpty()
            val assets = root.getAsJsonArray("assets") ?: return null

            var apkName: String? = null
            var apkUrl: String? = null
            var apkSize = 0L
            var sha256Url: String? = null

            for (elem in assets) {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.asString ?: continue
                val url = obj.get("browser_download_url")?.asString ?: continue
                if (!UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(url, owner, repo)) continue

                when {
                    name.endsWith(".apk", ignoreCase = true) && apkUrl == null -> {
                        apkName = name
                        apkUrl = url
                        apkSize = obj.get("size")?.asLong ?: 0L
                    }

                    name.endsWith(".sha256", ignoreCase = true) -> {
                        if (sha256Url == null || (apkName != null && name.startsWith(apkName))) {
                            sha256Url = url
                        }
                    }
                }
            }

            val resolvedApkName = apkName ?: return null
            val resolvedApkUrl = apkUrl ?: return null

            return ReleaseInfo(
                tagName = tagName,
                version = semVer,
                releaseNotes = body,
                htmlUrl = htmlUrl,
                apkName = resolvedApkName,
                apkUrl = resolvedApkUrl,
                apkSizeBytes = apkSize,
                sha256Url = sha256Url
            )
        }
    }
}
