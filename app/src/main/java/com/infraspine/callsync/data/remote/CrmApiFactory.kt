package com.infraspine.callsync.data.remote

import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds (and rebuilds, if the configured URL changes) the Retrofit client used to
 * talk to the CRM. The agent token is attached via [AuthInterceptor] and is never
 * written to logs — intentionally no logging interceptor is wired in, anywhere,
 * so the Authorization header can never end up in logcat or crash reports.
 */
class CrmApiFactory(private val settingsStore: SecureSettingsStore) {

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedService: CrmApiService? = null

    /**
     * Returns a configured [CrmApiService], or null if no CRM URL has been set.
     * Rebuilds the client only when the base URL changes, so token rotation
     * (handled per-request by the interceptor) doesn't force a rebuild.
     */
    fun getService(): CrmApiService? {
        val baseUrl = settingsStore.crmServerUrl?.takeIf { it.isNotBlank() } ?: return null
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        cachedService?.let { if (cachedBaseUrl == normalized) return it }

        NetworkDiagnostics.logConfiguredServer(normalized)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(settingsStore))
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(CrmApiService::class.java).also {
            cachedService = it
            cachedBaseUrl = normalized
        }
    }
}

/**
 * Attaches the agent token as a Bearer Authorization header on every request.
 * The token is read fresh per-request (so settings changes apply immediately)
 * and is never logged or included in exception messages.
 */
private class AuthInterceptor(private val settingsStore: SecureSettingsStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = settingsStore.agentToken

        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }

        return chain.proceed(request)
    }
}
