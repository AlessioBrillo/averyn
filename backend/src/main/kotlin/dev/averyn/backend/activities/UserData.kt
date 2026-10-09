package dev.averyn.backend.activities

import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.exportGpxToFile
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.sql.DataSource

/** One activity in `activities.json`: its summary and how many unreadable raw lines the GPX had to skip (F5). */
@Serializable
data class ExportedActivity(
    val activity: ActivitySummary,
    val gpxSkippedLines: Int,
)

@Serializable
data class ExportManifest(
    val exportedAt: String,
    val activities: List<ExportedActivity>,
)

/**
 * Deleting and exporting what a user has stored (ADR-0017, TDD-0004). Blocking (JDBC, S3): call from
 * `Dispatchers.IO`. Every step is safe to repeat, so a failure half-way is finished by calling it again.
 */
class UserData(
    private val db: DataSource,
    private val raw: RawObjectStore,
) {
    /**
     * Removes one activity and remembers its id so that a device retry gets `410`. Returns false when the caller
     * has no such activity (also when it is someone else's). The object goes first: a failure leaves a row without
     * its file (a retry completes it), never a file without a row.
     */
    fun deleteActivity(
        ownerId: UUID,
        id: UUID,
    ): Boolean {
        val found =
            db.connection.use { conn ->
                val sql = "SELECT raw_object_key, client_activity_id FROM activities WHERE id = ? AND owner_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, ownerId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) to rs.getObject(2, UUID::class.java) else null
                    }
                }
            } ?: return false
        raw.delete(found.first)
        inTransaction { conn ->
            conn.prepareStatement("DELETE FROM activities WHERE id = ? AND owner_id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, ownerId)
                stmt.executeUpdate()
            }
            val tombstone =
                "INSERT INTO deleted_activities (owner_id, client_activity_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
            conn.prepareStatement(tombstone).use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setObject(2, found.second)
                stmt.executeUpdate()
            }
        }
        return true
    }

    /**
     * Deletes everything Averyn holds for the user and flags the account. The flag comes first, so the account
     * stops working at once; the rest can be repeated until it succeeds. The identity at the IdP is not touched.
     */
    fun deleteAccount(ownerId: UUID) {
        inTransaction { conn ->
            val flag = "UPDATE users SET deleted_at = COALESCE(deleted_at, now()) WHERE id = ?"
            conn.prepareStatement(flag).use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.executeUpdate()
            }
        }
        raw.deletePrefix("raw/$ownerId/") // also removes objects orphaned by failed uploads
        inTransaction { conn ->
            for (table in listOf("activities", "deleted_activities")) {
                conn.prepareStatement("DELETE FROM $table WHERE owner_id = ?").use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * Writes a ZIP of everything the user has to [out]: each raw file unchanged, a GPX of it, and `activities.json`
     * (last, because it records what the GPX skipped). One raw file is in memory at a time.
     */
    fun export(
        ownerId: UUID,
        out: OutputStream,
    ) {
        val rows =
            db.connection.use { conn ->
                val sql =
                    "SELECT raw_object_key, $SUMMARY_COLUMNS FROM activities WHERE owner_id = ? ORDER BY started_at, id"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.toSummary(columnOffset = 1)) }
                    }
                }
            }
        val workDir = Files.createTempDirectory("averyn-export-")
        try {
            val store = ActivityStore(Path(workDir.toString()))
            val exported = ArrayList<ExportedActivity>()
            ZipOutputStream(out).use { zip ->
                for ((key, summary) in rows) {
                    val id = summary.clientActivityId
                    val bytes = raw.get(key)
                    zip.putNextEntry(ZipEntry("raw/$id.jsonl"))
                    zip.write(bytes)
                    zip.closeEntry()

                    val rawFile = workDir.resolve("$id.jsonl")
                    val gpx = workDir.resolve("$id.gpx")
                    Files.write(rawFile, bytes)
                    val skipped = store.exportGpxToFile(id, gpx.toString())
                    zip.putNextEntry(ZipEntry("gpx/$id.gpx"))
                    Files.copy(gpx, zip)
                    zip.closeEntry()
                    Files.delete(gpx)
                    Files.delete(rawFile)

                    exported += ExportedActivity(summary, skipped)
                }
                zip.putNextEntry(ZipEntry("activities.json"))
                zip.write(Json.encodeToString(ExportManifest(Instant.now().toString(), exported)).toByteArray())
                zip.closeEntry()
            }
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private fun inTransaction(block: (Connection) -> Unit) {
        db.connection.use { conn ->
            conn.autoCommit = false
            try {
                block(conn)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
}
