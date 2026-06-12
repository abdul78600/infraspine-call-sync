package com.infraspine.callsync.data.repository

import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.data.remote.CrmApiFactory
import com.infraspine.callsync.data.remote.LoginRequest
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.domain.util.UploadErrorParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed class LoginResult {
    object Success : LoginResult()
    data class Failure(val message: String) : LoginResult()
}

class AuthRepository(
    private val settingsStore: SecureSettingsStore,
    private val apiFactory: CrmApiFactory
) {

    suspend fun login(serverUrl: String, email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val normalizedUrl = serverUrl.trim()
        val normalizedEmail = email.trim()

        if (normalizedUrl.isBlank()) return@withContext LoginResult.Failure("CRM server URL is required")
        if (normalizedEmail.isBlank()) return@withContext LoginResult.Failure("Email is required")
        if (password.isBlank()) return@withContext LoginResult.Failure("Password is required")

        settingsStore.crmServerUrl = normalizedUrl
        val api = apiFactory.getService()
            ?: return@withContext LoginResult.Failure("CRM server URL is not configured")

        try {
            val response = api.login(LoginRequest(email = normalizedEmail, password = password))
            if (!response.isSuccessful) {
                val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                val serverMessage = UploadErrorParser.extractMessage(rawBody)
                return@withContext LoginResult.Failure(
                    NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
                )
            }

            val body = response.body()
                ?: return@withContext LoginResult.Failure("Login response did not include an access token")
            val token = body.accessToken?.takeIf { it.isNotBlank() }
                ?: body.token?.takeIf { it.isNotBlank() }
                ?: return@withContext LoginResult.Failure("Login response did not include an access token")

            settingsStore.accessToken = token
            settingsStore.userId = body.user?.id ?: body.user?.userId ?: body.userId
            settingsStore.userName = body.user?.name ?: body.name
            settingsStore.userEmail = body.user?.email ?: body.email ?: normalizedEmail
            settingsStore.dummyTestMode = false

            LoginResult.Success
        } catch (io: IOException) {
            LoginResult.Failure(NetworkDiagnostics.classify(throwable = io))
        }
    }

    fun logout() {
        settingsStore.clearAuth()
    }
}
