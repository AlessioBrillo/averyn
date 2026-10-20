package dev.averyn.backend.activities

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.tracking.ActivityStore
import kotlinx.io.files.Path
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** How many analyses are computed at the same time: each one replays a whole activity file. */
const val MAX_CONCURRENT_ANALYSES = 2

/** How long the export waits for an analysis slot before it goes without that analysis. */
private const val EXPORT_WAIT_SECONDS = 30L

/** What the analysis of an activity can be. */
sealed interface AnalysisOutcome {
    /** The analysis is ready. */
    data class Ready(
        val analysis: ActivityAnalysis,
    ) : AnalysisOutcome

    /** The activity does not exist, or it is not the caller's: the same 404 as everywhere else. */
    data object NotFound : AnalysisOutcome

    /** The server is already computing as many analyses as it can: retry later. */
    data object Busy : AnalysisOutcome

    /** The analysis could not be computed. The activity itself is untouched. */
    data object Failed : AnalysisOutcome
}

/**
 * The analysis of the activities (TDD-0009).
 *
 * A new activity is analysed when it is stored: the ingest already replays the file, so the analysis costs
 * nothing more than a second pass over the same samples. An activity stored before the analysis existed, or one
 * whose algorithms changed, is analysed the first time it is read: the computation is then paid only by the
 * users who open it.
 */
class AnalysisService(
    private val db: DataSource,
    private val raw: RawObjectStore,
    private val analysisStore: ActivityAnalysisStore = ActivityAnalysisStore(db),
    private val slots: Semaphore = Semaphore(MAX_CONCURRENT_ANALYSES),
) {
    private val log = LoggerFactory.getLogger(AnalysisService::class.java)

    /** The analysis of the activity of the owner: computed on the spot when it is missing or outdated. */
    fun analysisFor(
        ownerId: UUID,
        activityId: UUID,
    ): AnalysisOutcome =
        try {
            val activity = activityOf(ownerId, activityId) ?: return AnalysisOutcome.NotFound
            val stored = analysisStore.find(ownerId, activityId)
            if (stored != null && analysisStore.isCurrent(activityId)) return AnalysisOutcome.Ready(stored)

            // A request does not wait: the caller is told to come back later, like for an upload.
            if (!slots.tryAcquire()) return AnalysisOutcome.Busy
            try {
                val analysis = computeAndStore(activityId, activity.first, activity.second)
                AnalysisOutcome.Ready(analysis)
            } finally {
                slots.release()
            }
        } catch (e: Exception) {
            // Never the coordinates, never the file: what failed and which activity it was.
            log.warn("could not analyse activity {}: {}", activityId, e.javaClass.simpleName)
            AnalysisOutcome.Failed
        }

    /**
     * The analysis of the activity, whatever its state. Used by the export, which must be complete: it waits for
     * a slot instead of giving up, and it returns null only when the analysis cannot be computed at all.
     */
    fun analysisOrNull(
        ownerId: UUID,
        activityId: UUID,
    ): ActivityAnalysis? =
        try {
            val activity = activityOf(ownerId, activityId) ?: return null
            val stored = analysisStore.find(ownerId, activityId)
            if (stored != null && analysisStore.isCurrent(activityId)) return stored
            // The export waits, but not forever: an export that hangs is worse than an export without one
            // analysis.
            if (!slots.tryAcquire(EXPORT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("the analysis of activity {} waited too long: the export goes without it", activityId)
                return null
            }
            try {
                computeAndStore(activityId, activity.first, activity.second)
            } finally {
                slots.release()
            }
        } catch (e: Exception) {
            // The export of an account must not break because of one analysis.
            log.warn("could not analyse activity {}: {}", activityId, e.javaClass.simpleName)
            null
        }

    /** The activity of the owner: its client id and the key of its raw file. */
    private fun activityOf(
        ownerId: UUID,
        activityId: UUID,
    ): Pair<UUID, String>? =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT client_activity_id, raw_object_key FROM activities WHERE id = ? AND owner_id = ?",
                ).use { stmt ->
                    stmt.setObject(1, activityId)
                    stmt.setObject(2, ownerId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            rs.getObject(1, UUID::class.java) to rs.getString(2)
                        }
                    }
                }
        }

    /** The analysis of the activity, computed from its raw file and stored. */
    private fun computeAndStore(
        activityId: UUID,
        clientActivityId: UUID,
        rawKey: String,
    ): ActivityAnalysis {
        val dir = Files.createTempDirectory("averyn-analysis-")
        try {
            val file = dir.resolve("$clientActivityId.jsonl")
            raw.getToFile(rawKey, file)
            val analysis = ActivityAnalyzer.analyze(ActivityStore(Path(dir.toString())), clientActivityId.toString())
            analysisStore.save(activityId, analysis)
            return analysis
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
