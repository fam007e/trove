package com.trove.core.net.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * OAuth 2.0 token engine: handles PKCE authorization URL building,
 * code-for-token exchange, and silent token refresh.
 *
 * PEDAGOGICAL NOTES:
 *
 * This is a pure HTTP implementation — no Google SDK, no Dropbox SDK,
 * no proprietary auth library. The OAuth 2.0 spec is an open standard
 * (RFC 6749 + RFC 7636) and every provider implements the same endpoints.
 *
 * The flow:
 *   1. [buildAuthorizeUrl] → open in browser
 *   2. User authenticates, browser redirects to our URI with ?code=...
 *   3. [exchangeCode] → POST code + verifier → receive tokens
 *   4. [refreshAccessToken] → POST refresh_token → receive new access token
 *
 * We deliberately parse JSON manually with org.json (bundled in Android)
 * to avoid adding kotlinx-serialization as a dependency.
 */
class OAuthTokenEngine(
    private val config: OAuthProviderConfig
) {
    private val httpClient = HttpClient(OkHttp)

    /** Active PKCE session state: verifier for the in-flight authorization. */
    private var pendingVerifier: String? = null

    /** Current tokens (null until first successful exchange). */
    var tokens: OAuthTokens? = null
        private set

    /**
     * Step 1: Build the authorization URL the user will open in a browser.
     * Generates a fresh PKCE code verifier and stores it for step 3.
     */
    fun buildAuthorizeUrl(): String {
        val verifier = PkceUtils.generateCodeVerifier()
        pendingVerifier = verifier
        val challenge = PkceUtils.computeCodeChallenge(verifier)

        return buildString {
            append(config.authorizeUrl)
            append("?response_type=code")
            append("&client_id=").append(urlEncode(config.clientId))
            append("&redirect_uri=").append(urlEncode(config.redirectUri))
            append("&scope=").append(urlEncode(config.scopes))
            append("&code_challenge=").append(urlEncode(challenge))
            append("&code_challenge_method=S256")
            append("&access_type=offline")  // Request refresh token (Google-specific, harmless elsewhere)
            append("&prompt=consent")        // Force consent screen to get refresh token
        }
    }

    /**
     * Step 3: Exchange the authorization code for tokens.
     * Uses the stored PKCE verifier from step 1.
     *
     * PEDAGOGICAL NOTE:
     * This POST sends the code_verifier (not the challenge). The server
     * recomputes SHA256(verifier) and checks it matches the challenge
     * it stored when the user authorized. This proves the same app
     * that started the flow is finishing it.
     */
    suspend fun exchangeCode(code: String): Result<OAuthTokens> = withContext(Dispatchers.IO) {
        val verifier = pendingVerifier
            ?: return@withContext Result.failure(IllegalStateException("No pending PKCE verifier — call buildAuthorizeUrl first"))

        try {
            val response = httpClient.submitForm(
                url = config.tokenUrl,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("client_id", config.clientId)
                    append("code", code)
                    append("redirect_uri", config.redirectUri)
                    append("code_verifier", verifier)
                }
            )
            val body = response.bodyAsText()
            val json = JSONObject(body)

            if (json.has("error")) {
                val error = json.getString("error")
                val desc = json.optString("error_description", "")
                return@withContext Result.failure(
                    OAuthException("$error: $desc")
                )
            }

            val oauthTokens = OAuthTokens(
                accessToken = json.getString("access_token"),
                refreshToken = if (json.has("refresh_token")) json.getString("refresh_token") else null,
                expiresInSeconds = json.optLong("expires_in", 3600),
                tokenType = json.optString("token_type", "Bearer"),
                scope = if (json.has("scope")) json.getString("scope") else null
            )
            tokens = oauthTokens
            pendingVerifier = null
            Result.success(oauthTokens)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Silent token refresh using the stored refresh token.
     *
     * PEDAGOGICAL NOTE:
     * Access tokens are intentionally short-lived (1 hour) to limit
     * damage if intercepted. The refresh token is long-lived but can
     * only be used from the app (bound to client_id). When we refresh,
     * the server may rotate the refresh token too — we always store
     * the latest one.
     */
    suspend fun refreshAccessToken(): Result<OAuthTokens> = withContext(Dispatchers.IO) {
        val currentRefresh = tokens?.refreshToken
            ?: return@withContext Result.failure(IllegalStateException("No refresh token available"))

        try {
            val response = httpClient.submitForm(
                url = config.tokenUrl,
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("client_id", config.clientId)
                    append("refresh_token", currentRefresh)
                }
            )
            val body = response.bodyAsText()
            val json = JSONObject(body)

            if (json.has("error")) {
                val error = json.getString("error")
                val desc = json.optString("error_description", "")
                return@withContext Result.failure(
                    OAuthException("$error: $desc")
                )
            }

            val refreshed = OAuthTokens(
                accessToken = json.getString("access_token"),
                // Some providers rotate refresh tokens; use new one if present
                refreshToken = if (json.has("refresh_token")) json.getString("refresh_token") else currentRefresh,
                expiresInSeconds = json.optLong("expires_in", 3600),
                tokenType = json.optString("token_type", "Bearer"),
                scope = if (json.has("scope")) json.getString("scope") else null
            )
            tokens = refreshed
            Result.success(refreshed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get a valid access token, refreshing automatically if expired.
     */
    suspend fun getValidAccessToken(): String? {
        val t = tokens ?: return null
        if (!t.isExpired) return t.accessToken

        // Try to silently refresh
        val refreshResult = refreshAccessToken()
        return refreshResult.getOrNull()?.accessToken
    }

    /**
     * Restore tokens from persistent storage (SharedPreferences).
     * Called on app restart to resume sessions without re-auth.
     */
    fun restoreTokens(accessToken: String, refreshToken: String?, expiresAtMillis: Long) {
        val remaining = ((expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        tokens = OAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = remaining
        ).also {
            it.expiresAtMillis = expiresAtMillis
        }
    }

    fun clearTokens() {
        tokens = null
        pendingVerifier = null
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}

class OAuthException(message: String) : Exception(message)
