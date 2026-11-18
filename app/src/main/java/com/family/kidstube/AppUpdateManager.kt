package com.family.kidstube

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Checks and downloads app updates only; video browsing and playback stay local. */
class AppUpdateManager(private val activity: MainActivity) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var pendingInstall: File? = null

    fun checkForUpdates() {
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        executor.execute {
            val release = runCatching { fetchLatestRelease() }.getOrNull()
            mainHandler.post {
                if (closed || activity.isFinishing || activity.isDestroyed) return@post
                if (release == null) {
                    showToast(R.string.update_check_failed)
                } else if (release.versionCode <= installedVersionCode()) {
                    showToast(R.string.update_already_current)
                } else {
                    showUpdateDialog(release)
                }
            }
        }
    }

    fun onResume() {
        val file = pendingInstall ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstall = null
            launchInstaller(file)
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun fetchLatestRelease(): Release? {
        val connection = URL(RELEASES_API).openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "KidsTube-Android")
        return try {
            if (connection.responseCode !in 200..299) return null
            val releases = JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            var best: Release? = null
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val tag = release.optString("tag_name")
                val code = versionCodeForTag(tag) ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                for (assetIndex in 0 until assets.length()) {
                    val asset = assets.optJSONObject(assetIndex) ?: continue
                    if (asset.optString("name") != APK_ASSET_NAME) continue
                    val url = asset.optString("browser_download_url")
                    if (!url.startsWith(RELEASE_DOWNLOAD_PREFIX)) continue
                    val candidate = Release(tag.removePrefix("v"), code, url)
                    if (best == null || candidate.versionCode > best!!.versionCode) best = candidate
                }
            }
            best
        } finally {
            connection.disconnect()
        }
    }

    private fun showUpdateDialog(release: Release) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(activity.getString(R.string.update_available_message, release.versionName))
            .setPositiveButton(R.string.update_download_install) { _, _ -> downloadAndInstall(release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(release: Release) {
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        executor.execute {
            val file = runCatching { downloadApk(release.downloadUrl) }.getOrNull()
            mainHandler.post {
                if (closed || activity.isFinishing || activity.isDestroyed) return@post
                if (file == null) {
                    showToast(R.string.update_download_failed)
                } else if (!isValidSignedUpdate(file)) {
                    file.delete()
                    showToast(R.string.update_signature_mismatch)
                } else {
                    requestInstallPermissionOrLaunch(file)
                }
            }
        }
    }

    private fun downloadApk(downloadUrl: String): File {
        if (!downloadUrl.startsWith(RELEASE_DOWNLOAD_PREFIX)) {
            throw IOException("Unexpected download host")
        }
        val connection = URL(downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) throw IOException("Download failed")
            val expectedSize = connection.contentLengthLong
            if (expectedSize > MAX_APK_SIZE_BYTES) throw IOException("APK is too large")
            val directory = File(activity.cacheDir, "updates")
            if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create update folder")
            val apk = File(directory, APK_ASSET_NAME)
            var totalBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            connection.inputStream.buffered().use { input ->
                FileOutputStream(apk).use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_APK_SIZE_BYTES) throw IOException("APK is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (totalBytes == 0L) throw IOException("Empty APK")
            return apk
        } catch (error: Exception) {
            File(activity.cacheDir, "updates/$APK_ASSET_NAME").delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidSignedUpdate(apk: File): Boolean = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val downloaded = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return@runCatching false
        if (downloaded.packageName != activity.packageName ||
            versionCode(downloaded) <= installedVersionCode()
        ) return@runCatching false
        val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
        signingCertificates(installed).isNotEmpty() &&
            signingCertificates(installed) == signingCertificates(downloaded)
    }.getOrDefault(false)

    private fun requestInstallPermissionOrLaunch(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstall = apk
            AlertDialog.Builder(activity)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_message)
                .setPositiveButton(R.string.install_permission_button) { _, _ ->
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(settingsIntent)
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
        } else {
            launchInstaller(apk)
        }
    }

    private fun launchInstaller(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (_: Exception) {
            showToast(R.string.update_install_failed)
        }
    }

    private fun installedVersionCode(): Long {
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return versionCode(packageInfo)
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    } else {
        info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
    }

    private fun versionCodeForTag(tag: String): Long? {
        val match = VERSION_TAG.matchEntire(tag) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val minor = match.groupValues[2].toLongOrNull() ?: return null
        val patch = match.groupValues[3].toLongOrNull() ?: return null
        if (major > 2100 || minor > 999 || patch > 999) return null
        val code = major * 1_000_000L + minor * 1_000L + patch
        return code.takeIf { it in 1..2_100_000_000L }
    }

    private fun showToast(messageId: Int) {
        Toast.makeText(activity, messageId, Toast.LENGTH_SHORT).show()
    }

    private data class Release(val versionName: String, val versionCode: Long, val downloadUrl: String)

    companion object {
        private const val RELEASES_API = "https://api.github.com/repos/Hussamobeed/kidstube/releases?per_page=20"
        private const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/Hussamobeed/kidstube/releases/download/"
        private const val APK_ASSET_NAME = "kidstube-release.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val MAX_APK_SIZE_BYTES = 300L * 1024L * 1024L
        private val VERSION_TAG = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")
    }
}
