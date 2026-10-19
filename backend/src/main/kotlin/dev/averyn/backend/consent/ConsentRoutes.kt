package dev.averyn.backend.consent

import dev.averyn.backend.auth.AccountDeletedException
import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.auth.userIdFor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Deletes the data a revoked scope covers (ADR-0021): the heart-rate sidecar objects and their tombstones.
 * The activities package owns those files, so the caller wires the deletion in and this package stays free of
 * it.
 */
fun interface ConsentDataDeletion {
    fun delete(
        ownerId: UUID,
        scope: String,
    )
}

/**
 * Consent API (ADR-0021): `GET /v1/consents` lists the caller's grants; `POST /v1/consents/{scope}` grants;
 * `DELETE /v1/consents/{scope}` revokes. All authenticated, owner-only. The device-side adapter checks the
 * grant before streaming RR (fail-closed), and revocation deletes the covered data via the deleted_rr
 * tombstone (ADR-0020/0021).
 *
 * Revocation answers 204 even when nothing was granted: idempotent, and the caller can't distinguish "never
 * granted" from "revoked" (no enumeration of which scopes a user considered).
 */
fun Application.consent(
    db: DataSource,
    deletion: ConsentDataDeletion,
): ConsentStore {
    val store = ConsentStore(db)

    routing {
        authenticate(OIDC_AUTH) {
            get("/v1/consents") {
                val owner = call.owner(db) ?: return@get
                val grants =
                    withContext(Dispatchers.IO) { store.list(owner) }
                call.respond(
                    grants.map {
                        mapOf(
                            "scope" to it.scope,
                            "grantedAt" to it.grantedAt.toString(),
                            "revokedAt" to it.revokedAt?.toString(),
                        )
                    },
                )
            }

            post("/v1/consents/{scope}") {
                val scope = call.parameters["scope"]
                if (scope == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing scope"))
                    return@post
                }
                if (scope !in ConsentScope.ALL) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown consent scope"))
                    return@post
                }
                val owner = call.owner(db) ?: return@post
                withContext(Dispatchers.IO) { store.grant(owner, scope) }
                call.respond(HttpStatusCode.Created)
            }

            delete("/v1/consents/{scope}") {
                val scope = call.parameters["scope"]
                if (scope == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing scope"))
                    return@delete
                }
                if (scope !in ConsentScope.ALL) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown consent scope"))
                    return@delete
                }
                val owner = call.owner(db) ?: return@delete
                withContext(Dispatchers.IO) { store.revoke(owner, scope) }
                // Revoking really deletes the data it covers: the grant is off, so nothing new comes in, and
                // what was stored for it goes away (ADR-0021).
                withContext(Dispatchers.IO) { deletion.delete(owner, scope) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    return store
}

/** Scopes Averyn currently grants consent for (ADR-0021). `hrv_analysis` is reserved for hrv-v1. */
object ConsentScope {
    val RR_RECORDING = "rr_recording"
    val HRV_ANALYSIS = "hrv_analysis"
    val ALL = setOf(RR_RECORDING, HRV_ANALYSIS)
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
