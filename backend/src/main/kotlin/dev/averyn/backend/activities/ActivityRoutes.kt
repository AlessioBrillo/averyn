package dev.averyn.backend.activities

import dev.averyn.backend.auth.AccountDeletedException
import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.auth.userIdFor
import dev.averyn.backend.storage.RawObjectStore
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource

/** Upload limit (TDD-0002 §4): 3 h at 1 Hz is about 2 MB, 10 h about 7 MB. */
const val MAX_ACTIVITY_BYTES = 32L * 1024 * 1024

const val DEFAULT_PAGE_SIZE = 50
const val MAX_PAGE_SIZE = 100

private val GEO_JSON = ContentType("application", "geo+json")

/** Uploads handled at once: each holds an IO thread and up to [MAX_ACTIVITY_BYTES] of spooled disk. */
const val MAX_CONCURRENT_UPLOADS = 8

/** Exports handled at once: each streams a whole archive. */
const val MAX_CONCURRENT_EXPORTS = 2

/**
 * `PUT /v1/activities/{clientActivityId}`, `GET /v1/activities` (list), `GET /v1/activities/{id}` and
 * `GET /v1/activities/{id}/track`, owner-only (TDD-0002 §5.1, TDD-0003 §5.1); `DELETE /v1/activities/{id}`,
 * `DELETE /v1/me` and `GET /v1/me/export` (TDD-0004). When all [uploadSlots] (or [exportSlots]) are taken a further
 * upload (export) gets `503` + `Retry-After`, which the apps treat as retryable. A deleted account gets `403` on
 * everything but repeating `DELETE /v1/me`.
 *
 * ponytail: the bound is global, not per user. Add per-user quotas / rate limits before any public instance
 * (docs/privacy/threat-model.md).
 */
