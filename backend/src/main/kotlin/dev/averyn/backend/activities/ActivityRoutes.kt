package dev.averyn.backend.activities

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.analysis.heartRateZones
import dev.averyn.backend.auth.AccountDeletedException
import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.auth.userIdFor
import dev.averyn.backend.consent.ConsentScope
import dev.averyn.backend.consent.ConsentStore
import dev.averyn.backend.profile.ProfileStore
import dev.averyn.backend.storage.RawObjectStore
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
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
import kotlin.time.Duration.Companion.minutes

/** Upload limit (TDD-0002 §4): 3 h at 1 Hz is about 2 MB, 10 h about 7 MB. */
const val MAX_ACTIVITY_BYTES = 32L * 1024 * 1024

const val DEFAULT_PAGE_SIZE = 50
const val MAX_PAGE_SIZE = 100

private val GEO_JSON = ContentType("application", "geo+json")

/** Uploads handled at once: each holds an IO thread and up to [MAX_ACTIVITY_BYTES] of spooled disk. */
const val MAX_CONCURRENT_UPLOADS = 8

/** Exports handled at once: each streams a whole archive. */
const val MAX_CONCURRENT_EXPORTS = 2

/** Authenticated requests per user and minute (TDD-0005 §5.4); a sync of 30 activities needs about 30. */
const val DEFAULT_REQUESTS_PER_MINUTE = 120

/** The rate limit every authenticated call shares, whatever it does. */
internal val PER_USER = RateLimitName("per-user")

/**
 * `PUT /v1/activities/{clientActivityId}`, `GET /v1/activities` (list), `GET /v1/activities/{id}` and
 * `GET /v1/activities/{id}/track`, owner-only (TDD-0002 §5.1, TDD-0003 §5.1); `DELETE /v1/activities/{id}`,
 * `DELETE /v1/me` and `GET /v1/me/export` (TDD-0004). When all [uploadSlots] (or [exportSlots]) are taken a further
 * upload (export) gets `503` + `Retry-After`, which the apps treat as retryable. A deleted account gets `403` on
 * everything but repeating `DELETE /v1/me`.
 *
 * Per user (TDD-0005): at most [requestsPerMinute] authenticated requests a minute (`429` + `Retry-After`) and
 * [quotaBytes] of raw files (`507`, which the apps retry once the user has freed space). The slots above stay: they
 * protect the process, the per-user limits protect the other users.
 *
 * ponytail: one in-memory token bucket per user, single node; a shared store if the backend ever runs replicated.
 */
