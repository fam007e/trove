package com.trove.core.net.cloud

/**
 * Interface for interactive two-factor authentication (TOTP/MFA) challenges.
 * Used by direct-credential backends (MEGA.nz, WebDAV/Nextcloud 2FA, etc.).
 */
fun interface TotpChallengeHandler {
    suspend fun requestTotpCode(serviceName: String, accountHint: String): String?
}

/**
 * Result of a multi-factor authentication step.
 */
sealed class MfaResult<out T> {
    data class Success<out T>(val value: T) : MfaResult<T>()
    data class RequiresTotp(val serviceName: String, val accountHint: String) : MfaResult<Nothing>()
    data class Failure(val exception: Throwable) : MfaResult<Nothing>()
}
