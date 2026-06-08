package com.infraspine.callsync.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Result of an update check against the project's GitHub Releases.
 *
 * The app has no Play Store auto-update, so this lets an agent check, from
 * Settings, whether a newer build than the one they installed has been
 * published — and gives them a stable browser link to download it.
 */
sealed class UpdateCheckResult {
    data class UpToDate(val installedAt: String?) : UpdateCheckResult()
    data class UpdateAvailable(val publishedAt: String, val downloadUrl: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Checks the "latest" GitHub Release for this project and compares its publish
 * timestamp against the one the agent last installed/acknowledged. The CI workflow
 * (.github/workflows/build-apk.yml) keeps a single release tagged `latest` up to
 * date with the newest debug APK at a stable download URL, so this never has to
 * guess at version numbers — it just compares "is there something newer than what
 * I last saw."
 */
class UpdateChecker(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("GitHub returned HTTP ${response.code}")
                }

                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }
                    ?: return@withContext UpdateCheckResult.Error("Release has no publish date")

                val downloadUrl = findApkDownloadUrl(json) ?: STABLE_APK_DOWNLOAD_URL

                val lastSeen = prefs.getString(KEY_LAST_SEEN_PUBLISHED_AT, null)

                if (lastSeen == null) {
                    // First check ever: remember this as the installed baseline rather
                    // than immediately reporting "update available" for the build the
                    // agent is already running.
                    prefs.edit { putString(KEY_LAST_SEEN_PUBLISHED_AT, publishedAt) }
                    UpdateCheckResult.UpToDate(installedAt = publishedAt)
                } else if (publishedAt > lastSeen) {
                    UpdateCheckResult.UpdateAvailable(publishedAt, downloadUrl)
                } else {
                    UpdateCheckResult.UpToDate(installedAt = lastSeen)
                }
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(error.message ?: "Network error while checking for updates")
        }
    }

    /** Call after the agent downloads/installs an update so future checks compare against it. */
    fun acknowledgeVersion(publishedAt: String) {
        prefs.edit { putString(KEY_LAST_SEEN_PUBLISHED_AT, publishedAt) }
    }

    private fun findApkDownloadUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    companion object {
        private const val PREFS_FILE_NAME = "update_checker_prefs"
        private const val KEY_LAST_SEEN_PUBLISHED_AT = "last_seen_published_at"

        private const val GITHUB_OWNER = "abdul78600"
        private const val GITHUB_REPO = "infraspine-call-sync"

        /**
         * Fetches by tag name rather than GitHub's "latest release" endpoint —
         * that endpoint deliberately excludes pre-releases/drafts, and the build
         * workflow publishes the `latest` tag as a pre-release (it's a debug build,
         * not a store-ready release), which made it 404 for everyone.
         */
        private const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/tags/latest"

        /** Stable URL that always resolves to the newest APK published under the `latest` release tag. */
        const val STABLE_APK_DOWNLOAD_URL =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/latest/app-debug.apk"
    }
}
