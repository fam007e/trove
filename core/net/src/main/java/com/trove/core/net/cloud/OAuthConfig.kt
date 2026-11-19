package com.trove.core.net.cloud

/**
 * Configuration for an OAuth 2.0 PKCE provider.
 *
 * PEDAGOGICAL NOTE:
 * Each cloud service uses the same OAuth 2.0 flow but with different
 * endpoint URLs and scope strings. This data class captures those
 * per-provider differences so the token engine stays generic.
 *
 * @property name           Human-readable name for UI ("Google Drive", "Dropbox")
 * @property authorizeUrl   The authorization endpoint (opened in browser)
 * @property tokenUrl       The token exchange endpoint (POST from app)
 * @property clientId       The OAuth client ID (safe to embed — PKCE means no secret needed)
 * @property scopes         Space-separated OAuth scopes to request
 * @property redirectScheme Redirect URI scheme (custom scheme or loopback)
 * @property redirectPath   Path component of the redirect URI
 */
data class OAuthProviderConfig(
    val name: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val scopes: String,
    val redirectScheme: String = "com.trove.app",
    val redirectPath: String = "/oauth/callback"
) {
    val redirectUri: String
        get() = "$redirectScheme:$redirectPath"
}

/**
 * Tokens received from a successful OAuth 2.0 exchange.
 *
 * PEDAGOGICAL NOTE on refresh tokens:
 * Access tokens are short-lived (typically 1 hour). The refresh token
 * is a long-lived credential that lets us silently obtain a new access
 * token without sending the user back to the browser. This is crucial
 * for a file manager — you don't want to re-authenticate every hour.
 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val tokenType: String = "Bearer",
    val scope: String? = null
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAtMillis

    // Set on creation; recalculated on refresh
    var expiresAtMillis: Long = System.currentTimeMillis() + (expiresInSeconds * 1000)
        internal set
}
