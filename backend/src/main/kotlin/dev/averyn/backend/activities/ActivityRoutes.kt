package dev.averyn.backend.activities

import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.auth.userIdFor
import dev.averyn.backend.storage.RawObjectStore
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
import io.ktor.server.response.respondText
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

/**
 * `PUT /v1/activities/{clientActivityId}`, `GET /v1/activities` (list), `GET /v1/activities/{id}` and
 * `GET /v1/activities/{id}/track`, owner-only (TDD-0002 §5.1, TDD-0003 §5.1). When all
 * [uploadSlots] are taken a further upload gets `503` + `Retry-After`, which the apps treat as retryable.
 *
 * ponytail: the bound is global, not per user. Add per-user quotas / rate limits before any public instance
 * (docs/privacy/threat-model.md).
 */
fun Application.activities(
    db: DataSource,
    raw: RawObjectStore,
    maxBytes: Long = MAX_ACTIVITY_BYTES,
    uploadSlots: Semaphore = Semaphore(MAX_CONCURRENT_UPLOADS),
) {
    val ingest = ActivityIngest(db, raw)

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
                val principal = checkNotNull(call.principal<JWTPrincipal>())
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
                                    ingest.ingest(db.userIdFor(principal), clientId, file, sha256)
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
                val principal = checkNotNull(call.principal<JWTPrincipal>())
                call.respond(withContext(Dispatchers.IO) { ingest.list(db.userIdFor(principal), limit, after) })
            }

            get("/v1/activities/{id}/track") {
                val id =
                    call.parameters["id"]?.toUuidOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                val principal = checkNotNull(call.principal<JWTPrincipal>())
                val feature =
                    withContext(Dispatchers.IO) { ingest.trackGeoJson(db.userIdFor(principal), id) }
                        ?: return@get call.respond(HttpStatusCode.NotFound, errorBody("not found"))
                call.respondText(feature, GEO_JSON)
            }

            get("/v1/activities/{id}") {
                val id =
                    call.parameters["id"]?.toUuidOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                val principal = checkNotNull(call.principal<JWTPrincipal>())
                val summary =
                    withContext(Dispatchers.IO) { ingest.find(db.userIdFor(principal), id) }
                        // Not yours and not existing look identical (S5): never reveal that someone else's id is real.
                        ?: return@get call.respond(HttpStatusCode.NotFound, errorBody("not found"))
                call.respond(summary)
            }
        }
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
