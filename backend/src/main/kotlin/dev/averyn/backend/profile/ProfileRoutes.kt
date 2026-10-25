package dev.averyn.backend.profile

import dev.averyn.api.UserProfile
import dev.averyn.backend.auth.AccountDeletedException
import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.auth.userIdFor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
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
    routing {
        authenticate(OIDC_AUTH) {
            get("/v1/profile") {
                val owner = call.owner(db) ?: return@get
                call.respond(withContext(Dispatchers.IO) { store.get(owner) })
            }

            put("/v1/profile") {
                val owner = call.owner(db) ?: return@put
                val body = call.receiveText()
                val parsed =
                    if (body.length > MAX_PROFILE_BYTES) {
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

/** The caller's Averyn user id, or null after answering 403 because the account was deleted (ADR-0017). */
private suspend fun ApplicationCall.owner(db: DataSource): UUID? {
    val principal = checkNotNull(principal<JWTPrincipal>())
    return try {
        withContext(Dispatchers.IO) { db.userIdFor(principal) }
    } catch (e: AccountDeletedException) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "account deleted"))
        null
    }
}
