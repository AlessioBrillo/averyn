package dev.averyn.backend.analysis

import dev.averyn.backend.activities.ActivityAnalysisStore
import dev.averyn.backend.activities.ActivityAnalyzer
import dev.averyn.backend.connectAndMigrate
import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.tracking.ActivityStore
import kotlinx.io.files.Path
import java.nio.file.Files

/**
 * The backfill of the analysis (TDD-0009): it computes the analysis of the activities that have none, or whose
 * analysis was computed with another version of the algorithms.
 *
 * It is a command, not a job: the backend has no worker and it does not need one. A new activity is analysed
 * when it is stored, and an old one is analysed the first time it is read; the command exists for the operator
 * who wants the whole instance up to date at once, or who wants the numbers before the first user asks for them.
 *
 * It reads the same environment variables as the server (DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD,
 * AVERYN_S3_ENDPOINT, AVERYN_S3_BUCKET, AVERYN_S3_ACCESS_KEY, AVERYN_S3_SECRET_KEY) and is run with the
 * backend on the classpath. From a checkout it is `./gradlew :backend:backfillAnalysis`; in the container the
 * command is in docs/deployment/self-hosting.md.
 */
fun main() {
    val db =
        connectAndMigrate(
            url = env("DATABASE_URL"),
            user = env("DATABASE_USER"),
            password = env("DATABASE_PASSWORD"),
        )
    val raw =
        RawObjectStore(
            endpoint = env("AVERYN_S3_ENDPOINT"),
            bucket = env("AVERYN_S3_BUCKET"),
            accessKey = env("AVERYN_S3_ACCESS_KEY"),
            secretKey = env("AVERYN_S3_SECRET_KEY"),
        )
    val store = ActivityAnalysisStore(db)
    val outdated = store.outdated()
    println("${outdated.size} activity/activities to analyse")
    var done = 0
    var failed = 0
    for (activity in outdated) {
        val dir = Files.createTempDirectory("averyn-backfill-")
        try {
            val file = dir.resolve("${activity.clientActivityId}.jsonl")
            raw.getToFile(activity.rawObjectKey, file)
            val analysis =
                ActivityAnalyzer.analyze(
                    ActivityStore(Path(dir.toString())),
                    activity.clientActivityId.toString(),
                )
            store.save(activity.id, analysis)
            done++
        } catch (e: Exception) {
            // Never the coordinates, never the file: what failed and which activity it was.
            failed++
            println("activity ${activity.id}: ${e.javaClass.simpleName}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
    println("$done analysed, $failed failed")
}

private fun env(name: String): String = System.getenv(name) ?: error("the environment variable $name is not set")
