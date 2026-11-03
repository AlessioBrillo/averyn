package dev.averyn.backend.profile

import dev.averyn.api.UserProfile
import dev.averyn.backend.activities.PER_USER
import dev.averyn.backend.activities.owner
import dev.averyn.backend.activities.perUserRateLimit
import dev.averyn.backend.auth.OIDC_AUTH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.sql.DataSource

/** A profile body larger than this is not a profile (it has two fields). */
private const val MAX_PROFILE_BYTES = 1024

/**
 * Profile API (TDD-0012): `GET /v1/profile` and `PUT /v1/profile`, authenticated and owner-only. An account that
 * never saved a profile gets the defaults. A body that is not a valid profile is `422` and nothing is stored.
 */
fun Application.profile(
    db: DataSource,
    store: ProfileStore = ProfileStore(db),
) {
    val json = Json { ignoreUnknownKeys = true }
    perUserRateLimit()
    routing {
        authenticate(OIDC_AUTH) {
            rateLimit(PER_USER) {
                get("/v1/profile") {
                    val owner = call.owner(db) ?: return@get
                    call.respond(withContext(Dispatchers.IO) { store.get(owner) })
                }

                put("/v1/profile") {
                    val owner = call.owner(db) ?: return@put
                    // At most one byte past the limit is read: a body without a Content-Length is not read to its end.
                    val bytes = call.receiveChannel().readRemaining(MAX_PROFILE_BYTES + 1L).readByteArray()
                    val body = bytes.decodeToString()
                    val parsed =
                        if (bytes.size > MAX_PROFILE_BYTES) {
                            null
                        } else {
                            try {
                                json.decodeFromString(UserProfile.serializer(), body)
                            } catch (e: SerializationException) {
                                null
                            } catch (e: IllegalArgumentException) {
                                null
                            }
                        }
                    val problem = if (parsed == null) "not a profile" else parsed.problem()
                    if (parsed == null || problem != null) {
                        call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (problem ?: "not a profile")))
                        return@put
                    }
                    val profile: UserProfile = parsed
                    withContext(Dispatchers.IO) { store.put(owner, profile) }
                    call.respond(profile)
                }
            }
        }
    }
}
