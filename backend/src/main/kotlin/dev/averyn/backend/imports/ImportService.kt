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

/** The version of the GPX importer, so a stored import says how it was read. */
const val IMPORT_PARSER_VERSION = "gpx-import-v1"

/** The version of the FIT importer (TDD-0013). */
const val FIT_IMPORT_PARSER_VERSION = "fit-import-v1"

/** The source an imported activity comes from. */
const val SOURCE_GPX = "gpx"
const val SOURCE_FIT = "fit"

/** The formats the importer reads: the source stored with the activity and the version of the reader. */
enum class ImportFormat(
    val source: String,
    val parserVersion: String,
) {
    GPX(SOURCE_GPX, IMPORT_PARSER_VERSION),
    FIT(SOURCE_FIT, FIT_IMPORT_PARSER_VERSION),
}

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
    /** The number of heart-rate readings imported; null when the file was not parsed (a duplicate). */
    val heartRateReadings: Int? = null,
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
 * Imports a GPX or a FIT file as an activity (TDD-0008, TDD-0013).
 *
 * The file the user uploaded is kept byte-for-byte next to the canonical activity file, and the activity is
 * computed by replaying the canonical file with the same code a device uses. The same file is imported once per
 * owner: the second import returns the first one.
 */
class ImportService(
    private val ingest: ActivityIngest,
    private val gpxParser: GpxParser = GpxParser(),
    private val fitParser: FitParser = FitParser(),
    private val quotaBytes: Long = DEFAULT_USER_QUOTA_BYTES,
) {
    fun import(
        ownerId: UUID,
        original: java.nio.file.Path,
        originalSha256: String,
        format: ImportFormat,
        sport: Sport?,
    ): ImportResult {
        // The same file is imported once: the second import says so instead of storing it again.
        ingest.existingBySource(ownerId, originalSha256)?.let { existing ->
            return ImportResult.Existing(
                existing,
                ImportReport(
                    source = format.source,
                    duplicate = true,
                    segments = null,
                    points = null,
                    ignoredExtensions = emptyMap(),
                    parserVersion = format.parserVersion,
                ),
            )
        }

        val originalSize = Files.size(original)
        if (ingest.storedBytesOf(ownerId) + originalSize > quotaBytes) return ImportResult.QuotaExceeded

        val parsed =
            Files.newInputStream(original).use { input ->
                when (format) {
                    ImportFormat.GPX -> gpxParser.parse(input)
                    ImportFormat.FIT -> fitParser.parse(input)
                }
            }
        val track =
            when (parsed) {
                is ImportParseResult.Invalid -> return ImportResult.Invalid(parsed.code, parsed.message)
                is ImportParseResult.Parsed -> parsed.track
            }
        // The sport the user chose wins; otherwise the one the file declares. A file that says nothing the importer
        // knows is refused with a reason: a guessed sport would label the activity wrongly.
        val chosenSport =
            sport ?: track.sport
                ?: return ImportResult.Invalid(
                    "sport_unknown",
                    "the file does not say a sport Averyn records: choose one",
                )

        val clientActivityId = UUID.randomUUID()
        val workDir = Files.createTempDirectory("averyn-import-")
        try {
            val canonicalFile = workDir.resolve("$clientActivityId.jsonl")
            writeActivityFile(
                Path(canonicalFile.toString()),
                TrackCanonicalizer.metadata(clientActivityId.toString(), chosenSport, track),
                TrackCanonicalizer.records(track),
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
                        source = format.source,
                        importVersion = format.parserVersion,
                    )
            ) {
                is IngestResult.Created ->
                    ImportResult.Created(
                        ingested.summary,
                        ImportReport(
                            source = format.source,
                            duplicate = false,
                            segments = track.segments.size,
                            points = track.pointCount,
                            ignoredExtensions = track.ignoredExtensions,
                            parserVersion = format.parserVersion,
                            heartRateReadings = track.heartRates.size,
                        ),
                    )
                // Another import of the same file won the race: the activity that exists is the one to show.
                is IngestResult.Existing ->
                    ImportResult.Existing(
                        ingested.summary,
                        ImportReport(
                            source = format.source,
                            duplicate = true,
                            segments = track.segments.size,
                            points = track.pointCount,
                            ignoredExtensions = track.ignoredExtensions,
                            parserVersion = format.parserVersion,
                            heartRateReadings = track.heartRates.size,
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