fun Application.activities(
    db: DataSource,
    raw: RawObjectStore,
    maxBytes: Long = MAX_ACTIVITY_BYTES,
    uploadSlots: Semaphore = Semaphore(MAX_CONCURRENT_UPLOADS),
    exportSlots: Semaphore = Semaphore(MAX_CONCURRENT_EXPORTS),
    quotaBytes: Long = DEFAULT_USER_QUOTA_BYTES,
    requestsPerMinute: Int = DEFAULT_REQUESTS_PER_MINUTE,
    consentStore: ConsentStore = ConsentStore(db),
    analysisService: AnalysisService = AnalysisService(db, raw),
    userData: UserData = UserData(db, raw, analysisService = analysisService),
    recordStore: PersonalRecordStore = PersonalRecordStore(db),
    profileStore: ProfileStore = ProfileStore(db),
) {
    val ingest = ActivityIngest(db, raw, quotaBytes)
    install(RateLimit) {
        register(PER_USER) {
            rateLimiter(limit = requestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> checkNotNull(call.principal<JWTPrincipal>()).payload.subject }
        }
    }

    routing {
        authenticate(OIDC_AUTH) {
            rateLimit(PER_USER) {
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
                            call.respond(
                                HttpStatusCode.Gone,
                                errorBody("activity was deleted and is not accepted again"),
                            )
                        IngestResult.AccountDeleted ->
                            call.respond(HttpStatusCode.Forbidden, errorBody("account was deleted"))
                        IngestResult.QuotaExceeded ->
                            call.respond(HttpStatusCode.InsufficientStorage, errorBody("storage quota exceeded"))
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

                // RR sidecar (Health class, ADR-0020/0021): uploaded as a separate object next to the
                // activity, consent-gated, tombstoned independently on consent revocation.
                put("/v1/activities/{clientActivityId}/rr") {
                    val clientId =
                        call.parameters["clientActivityId"]?.toUuidOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                    val owner = call.owner(db) ?: return@put
                    if (!withContext(Dispatchers.IO) { consentStore.isGranted(owner, ConsentScope.RR_RECORDING) }) {
                        return@put call.respond(
                            HttpStatusCode.Forbidden,
                            errorBody("rr_recording consent not granted"),
                        )
                    }
                    if (withContext(Dispatchers.IO) { ingest.rrDeleted(owner, clientId) }) {
                        return@put call.respond(
                            HttpStatusCode.Gone,
                            errorBody("rr data was deleted"),
                        )
                    }
                    // A sidecar belongs to an activity: without one there is nothing to attach it to, and the
                    // file would sit in the store forever with no way to delete it.
                    if (!withContext(Dispatchers.IO) { ingest.activityExists(owner, clientId) }) {
                        return@put call.respond(
                            HttpStatusCode.NotFound,
                            errorBody("the activity does not exist"),
                        )
                    }
                    val declared = call.request.contentLength()
                    if (declared != null && declared > maxBytes) {
                        return@put call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            errorBody("rr file too large"),
                        )
                    }
                    if (!uploadSlots.tryAcquire()) {
                        call.response.header(HttpHeaders.RetryAfter, "5")
                        return@put call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            errorBody("too many uploads, retry later"),
                        )
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val dir = Files.createTempDirectory("averyn-ingest-rr-")
                            try {
                                val file = dir.resolve("$clientId.rr.jsonl")
                                val sha256 = call.receiveToFile(file, maxBytes)
                                if (sha256 == null) {
                                    call.respond(
                                        HttpStatusCode.PayloadTooLarge,
                                        errorBody("rr file too large"),
                                    )
                                    return@withContext
                                }
                                // The sidecar is a store file: it starts with its own META line, so a reader
                                // knows which activity it belongs to (ADR-0020).
                                val header = Files.newBufferedReader(file).use { reader -> reader.readLine() }
                                if (header == null || !header.startsWith("META ")) {
                                    call.respond(
                                        HttpStatusCode.UnprocessableEntity,
                                        errorBody("the sidecar has no metadata line"),
                                    )
                                    return@withContext
                                }
                                // A sidecar that replaces another one: the old one is not stored twice, and its
                                // size is not counted twice against the quota.
                                val previous = ingest.rrSidecar(owner, clientId)
                                val stored = ingest.storedBytesOf(owner) - (previous?.second ?: 0L)
                                if (stored + Files.size(file) > quotaBytes) {
                                    call.respond(
                                        HttpStatusCode.InsufficientStorage,
                                        errorBody("storage quota exceeded"),
                                    )
                                    return@withContext
                                }
                                val key = "raw/$owner/$clientId/rr/$sha256.jsonl"
                                raw.put(key, file)
                                if (!withContext(Dispatchers.IO) {
                                        ingest.recordRrSidecar(owner, clientId, key, sha256, Files.size(file))
                                    }
                                ) {
                                    // The activity is not the caller's after all: the file goes with it.
                                    raw.delete(key)
                                    call.respond(HttpStatusCode.NotFound, errorBody("the activity does not exist"))
                                    return@withContext
                                }
                                // The old sidecar is not the one the row points at anymore: it goes, or it would
                                // stay in the store forever with no way to delete it.
                                previous?.first?.takeIf { it != key }?.let { old ->
                                    runCatching { raw.delete(old) }
                                }
                                call.respond(HttpStatusCode.Created)
                            } finally {
                                dir.toFile().deleteRecursively()
                            }
                        }
                    } finally {
                        uploadSlots.release()
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

                // The analysis of the activity: the automatic splits, the elevation and the series (TDD-0009).
                // It is computed when it is missing or when the algorithms changed, and it is stored.
                get("/v1/activities/{id}/analysis") {
                    val id =
                        call.parameters["id"]?.toUuidOrNull()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, errorBody("malformed activity id"))
                    val owner = call.owner(db) ?: return@get
                    when (val outcome = withContext(Dispatchers.IO) { analysisService.analysisFor(owner, id) }) {
                        is AnalysisOutcome.Ready -> {
                            // The zones depend on a setting of the user: they are computed here, never stored.
                            val maxHr = withContext(Dispatchers.IO) { profileStore.get(owner).maxHrBpm }
                            call.respond(outcome.analysis.withZones(maxHr))
                        }
                        AnalysisOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, errorBody("not found"))
                        AnalysisOutcome.Busy -> {
                            call.response.header(HttpHeaders.RetryAfter, "5")
                            call.respond(HttpStatusCode.ServiceUnavailable, errorBody("busy, retry later"))
                        }
                        AnalysisOutcome.Failed ->
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                errorBody("the analysis could not be computed"),
                            )
                    }
                }

                // The personal records of the caller: the fastest effort of every sport and distance
                // (TDD-0010). The numbers come from the stored best efforts: the browser never computes them.
                get("/v1/me/records") {
                    val owner = call.owner(db) ?: return@get
                    call.respond(withContext(Dispatchers.IO) { recordStore.records(owner) })
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
                        // Starting the ZIP answers 200 before a missing file can be found: look first, so an export
                        // that cannot be complete is an error the client sees, not a corrupt archive it cannot tell.
                        val missing = withContext(Dispatchers.IO) { userData.missingRawObjects(owner) }
                        if (missing.isNotEmpty()) {
                            return@get call.respond(
                                HttpStatusCode.InternalServerError,
                                errorBody("export unavailable: ${missing.size} stored file(s) are missing"),
                            )
                        }
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
}

/**
 * The caller's Averyn user id, or null after answering `403` because the account was deleted (ADR-0017).
 */
internal suspend fun ApplicationCall.owner(
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

internal fun errorBody(message: String) = mapOf("error" to message)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

/**
 * Streams the request body to [target], hashing as it goes. Returns the lower-case hex SHA-256, or null as soon
 * as more than [maxBytes] have arrived (the rest is not read).
 */
internal suspend fun ApplicationCall.receiveToFile(
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

/** The analysis with the heart-rate zones of the user's maximum (`hr-zones-v1`), when there is both. */
private fun ActivityAnalysis.withZones(maxHrBpm: Int?): ActivityAnalysis {
    val histogram = series.heartRate.timeAtBpm
    return if (maxHrBpm == null || histogram.timeMs.isEmpty()) {
        this
    } else {
        copy(heartRateZones = heartRateZones(histogram, maxHrBpm))
    }
}
