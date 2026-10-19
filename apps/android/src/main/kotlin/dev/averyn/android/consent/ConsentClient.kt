package dev.averyn.android.consent

import dev.averyn.android.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the consent grants the server holds for the user (ADR-0021).
 *
 * The server is authoritative: the device never decides on its own whether it may record, it asks and caches
 * the answer, and it fails closed — no answer means no recording.
 */
class ConsentClient(
    private val auth: AuthManager,
    private val serverUrl: () -> String?,
) {
    /** The scopes the user has granted and not revoked. Empty when the server cannot be reached. */
    suspend fun grantedScopes(): Set<String> {
        val server = serverUrl()?.takeIf { it.isNotEmpty() } ?: return emptySet()
        // A token that cannot be refreshed (no network, the identity provider is down) is not a grant: the
        // caller must not crash on it, and the answer is "nothing is granted".
        val token =
            try {
                auth.freshAccessToken()
            } catch (e: Exception) {
                null
            } ?: return emptySet()
        return withContext(Dispatchers.IO) {
            val connection = URL("$server/v1/consents").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            try {
                if (connection.responseCode != 200) return@withContext emptySet()
                val grants = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                buildSet {
                    for (index in 0 until grants.length()) {
                        val grant = grants.getJSONObject(index)
                        if (!grant.isNull("revokedAt")) add(grant.getString("scope"))
                    }
                }
            } catch (e: Exception) {
                // A server that cannot be reached is not a grant: fail closed.
                emptySet()
            } finally {
                connection.disconnect()
            }
        }
    }
}
