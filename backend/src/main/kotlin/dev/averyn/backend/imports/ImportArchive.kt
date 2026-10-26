package dev.averyn.backend.imports

import dev.averyn.domain.Sport
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * What one request may take from an archive (TDD-0013 §5.3). They are the guard against a decompression bomb, the
 * attack on a ZIP: a small file that expands to gigabytes. Every number is a count of what comes *out* of the
 * decompression, never what the archive says about itself.
 */
data class ArchiveLimits(
    /** Entries the central directory may list at all (directories, notes and photos included). */
    val maxEntries: Int = 5_000,
    /** Activity files imported per request; a bigger archive is imported in several requests. */
    val maxFiles: Int = 100,
    /** The size of one activity file after decompression: the limit of every other raw file. */
    val maxFileBytes: Long = 32L * 1024 * 1024,
    /** The size of everything decompressed in one request. */
    val maxTotalBytes: Long = 256L * 1024 * 1024,
)

/** The size of the upload itself, larger than a single file: an export holds hundreds of activities. */
const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

/** What happened to one entry of the archive. */
@Serializable
data class ArchiveEntryResult(
    val name: String,
    /** `created`, `duplicate`, `refused` or `skipped`. */
    val status: String,
    /** The id of the activity, for `created` and `duplicate`. */
    val activityId: String? = null,
    /** The stable code of the refusal or the reason an entry was skipped. */
    val code: String? = null,
    val message: String? = null,
)

/** The answer for a whole archive: one line per entry that was an activity file or looked like one. */
@Serializable
data class ArchiveReport(
    val created: Int,
    val duplicates: Int,
    val refused: Int,
    val skipped: Int,
    /** The files of the archive that are not activity files (notes, photos, a manifest): not read, counted. */
    val ignoredFiles: Int,
    val entries: List<ArchiveEntryResult>,
)

/** What an archive import did. */
sealed interface ArchiveResult {
    data class Done(
        val report: ArchiveReport,
    ) : ArchiveResult

    /** The archive as a whole cannot be imported; nothing was stored. */
    data class Invalid(
        val code: String,
        val message: String,
    ) : ArchiveResult

    /** The owner deleted the account while the archive was being imported. */
    data object AccountDeleted : ArchiveResult
}

/**
 * Imports the activity files of a ZIP archive, one after the other, each through the same [ImportService] as a
 * single upload: the quota, the duplicates and the checks are the same, and so is the file kept byte-for-byte.
 *
 * A file inside the archive that cannot be imported does not stop the others: it is reported with its reason. The
 * archive is read through its central directory (random access), no entry is ever extracted by its name, and every
 * decompressed byte is counted.
 */
