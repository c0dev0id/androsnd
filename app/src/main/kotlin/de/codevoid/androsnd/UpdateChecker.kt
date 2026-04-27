package de.codevoid.androsnd

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class UpdateChecker(private val context: Context) {

    data class ReleaseInfo(
        val tagName: String,
        val isPrerelease: Boolean,
        val apkDownloadUrl: String?,
        val apkVersion: String?
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val GITHUB_OWNER = "c0dev0id"
        const val GITHUB_REPO = "androsnd"
        const val API_BASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val POLL_INTERVAL_MS = 500L
    }

    fun installedVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /** True when this build came from the nightly/dev channel (versionName starts with "dev"). */
    fun isNightlyBuild(): Boolean = installedVersion().startsWith("dev")

    /** Returns true when onlineTag represents a strictly newer version than installedVersion. */
    fun isNewer(onlineTag: String, installedVersion: String): Boolean {
        // Nightly channel: tags are "dev-<sha>" (or just "dev"). Numeric semver comparison
        // is meaningless for SHAs, so fall back to string inequality of the trimmed tag.
        if (onlineTag.startsWith("dev") || installedVersion.startsWith("dev")) {
            return onlineTag.trim() != installedVersion.trim()
        }
        val online = parseVersion(onlineTag)
        val installed = parseVersion(installedVersion)
        val len = maxOf(online.size, installed.size)
        for (i in 0 until len) {
            val o = online.getOrElse(i) { 0 }
            val ins = installed.getOrElse(i) { 0 }
            if (o > ins) return true
            if (o < ins) return false
        }
        return false
    }

    private fun parseVersion(raw: String): List<Int> =
        raw.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }

    /**
     * Fetches the latest release from GitHub.
     * If [includePrerelease] is true, the latest pre-release is returned (first entry in
     * /releases list where prerelease == true); otherwise the stable /releases/latest is used.
     */
    fun fetchRelease(
        includePrerelease: Boolean,
        onResult: (ReleaseInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val json: JSONObject? = if (includePrerelease) {
                    fetchPrerelease()
                } else {
                    fetchLatestStable()
                }

                if (json == null) {
                    mainHandler.post { onError("No matching release found.") }
                    return@execute
                }

                val tagName = json.optString("tag_name", "")
                val prerelease = json.optBoolean("prerelease", false)
                val (apkUrl, apkVersion) = findApkAsset(json)
                mainHandler.post { onResult(ReleaseInfo(tagName, prerelease, apkUrl, apkVersion)) }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun fetchLatestStable(): JSONObject? {
        val text = httpGet("$API_BASE/latest") ?: return null
        return JSONObject(text)
    }

    private fun fetchPrerelease(): JSONObject? {
        val text = httpGet(API_BASE) ?: return null
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optBoolean("prerelease", false)) {
                return obj
            }
        }
        return null
    }

    /** Returns (downloadUrl, apkVersion) extracted from the first .apk asset, or (null, null). */
    private fun findApkAsset(release: JSONObject): Pair<String?, String?> {
        val assets = release.optJSONArray("assets") ?: return Pair(null, null)
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk")) {
                val url = asset.optString("browser_download_url").takeIf { it.isNotEmpty() }
                val version = name.removePrefix("androsnd-").removeSuffix(".apk").takeIf { it.isNotEmpty() }
                return Pair(url, version)
            }
        }
        return Pair(null, null)
    }

    private fun httpGet(urlString: String): String? {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        return try {
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else {
                throw RuntimeException("HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Enqueues an APK download via [DownloadManager] and polls for progress.
     * Returns the download ID so the caller can cancel it if needed.
     */
    fun downloadApk(
        url: String,
        fileName: String,
        onProgress: (percent: Int) -> Unit,
        onComplete: (downloadId: Long) -> Unit,
        onError: (String) -> Unit
    ): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType("application/vnd.android.package-archive")
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(context, null, fileName)
        }
        val downloadId = dm.enqueue(request)
        pollDownloadProgress(dm, downloadId, onProgress, onComplete, onError)
        return downloadId
    }

    private fun pollDownloadProgress(
        dm: DownloadManager,
        downloadId: Long,
        onProgress: (Int) -> Unit,
        onComplete: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.postDelayed({
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)
            if (!cursor.moveToFirst()) {
                cursor.close()
                onError("Download was cancelled.")
                return@postDelayed
            }

            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

            val status = cursor.getInt(statusIdx)
            val total = cursor.getLong(totalIdx)
            val downloaded = cursor.getLong(downloadedIdx)
            val reason = cursor.getInt(reasonIdx)
            cursor.close()

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> onComplete(downloadId)
                DownloadManager.STATUS_FAILED -> onError("Download failed (reason $reason).")
                else -> {
                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    onProgress(percent)
                    pollDownloadProgress(dm, downloadId, onProgress, onComplete, onError)
                }
            }
        }, POLL_INTERVAL_MS)
    }

    fun cancelDownload(downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(downloadId)
    }

    fun installApk(downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
