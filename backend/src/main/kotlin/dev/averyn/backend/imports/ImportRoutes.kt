package dev.averyn.backend.imports

import dev.averyn.api.ActivitySummary
import dev.averyn.backend.activities.ActivityIngest
import dev.averyn.backend.activities.DEFAULT_USER_QUOTA_BYTES
import dev.averyn.backend.activities.MAX_ACTIVITY_BYTES
import dev.averyn.backend.activities.PER_USER
import dev.averyn.backend.activities.errorBody
import dev.averyn.backend.activities.owner
import dev.averyn.backend.activities.receiveToFile
import dev.averyn.backend.auth.OIDC_AUTH
import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.domain.Sport
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import javax.sql.DataSource

/**
 * Imports handled at once: each parses a whole file and replays it. Fewer than the uploads, because an import
 * costs CPU, not just disk.
 */
const val MAX_CONCURRENT_IMPORTS = 2

private val GPX_XML = ContentType("application", "gpx+xml")
private val GPX_TYPES = listOf(GPX_XML, ContentType.Application.Xml, ContentType.Text.Xml)

/** A FIT file has no registered type; the ones clients use are accepted, and the parser checks the content. */
private val FIT_TYPES =
    listOf(
        ContentType("application", "vnd.ant.fit"),
        ContentType("application", "fit"),
        ContentType.Application.OctetStream,
    )

private val ARCHIVE_TYPES =
    listOf(
        ContentType("application", "zip"),
        ContentType("application", "x-zip-compressed"),
        ContentType.Application.OctetStream,
    )

private fun ImportFormat.acceptedTypes() = if (this == ImportFormat.GPX) GPX_TYPES else FIT_TYPES

/** The answer of an import: the activity that was created (or found) and what the importer read. */
@Serializable
data class ImportResponse(
    val activity: ActivitySummary,
    val `import`: ImportReport,
)

/**
 * `POST /v1/imports/gpx` (TDD-0008) and `POST /v1/imports/fit` (TDD-0013): import a file as an activity, owner-only.
 *
 * The body is the file itself, not a form: the browser streams it and the server writes it to disk while
 * hashing it. The sport is explicit for a GPX file (`?sport=RUN`): its `<type>` is free text and guessing it would
 * label the activity with the wrong sport. A FIT file declares its sport as an enum, so there `?sport=` is optional
 * and wins when given.
 *
 * The same file is imported once per owner: the second import answers `200` with the activity that exists.
 * A file that is not a track answers `422` with a stable code and a message that never carries a coordinate.
 */
