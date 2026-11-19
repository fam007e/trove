package com.trove.core.net.cloud

/**
 * Pre-configured OAuth 2.0 PKCE provider configs for supported cloud services.
 *
 * PEDAGOGICAL NOTES:
 *
 * Client IDs for PKCE (public) clients:
 * - Google: Create in Google Cloud Console → APIs & Services → Credentials
 *   → OAuth 2.0 Client IDs → "Android" type. No secret needed for PKCE.
 * - Dropbox: Create in Dropbox App Console. Enable PKCE. No secret needed.
 * - OneDrive: Register in Microsoft Azure AD → App Registrations.
 *   Enable "Mobile and desktop applications" platform. No secret for PKCE.
 * - Nextcloud: Register in the Nextcloud instance's OAuth admin panel.
 *
 * The placeholder client IDs below must be replaced with real ones
 * registered to Trove's package name (com.trove.app). They are set to
 * obviously-fake values so the app compiles and tests pass, while
 * making it clear they need real registration.
 *
 * F-DROID NOTE:
 * F-Droid reproducible builds require deterministic client IDs. Each
 * user/distributor can supply their own via gradle properties:
 *   -Ptrove.oauthGoogleClientId="xxx.apps.googleusercontent.com"
 */
object CloudProviders {

    /**
     * Google Drive via OAuth 2.0 PKCE.
     *
     * Scopes:
     * - drive.file: per-file access to files created/opened by the app
     * - drive.readonly: read-only access to all files (broader)
     *
     * We use drive.file for minimal-privilege principle.
     */
    fun googleDrive(clientId: String): OAuthProviderConfig = OAuthProviderConfig(
        name = "Google Drive",
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        clientId = clientId,
        scopes = "https://www.googleapis.com/auth/drive",
        redirectScheme = "com.trove.app",
        redirectPath = "/oauth/google"
    )

    /**
     * Dropbox via OAuth 2.0 PKCE.
     *
     * Dropbox natively supports PKCE since 2020.
     * Token endpoint returns short-lived access + long-lived refresh.
     */
    fun dropbox(clientId: String): OAuthProviderConfig = OAuthProviderConfig(
        name = "Dropbox",
        authorizeUrl = "https://www.dropbox.com/oauth2/authorize",
        tokenUrl = "https://api.dropboxapi.com/oauth2/token",
        clientId = clientId,
        scopes = "",  // Dropbox uses app-level scopes, not per-request
        redirectScheme = "com.trove.app",
        redirectPath = "/oauth/dropbox"
    )

    /**
     * OneDrive / Microsoft Graph via OAuth 2.0 PKCE.
     *
     * Uses the common (multi-tenant) Azure AD endpoint.
     * Files.ReadWrite gives full file access.
     */
    fun oneDrive(clientId: String): OAuthProviderConfig = OAuthProviderConfig(
        name = "OneDrive",
        authorizeUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        clientId = clientId,
        scopes = "Files.ReadWrite.All offline_access",
        redirectScheme = "com.trove.app",
        redirectPath = "/oauth/onedrive"
    )

    /**
     * Nextcloud via OAuth 2.0.
     *
     * Nextcloud's OAuth endpoint is at the instance URL.
     * The user provides their Nextcloud server URL at connection time.
     */
    fun nextcloud(instanceUrl: String, clientId: String): OAuthProviderConfig {
        val base = instanceUrl.trimEnd('/')
        return OAuthProviderConfig(
            name = "Nextcloud",
            authorizeUrl = "$base/index.php/apps/oauth2/authorize",
            tokenUrl = "$base/index.php/apps/oauth2/api/v1/token",
            clientId = clientId,
            scopes = "",  // Nextcloud uses app-level permissions
            redirectScheme = "com.trove.app",
            redirectPath = "/oauth/nextcloud"
        )
    }
}
