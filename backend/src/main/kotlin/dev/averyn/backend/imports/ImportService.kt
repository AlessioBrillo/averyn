package dev.averyn.backend.imports

import dev.averyn.api.ActivitySummary
import dev.averyn.backend.activities.ActivityIngest
import dev.averyn.backend.activities.DEFAULT_USER_QUOTA_BYTES
import dev.averyn.backend.activities.IngestResult
import dev.averyn.domain.Sport
import dev.averyn.tracking.writeActivityFile
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** The version of the importer itself, so a stored import says how it was read. */
const val IMPORT_PARSER_VERSION = "gpx-import-v1"

/** The source an imported activity comes from. */
const val SOURCE_GPX = "gpx"

/**
 * What the importer read out of the file. The user sees it, so nothing is dropped silently: an extension that
 * was found and not imported is counted here, and the original file is kept untouched.
 */
@Serializable
data class ImportReport(
    val source: String,
    /** True when this exact file was already imported: nothing was stored a second time. */
    val duplicate: Boolean,
    /** The number of track segments, null when the file was not parsed (a duplicate). */
    val segments: Int?,
    /** The number of track points, null when the file was not parsed (a duplicate). */
    val points: Int?,
    /** The extensions found in the file and not imported, by name. */
    val ignoredExtensions: Map<String, Int>,
    val parserVersion: String,
)

/** What an import did (TDD-0008). */
sealed interface ImportResult {
    data class Created(
        val summary: ActivitySummary,
        val report: ImportReport,
    ) : ImportResult

    /** The same file was already imported: the existing activity is returned, nothing is stored again. */
    data class Existing(
        val summary: ActivitySummary,
        val report: ImportReport,
    ) : ImportResult

    /** The file is not a track this importer can read. */
    data class Invalid(
        val code: String,
        val message: String,
    ) : ImportResult

    /** Storing the file would take the owner over their storage quota. */
    data object QuotaExceeded : ImportResult

    /** The owner deleted the account while the file was being imported. */
    data object AccountDeleted : ImportResult
}

/**
 * Imports a GPX file as an activity (TDD-0008).
 *
 * The file the user uploaded is kept byte-for-byte next to the canonical activity file, and the activity is
 * computed by replaying the canonical file with the same code a device uses. The same file is imported once per
 * owner: the second import returns the first one.
 */
class ImportService(
    private val ingest: ActivityIngest,
    private val parser: GpxParser = GpxParser(),
    private val quotaBytes: Long = DEFAULT_USER_QUOTA_BYTES,
) {
    fun import(
        ownerId: UUID,
        original: java.nio.file.Path,
        originalSha256: String,
        sport: Sport,
    ): ImportResult {
        // The same file is imported once: the second import says so instead of storing it again.
        ingest.existingBySource(ownerId, originalSha256)?.let { existing ->
            return ImportResult.Existing(
                existing,
                ImportReport(
                    source = SOURCE_GPX,
                    duplicate = true,
                    segments = null,
                    points = null,
                    ignoredExtensions = emptyMap(),
                    parserVersion = IMPORT_PARSER_VERSION,
                ),
            )
        }

        val originalSize = Files.size(original)
        if (ingest.storedBytesOf(ownerId) + originalSize > quotaBytes) return ImportResult.QuotaExceeded

        val track =
            when (val parsed = parser.parse(Files.newInputStream(original))) {
                is GpxParseResult.Invalid -> return ImportResult.Invalid(parsed.code, parsed.message)
                is GpxParseResult.Parsed -> parsed.track
            }

        val clientActivityId = UUID.randomUUID()
        val workDir = Files.createTempDirectory("averyn-import-")
        try {
            val canonicalFile = workDir.resolve("$clientActivityId.jsonl")
            writeActivityFile(
                Path(canonicalFile.toString()),
                GpxCanonicalizer.metadata(clientActivityId.toString(), sport, track),
                GpxCanonicalizer.records(track),
            )
            val canonicalSha256 = sha256Of(canonicalFile)
            return when (
                val ingested =
                    ingest.ingestImported(
                        ownerId = ownerId,
                        clientActivityId = clientActivityId,
                        canonicalFile = canonicalFile,
                        canonicalSha256 = canonicalSha256,
                        originalFile = original,
                        originalSha256 = originalSha256,
                        source = SOURCE_GPX,
                        importVersion = IMPORT_PARSER_VERSION,
                    )
            ) {
                is IngestResult.Created ->
                    ImportResult.Created(
                        ingested.summary,
                        ImportReport(
                            source = SOURCE_GPX,
                            duplicate = false,
                            segments = track.segments.size,
                            points = track.pointCount,
                            ignoredExtensions = track.ignoredExtensions,
                            parserVersion = IMPORT_PARSER_VERSION,
                        ),
                    )
                // Another import of the same file won the race: the activity that exists is the one to show.
                is IngestResult.Existing ->
                    ImportResult.Existing(
                        ingested.summary,
                        ImportReport(
                            source = SOURCE_GPX,
                            duplicate = true,
                            segments = track.segments.size,
                            points = track.pointCount,
                            ignoredExtensions = track.ignoredExtensions,
                            parserVersion = IMPORT_PARSER_VERSION,
                        ),
                    )
                is IngestResult.Invalid -> ImportResult.Invalid("import_failed", ingested.reason)
                IngestResult.QuotaExceeded -> ImportResult.QuotaExceeded
                IngestResult.AccountDeleted -> ImportResult.AccountDeleted
                // A deleted activity id and a conflicting id cannot happen for a freshly generated one.
                IngestResult.Conflict, IngestResult.Gone, IngestResult.TooLarge ->
                    ImportResult.Invalid("import_failed", "the imported activity could not be stored")
            }
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private fun sha256Of(file: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
