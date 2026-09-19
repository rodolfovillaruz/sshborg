package com.sshborg.data.reflector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.sshborg.BuildConfig
import com.sshborg.data.AppPreferences
import com.sshborg.data.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class NotSignedInException : Exception("Not signed in — sign in with Google in Settings")

/**
 * Google sign-in over the system browser (OAuth 2.0 authorization code + PKCE), so it works
 * without Play Services and on F-Droid builds. Yields a Google ID token that the reflector
 * Worker verifies; the refresh token is kept encrypted in [AppPreferences].
 *
 * Uses a Google "Web application" client. Google redirects to `<authBase>/oauth/callback` on the
 * reflector Worker, which hands the code back to the app via `sshborg://oauth`; the Worker also
 * performs the code/refresh exchanges because the client secret lives only there.
 *
 * Configure `google.clientId` (the web client ID) and `google.authBase` (the Worker's https
 * origin, e.g. https://i.yes.ph) in local.properties.
 */
class GoogleAuth(private val prefs: AppPreferences) {

    private class Pending(val verifier: String, val state: String)
    @Volatile private var pending: Pending? = null
    @Volatile private var cachedIdToken: String? = null
    @Volatile private var cachedExpiryMs: Long = 0

    private val authBase: String get() = BuildConfig.GOOGLE_AUTH_BASE.trimEnd('/')

    val isConfigured: Boolean
        get() = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank() && authBase.startsWith("https://")

    /** Opens the browser on Google's consent screen. The result arrives in [handleRedirect]. */
    fun startSignIn(context: Context) {
        check(isConfigured) { "google.clientId / google.authBase are not set" }
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(16)
        pending = Pending(verifier, state)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), URL_SAFE,
        )
        val uri = Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.GOOGLE_CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirectUri())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "openid email")
            // Web clients only issue a refresh token for offline access, and only on a consent screen.
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** True if [uri] is this app's OAuth redirect. */
    fun isRedirect(uri: Uri?): Boolean =
        uri != null && uri.scheme == APP_SCHEME && uri.host == APP_HOST

    /** Completes sign-in from the redirect. Returns the signed-in email. */
    suspend fun handleRedirect(uri: Uri): String {
        val p = pending ?: error("No sign-in in progress")
        pending = null
        uri.getQueryParameter("error")?.let { error("Google sign-in failed: $it") }
        if (uri.getQueryParameter("state") != p.state) error("Sign-in state mismatch")
        val code = uri.getQueryParameter("code") ?: error("Sign-in returned no code")
        val json = postJson("/oauth/token", "code" to code, "code_verifier" to p.verifier)
        val refresh = json.optString("refresh_token")
        if (refresh.isEmpty()) error("Google returned no refresh token")
        val idToken = json.getString("id_token")
        prefs.setGoogleAccount(KeystoreManager.encrypt(refresh), emailOf(idToken))
        remember(idToken)
        return prefs.googleEmail.first() ?: ""
    }

    /** A valid ID token, refreshing silently when the cached one is about to expire. */
    suspend fun idToken(): String {
        cachedIdToken?.takeIf { System.currentTimeMillis() < cachedExpiryMs - EXPIRY_MARGIN_MS }?.let { return it }
        val blob = prefs.googleRefreshToken.first() ?: throw NotSignedInException()
        val json = try {
            postJson("/oauth/refresh", "refresh_token" to KeystoreManager.decrypt(blob))
        } catch (e: InvalidGrantException) {
            signOut()   // revoked or expired: force a clean re-sign-in instead of failing forever
            throw NotSignedInException()
        }
        return remember(json.getString("id_token"))
    }

    suspend fun signOut() {
        cachedIdToken = null
        prefs.setGoogleAccount(null, null)
    }

    private fun remember(idToken: String): String {
        cachedIdToken = idToken
        cachedExpiryMs = claims(idToken).optLong("exp") * 1000
        return idToken
    }

    private fun emailOf(idToken: String): String = claims(idToken).optString("email")

    // The token comes straight from Google over TLS in exchange for our code; the Worker is the
    // party that verifies the signature. We only read claims for display and cache expiry.
    private fun claims(idToken: String): JSONObject =
        JSONObject(String(Base64.decode(idToken.split(".")[1], URL_SAFE)))

    private class InvalidGrantException : Exception()

    private suspend fun postJson(path: String, vararg fields: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }.toString()
        val conn = URL(authBase + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (!ok) {
                if (runCatching { JSONObject(text).optString("error") }.getOrNull() == "invalid_grant") {
                    throw InvalidGrantException()
                }
                error("Sign-in request failed (${conn.responseCode})")
            }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun redirectUri(): String = "$authBase/oauth/callback"

    private fun randomUrlSafe(bytes: Int): String =
        Base64.encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) }, URL_SAFE)

    private companion object {
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val APP_SCHEME = "sshborg"
        const val APP_HOST = "oauth"
        const val EXPIRY_MARGIN_MS = 60_000L
        const val URL_SAFE = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    }
}
