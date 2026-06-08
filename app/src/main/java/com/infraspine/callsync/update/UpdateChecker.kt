package com.infraspine.callsync.update

import android.content.Context
import com.infraspine.callsync.BuildConfig
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
    object UpToDate : UpdateCheckResult()
    data class UpdateAvailable(val downloadUrl: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Checks the "latest" GitHub Release for this project and compares the commit SHA
 * it was built from against the SHA the *running* build was compiled from
 * ([BuildConfig.GIT_COMMIT_SHA], injected by the CI workflow at build time).
 *
 * Earlier versions tried to infer "is there something newer" from cached release
 * timestamps — but since the `latest` release is edited in place (same tag, new
 * APK on every push), the only thing that reliably identifies *which* build is
 * running versus which is published is the commit it was built from. Comparing
 * SHAs directly removes the need to track any "last seen" state at all: a fresh
 * install, a reinstall, or a brand new device all give the same correct answer.
 */
class UpdateChecker(context: Context) {

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
                val apkAsset = findApkAsset(json)
                    ?: return@withContext UpdateCheckResult.Error("Release has no APK asset")

                val downloadUrl = apkAsset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    ?: STABLE_APK_DOWNLOAD_URL

                // The workflow names every release "Latest build (<full commit SHA>)" —
                // extract that SHA and compare it to the one this APK was built from.
                val releaseSha = extractCommitSha(json.optString("name"))
                val runningSha = BuildConfig.GIT_COMMIT_SHA

                when {
                    releaseSha == null ->
                        UpdateCheckResult.Error("Could not determine the published build's commit")
                    runningSha.isBlank() || runningSha == "unknown" ->
                        // Can't identify our own build (e.g. a local build without CI env) —
                        // surface the release as available rather than falsely claim "up to date".
                        UpdateCheckResult.UpdateAvailable(downloadUrl)
                    releaseSha == runningSha ->
                        UpdateCheckResult.UpToDate
                    else ->
                        UpdateCheckResult.UpdateAvailable(downloadUrl)
                }
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(error.message ?: "Network error while checking for updates")
        }
    }

    private fun extractCommitSha(releaseName: String): String? =
        COMMIT_SHA_REGEX.find(releaseName)?.groupValues?.get(1)

    private fun findApkAsset(release: JSONObject): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return asset
            }
        }
        return null
    }

    companion object {
        private const val GITHUB_OWNER = "abdul78600"
        private const val GITHUB_REPO = "infraspine-call-sync"

        // Matches the full 40-character SHA the workflow embeds in the release name,
        // e.g. "Latest build (875c973b4acead921a9e70d0f30d79246bcc69f5)".
        private val COMMIT_SHA_REGEX = Regex("\\(([0-9a-f]{40})\\)")

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