fun Application.imports(
    db: DataSource,
    raw: RawObjectStore,
    maxBytes: Long = MAX_ACTIVITY_BYTES,
    importSlots: Semaphore = Semaphore(MAX_CONCURRENT_IMPORTS),
    quotaBytes: Long = DEFAULT_USER_QUOTA_BYTES,
    maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
    archiveLimits: ArchiveLimits = ArchiveLimits(),
) {
    val service = ImportService(ActivityIngest(db, raw, quotaBytes), quotaBytes = quotaBytes)
    val archives = ArchiveImporter(service, archiveLimits)

    routing {
        authenticate(OIDC_AUTH) {
            rateLimit(PER_USER) {
                for (format in ImportFormat.entries) {
                    post("/v1/imports/${format.source}") {
                        val sportName = call.request.queryParameters["sport"]
                        val sport = Sport.entries.firstOrNull { it.name == sportName }
                        // GPX needs the sport from the user; a FIT file brings its own, so the parameter is optional
                        // there, but a value that is not a sport is still refused.
                        val sportRequired = format == ImportFormat.GPX
                        if (sport == null && (sportRequired || sportName != null)) {
                            return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf(
                                    "error" to "invalid_sport",
                                    "message" to "the sport must be one of ${Sport.entries.joinToString(",")}",
                                ),
                            )
                        }
                        val contentType = call.request.contentType()
                        if (format.acceptedTypes().none { it.match(contentType) }) {
                            return@post call.respond(
                                HttpStatusCode.UnsupportedMediaType,
                                mapOf(
                                    "error" to "unsupported_media_type",
                                    "message" to "the file must be a ${format.name} file",
                                ),
                            )
                        }
                        val owner = call.owner(db) ?: return@post
                        if (!importSlots.tryAcquire()) {
                            call.response.header(HttpHeaders.RetryAfter, "5")
                            return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                errorBody("too many imports, retry later"),
                            )
                        }
                        try {
                            withContext(Dispatchers.IO) {
                                val dir = Files.createTempDirectory("averyn-import-")
                                try {
                                    val file = dir.resolve("upload.${format.source}")
                                    val sha256 = call.receiveToFile(file, maxBytes)
                                    if (sha256 == null) {
                                        call.respond(
                                            HttpStatusCode.PayloadTooLarge,
                                            errorBody("the file is too large"),
                                        )
                                        return@withContext
                                    }
                                    when (val result = service.import(owner, file, sha256, format, sport)) {
                                        is ImportResult.Created ->
                                            call.respond(
                                                HttpStatusCode.Created,
                                                ImportResponse(result.summary, result.report),
                                            )
                                        is ImportResult.Existing ->
                                            call.respond(
                                                HttpStatusCode.OK,
                                                ImportResponse(result.summary, result.report),
                                            )
                                        is ImportResult.Invalid ->
                                            call.respond(
                                                HttpStatusCode.UnprocessableEntity,
                                                mapOf("error" to result.code, "message" to result.message),
                                            )
                                        ImportResult.QuotaExceeded ->
                                            call.respond(
                                                HttpStatusCode.InsufficientStorage,
                                                errorBody("storage quota exceeded"),
                                            )
                                        ImportResult.AccountDeleted ->
                                            call.respond(HttpStatusCode.Forbidden, errorBody("account was deleted"))
                                    }
                                } finally {
                                    dir.toFile().deleteRecursively()
                                }
                            }
                        } finally {
                            importSlots.release()
                        }
                    }
                }

                post("/v1/imports/archive") {
                    val sportName = call.request.queryParameters["sport"]
                    val sport = Sport.entries.firstOrNull { it.name == sportName }
                    if (sport == null && sportName != null) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "invalid_sport",
                                "message" to "the sport must be one of ${Sport.entries.joinToString(",")}",
                            ),
                        )
                    }
                    if (ARCHIVE_TYPES.none { it.match(call.request.contentType()) }) {
                        return@post call.respond(
                            HttpStatusCode.UnsupportedMediaType,
                            mapOf("error" to "unsupported_media_type", "message" to "the file must be a ZIP archive"),
                        )
                    }
                    val owner = call.owner(db) ?: return@post
                    // One slot for the whole archive: it is the heaviest request there is.
                    if (!importSlots.tryAcquire()) {
                        call.response.header(HttpHeaders.RetryAfter, "5")
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            errorBody("too many imports, retry later"),
                        )
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val dir = Files.createTempDirectory("averyn-import-")
                            try {
                                val file = dir.resolve("upload.zip")
                                if (call.receiveToFile(file, maxArchiveBytes) == null) {
                                    call.respond(HttpStatusCode.PayloadTooLarge, errorBody("the archive is too large"))
                                    return@withContext
                                }
                                when (val result = archives.import(owner, file, sport)) {
                                    is ArchiveResult.Done -> call.respond(HttpStatusCode.OK, result.report)
                                    is ArchiveResult.Invalid ->
                                        call.respond(
                                            HttpStatusCode.UnprocessableEntity,
                                            mapOf("error" to result.code, "message" to result.message),
                                        )
                                    ArchiveResult.AccountDeleted ->
                                        call.respond(HttpStatusCode.Forbidden, errorBody("account was deleted"))
                                }
                            } finally {
                                dir.toFile().deleteRecursively()
                            }
                        }
                    } finally {
                        importSlots.release()
                    }
                }
            }
        }
    }
}
