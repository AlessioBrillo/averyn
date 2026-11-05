package dev.averyn.backend.privacy

import dev.averyn.api.PrivacyZones
import dev.averyn.backend.activities.PER_USER
import dev.averyn.backend.activities.errorBody
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

/** Ten zones with a label are about 1.2 kB; anything larger is not a list of zones. */
private const val MAX_ZONES_BYTES = 4096

/**
 * Privacy zones API (TDD-0016): `GET /v1/privacy-zones` and `PUT /v1/privacy-zones`, authenticated and owner-only.
 * The body of the PUT replaces the whole list; one that is not valid is `422` and nothing is stored. The answers
 * and the errors never repeat a coordinate, and nothing here logs one.
 */
fun Application.privacyZones(
    db: DataSource,
    store: PrivacyZoneStore = PrivacyZoneStore(db),
) {
    val json = Json { ignoreUnknownKeys = true }
    perUserRateLimit()
    routing {
        authenticate(OIDC_AUTH) {
            rateLimit(PER_USER) {
                get("/v1/privacy-zones") {
                    val owner = call.owner(db) ?: return@get
                    call.respond(withContext(Dispatchers.IO) { store.get(owner) })
                }

                put("/v1/privacy-zones") {
                    val owner = call.owner(db) ?: return@put
                    val bytes = call.receiveChannel().readRemaining(MAX_ZONES_BYTES + 1L).readByteArray()
                    val parsed =
                        if (bytes.size > MAX_ZONES_BYTES) {
                            null
                        } else {
                            try {
                                json.decodeFromString(PrivacyZones.serializer(), bytes.decodeToString())
                            } catch (e: SerializationException) {
                                null
                            } catch (e: IllegalArgumentException) {
                                null
                            }
                        }
                    val problem = if (parsed == null) "not a list of zones" else parsed.problem()
                    if (parsed == null || problem != null) {
                        call.respond(HttpStatusCode.UnprocessableEntity, errorBody(problem ?: "not a list of zones"))
                        return@put
                    }
                    withContext(Dispatchers.IO) { store.put(owner, parsed) }
                    call.respond(parsed)
                }
            }
        }
    }
}