fun Application.activities(
    db: DataSource,
    raw: RawObjectStore,
    maxBytes: Long = MAX_ACTIVITY_BYTES,
    uploadSlots: Semaphore = Semaphore(MAX_CONCURRENT_UPLOADS),
    exportSlots: Semaphore = Semaphore(MAX_CONCURRENT_EXPORTS),
) {
    val ingest = ActivityIngest(db, raw)
    val userData = UserData(db, raw)

    routing {
        authenticate(OIDC_AUTH) {
            put("/v1/activities/{clientActivityId}") {
                val clientId =
                    call.parameters["clientActivityId"]?.toUuidOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                val declared = call.request.contentLength()
                if (declared != null && declared > maxBytes) {
                    return@put call.respond(HttpStatusCode.PayloadTooLarge, errorBody("activity file too large"))
                }
                val owner = call.owner(db) ?: return@put
                if (!uploadSlots.tryAcquire()) {
                    call.response.header(HttpHeaders.RetryAfter, "5")
                    return@put call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        errorBody("too many uploads, retry later"),
                    )
                }

                val result =
                    try {
                        withContext(Dispatchers.IO) {
                            val dir = Files.createTempDirectory("averyn-ingest-")
                            try {
                                val file = dir.resolve("$clientId.jsonl")
                                val sha256 = call.receiveToFile(file, maxBytes)
                                if (sha256 == null) {
                                    IngestResult.TooLarge
                                } else {
                                    ingest.ingest(owner, clientId, file, sha256)
                                }
                            } finally {
                                dir.toFile().deleteRecursively()
                            }
                        }
                    } finally {
                        uploadSlots.release()
                    }
                when (result) {
                    is IngestResult.Created -> call.respond(HttpStatusCode.Created, result.summary)
                    is IngestResult.Existing -> call.respond(HttpStatusCode.OK, result.summary)
                    IngestResult.Conflict ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            errorBody("activity id already uploaded with different content"),
                        )
                    IngestResult.Gone ->
                        call.respond(HttpStatusCode.Gone, errorBody("activity was deleted and is not accepted again"))
                    IngestResult.TooLarge ->
                        call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            errorBody("activity file too large"),
                        )
                    is IngestResult.Invalid ->
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            errorBody(result.reason),
                        )
                }
            }

            get("/v1/activities") {
                val rawLimit = call.request.queryParameters["limit"]
                val limit =
                    if (rawLimit == null) {
                        DEFAULT_PAGE_SIZE
                    } else {
                        rawLimit.toIntOrNull()?.takeIf { it in 1..MAX_PAGE_SIZE }
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                errorBody("limit must be 1..$MAX_PAGE_SIZE"),
                            )
                    }
                val after =
                    call.request.queryParameters["cursor"]?.let {
                        ActivityCursor.parseOrNull(it)
                            ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed cursor"))
                    }
                val owner = call.owner(db) ?: return@get
                call.respond(withContext(Dispatchers.IO) { ingest.list(owner, limit, after) })
            }

            get("/v1/activities/{id}/track") {
                val id =
                    call.parameters["id"]?.toUuidOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                val owner = call.owner(db) ?: return@get
                val feature =
                    withContext(Dispatchers.IO) { ingest.trackGeoJson(owner, id) }
                        ?: return@get call.respond(HttpStatusCode.NotFound, errorBody("not found"))
                call.respondText(feature, GEO_JSON)
            }

            get("/v1/activities/{id}") {
                val id =
                    call.parameters["id"]?.toUuidOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                val owner = call.owner(db) ?: return@get
                val summary =
                    withContext(Dispatchers.IO) { ingest.find(owner, id) }
                        // Not yours and not existing look identical (S5): never reveal that someone else's id is real.
                        ?: return@get call.respond(HttpStatusCode.NotFound, errorBody("not found"))
                call.respond(summary)
            }

            delete("/v1/activities/{id}") {
                val id =
                    call.parameters["id"]?.toUuidOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                val owner = call.owner(db) ?: return@delete
                if (withContext(Dispatchers.IO) { userData.deleteActivity(owner, id) }) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, errorBody("not found"))
                }
            }

            // The one call a deleted account may repeat: it finishes a deletion that failed half-way.
            delete("/v1/me") {
                val owner = call.owner(db, allowDeleted = true) ?: return@delete
                withContext(Dispatchers.IO) { userData.deleteAccount(owner) }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/v1/me/export") {
                val owner = call.owner(db) ?: return@get
                if (!exportSlots.tryAcquire()) {
                    call.response.header(HttpHeaders.RetryAfter, "5")
                    return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        errorBody("too many exports, retry later"),
                    )
                }
                try {
                    val disposition =
                        ContentDisposition.Attachment.withParameter(
                            ContentDisposition.Parameters.FileName,
                            "averyn-export.zip",
                        )
                    call.response.header(HttpHeaders.ContentDisposition, disposition.toString())
                    call.respondOutputStream(ContentType.Application.Zip) { userData.export(owner, this) }
                } finally {
                    exportSlots.release()
                }
            }
        }
    }
}

/**
 * The caller's Averyn user id, or null after answering `403` because the account was deleted (ADR-0017).
 *
 * ponytail: the export bound is global, not per user; per-user quotas come with the rate-limit slice.
 */
private suspend fun ApplicationCall.owner(
    db: DataSource,
    allowDeleted: Boolean = false,
): UUID? {
    val principal = checkNotNull(principal<JWTPrincipal>())
    return try {
        withContext(Dispatchers.IO) { db.userIdFor(principal, allowDeleted) }
    } catch (e: AccountDeletedException) {
        respond(HttpStatusCode.Forbidden, errorBody("account deleted"))
        null
    }
}

private fun errorBody(message: String) = mapOf("error" to message)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

/**
 * Streams the request body to [target], hashing as it goes. Returns the lower-case hex SHA-256, or null as soon
 * as more than [maxBytes] have arrived (the rest is not read).
 */
private suspend fun ApplicationCall.receiveToFile(
    target: Path,
    maxBytes: Long,
): String? {
    val channel = receiveChannel()
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    Files.newOutputStream(target).use { out ->
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
