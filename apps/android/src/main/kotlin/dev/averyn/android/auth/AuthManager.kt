package dev.averyn.android.auth

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import net.openid.appauth.connectivity.ConnectionBuilder
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Where the IdP sends the user back after sign-in; must match the app registered by idp-init.sh. */
private const val REDIRECT_URI = "dev.averyn.app:/oauth2redirect"

/** AppAuth reports every async result as (value, error), exactly one of them set. */
private fun <T> Continuation<T>.finish(
    value: T?,
    error: Exception?,
    fallbackMessage: String,
) {
    if (value != null) resume(value) else resumeWithException(error ?: IllegalStateException(fallbackMessage))
}

/** Debug builds only: like AppAuth's default builder, but it also opens plain-HTTP URLs (the dev stack). */
private object CleartextConnectionBuilder : ConnectionBuilder {
    override fun openConnection(uri: Uri): HttpURLConnection =
        (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 10_000
            instanceFollowRedirects = false
        }
}

/**
 * The server this app syncs to, and the OIDC session with that server's identity provider (ADR-0011): the app
 * is given only the server URL; the issuer and client id come from `GET /v1/client-config`. Authorization code
 * with PKCE in the system browser (AppAuth, RFC 8252); the session is stored encrypted ([TokenStorage]).
 *
 * Debug builds accept a plain-HTTP issuer so the dev stack on a LAN address works; release builds require HTTPS.
 */
class AuthManager(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("averyn", Context.MODE_PRIVATE)
    private val storage = TokenStorage(File(context.filesDir, "auth.bin"))
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val connectionBuilder: ConnectionBuilder =
        if (debuggable) CleartextConnectionBuilder else DefaultConnectionBuilder.INSTANCE
    private val service by lazy {
        AuthorizationService(
            context,
            AppAuthConfiguration
                .Builder()
                .setConnectionBuilder(connectionBuilder)
                .setSkipIssuerHttpsCheck(debuggable)
                .build(),
        )
    }
    private var state: AuthState? = storage.read()?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) {
            prefs.edit { putString(KEY_SERVER_URL, value?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }) }
        }

    val isSignedIn: Boolean get() = state?.isAuthorized == true

    /** The intent that opens the system browser at the IdP's login page. Throws if the server cannot be reached. */
    suspend fun signInIntent(): Intent {
        val server = checkNotNull(serverUrl) { "no server URL" }
        val (issuer, clientId) = withContext(Dispatchers.IO) { fetchClientConfig(server) }
        val discoveryUrl = "${issuer.trimEnd('/')}/.well-known/openid-configuration"
        val configuration =
            suspendCoroutine { cont ->
                AuthorizationServiceConfiguration.fetchFromUrl(
                    discoveryUrl.toUri(),
                    { config, error -> cont.finish(config, error, "discovery failed") },
                    connectionBuilder,
                )
            }
        val request =
            AuthorizationRequest
                .Builder(configuration, clientId, ResponseTypeValues.CODE, REDIRECT_URI.toUri())
                .setScope("openid offline_access") // offline_access: a refresh token, so background sync keeps working
                .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Finishes sign-in from the browser's result intent: exchanges the code for tokens and stores the session. */
    suspend fun completeSignIn(data: Intent) {
        val response =
            AuthorizationResponse.fromIntent(data)
                ?: throw AuthorizationException.fromIntent(data) ?: IllegalStateException("sign-in cancelled")
        val tokens =
            suspendCoroutine<TokenResponse> { cont ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { token, error ->
                    cont.finish(token, error, "token request failed")
                }
            }
        persist(
            AuthState().apply {
                update(response, null)
                update(tokens, null)
            },
        )
    }

    /**
     * An access token that is valid now (refreshing it if needed), or null if the user is not signed in or the
     * IdP refused the refresh token (then the session is dropped: sign in again). Throws on transient trouble
     * such as no network: the caller retries later.
     */
    suspend fun freshAccessToken(): String? {
        val current = state ?: return null
        return try {
            val token =
                suspendCoroutine<String> { cont ->
                    current.performActionWithFreshTokens(service) { accessToken, _, error ->
                        cont.finish(accessToken, error, "no access token")
                    }
                }
            persist(current) // a refresh changes the stored tokens
            token
        } catch (e: AuthorizationException) {
            if (e.type != AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) throw e
            signOut()
            null
        }
    }

    /** Forgets the session on this device. (The IdP session in the browser is not ended: ponytail, add end_session.) */
    fun signOut() {
        state = null
        storage.clear()
    }

    private fun persist(newState: AuthState) {
        state = newState
        storage.write(newState.jsonSerializeString())
    }

    /** `GET /v1/client-config` (public): the issuer and the public client id apps sign in with. */
    private fun fetchClientConfig(server: String): Pair<String, String> {
        val connection = URL("$server/v1/client-config").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            check(connection.responseCode == 200) { "server answered ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            json.getString("issuer") to json.getString("clientId")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
    }
}