class ArchiveImporter(
    private val service: ImportService,
    private val limits: ArchiveLimits = ArchiveLimits(),
) {
    fun import(
        ownerId: UUID,
        archive: Path,
        sport: Sport?,
    ): ArchiveResult {
        val zip =
            try {
                ZipFile(archive.toFile())
            } catch (e: ZipException) {
                return ArchiveResult.Invalid("zip_error", "the file is not a readable ZIP archive")
            } catch (e: IOException) {
                return ArchiveResult.Invalid("zip_error", "the file is not a readable ZIP archive")
            }
        zip.use { file ->
            if (file.size() > limits.maxEntries) {
                return ArchiveResult.Invalid("archive_too_large", "the archive lists too many entries")
            }
            val ignoredFiles = file.entries().asSequence().count { !it.isDirectory && formatOf(it.name) == null }
            val candidates =
                file
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory }
                    .mapNotNull { entry -> formatOf(entry.name)?.let { entry to it } }
                    .sortedBy { it.first.name }
                    .toList()
            if (candidates.size > limits.maxFiles) {
                return ArchiveResult.Invalid(
                    "archive_too_large",
                    "the archive has more than ${limits.maxFiles} activity files: import it in several parts",
                )
            }

            val results = mutableListOf<ArchiveEntryResult>()
            var totalBytes = 0L
            val workDir = Files.createTempDirectory("averyn-archive-")
            try {
                for ((entry, format) in candidates) {
                    val name = displayName(entry.name)
                    val unpacked = workDir.resolve("entry.${format.source}")
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    val written =
                        try {
                            file.getInputStream(entry).use { raw ->
                                val input = if (entry.name.lowercase().endsWith(".gz")) GZIPInputStream(raw) else raw
                                copyCounting(
                                    input,
                                    unpacked,
                                    sha256,
                                    limits.maxFileBytes,
                                    limits.maxTotalBytes - totalBytes,
                                )
                            }
                        } catch (e: IOException) {
                            results +=
                                ArchiveEntryResult(
                                    name,
                                    "refused",
                                    code = "entry_unreadable",
                                    message = "the entry cannot be read",
                                )
                            continue
                        }
                    when (written) {
                        Copy.FileTooBig -> {
                            results +=
                                ArchiveEntryResult(
                                    name,
                                    "refused",
                                    code = "entry_too_large",
                                    message = "the file is too large",
                                )
                            continue
                        }
                        Copy.ArchiveTooBig ->
                            return ArchiveResult.Invalid(
                                "archive_too_large",
                                "the archive is larger than ${limits.maxTotalBytes / (1024 * 1024)} MB once unpacked",
                            )
                        is Copy.Done -> totalBytes += written.bytes
                    }
                    val hash = sha256.digest().joinToString("") { "%02x".format(it) }
                    when (val result = service.import(ownerId, unpacked, hash, format, sport)) {
                        is ImportResult.Created ->
                            results += ArchiveEntryResult(name, "created", activityId = result.summary.id)
                        is ImportResult.Existing ->
                            results += ArchiveEntryResult(name, "duplicate", activityId = result.summary.id)
                        is ImportResult.Invalid ->
                            results += ArchiveEntryResult(name, "refused", code = result.code, message = result.message)
                        ImportResult.QuotaExceeded -> {
                            results +=
                                ArchiveEntryResult(
                                    name,
                                    "refused",
                                    code = "quota_exceeded",
                                    message = "storage quota exceeded",
                                )
                            // Nothing after this fits either: say so for the rest instead of trying each one.
                            candidates.dropWhile { it.first !== entry }.drop(1).forEach {
                                results +=
                                    ArchiveEntryResult(displayName(it.first.name), "skipped", code = "quota_exceeded")
                            }
                            break
                        }
                        ImportResult.AccountDeleted -> return ArchiveResult.AccountDeleted
                    }
                    Files.deleteIfExists(unpacked)
                }
            } finally {
                workDir.toFile().deleteRecursively()
            }
            return ArchiveResult.Done(
                ArchiveReport(
                    created = results.count { it.status == "created" },
                    duplicates = results.count { it.status == "duplicate" },
                    refused = results.count { it.status == "refused" },
                    skipped = results.count { it.status == "skipped" },
                    ignoredFiles = ignoredFiles,
                    entries = results,
                ),
            )
        }
    }

    /** The format an entry is, from its name; null for anything that is not an activity file (notes, photos). */
    private fun formatOf(name: String): ImportFormat? {
        val lower = name.lowercase().removeSuffix(".gz")
        val base = lower.substringAfterLast('/')
        // The folders and the files a file manager adds to an archive are not activities.
        if (base.startsWith(".") || lower.startsWith("__macosx/")) return null
        return when {
            lower.endsWith(".fit") -> ImportFormat.FIT
            lower.endsWith(".gpx") -> ImportFormat.GPX
            else -> null
        }
    }

    /** The name the report shows: the path in the archive, never longer than a line. */
    private fun displayName(name: String): String = name.take(200)

    private sealed interface Copy {
        data class Done(
            val bytes: Long,
        ) : Copy

        data object FileTooBig : Copy

        data object ArchiveTooBig : Copy
    }

    /** Copies [input] to [target] while counting; stops as soon as a limit is passed. */
    private fun copyCounting(
        input: InputStream,
        target: Path,
        digest: MessageDigest,
        maxFile: Long,
        maxArchiveLeft: Long,
    ): Copy {
        var total = 0L
        Files.newOutputStream(target).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxArchiveLeft) return Copy.ArchiveTooBig
                if (total > maxFile) return Copy.FileTooBig
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
        }
        return Copy.Done(total)
    }
}
