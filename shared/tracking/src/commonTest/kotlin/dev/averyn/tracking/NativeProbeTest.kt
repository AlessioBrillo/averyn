package dev.averyn.tracking

import dev.averyn.domain.Sport
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

class NativeProbeTest {
    @Test
    fun probe() {
        val sb = StringBuilder()

        fun step(
            name: String,
            f: () -> Unit,
        ) {
            try {
                f()
                sb.appendLine("$name ok")
            } catch (e: Throwable) {
                sb.appendLine("$name FAILED ${e::class.qualifiedName}: ${e.message}\n${e.stackTraceToString()}\ncause=${e.cause}")
            }
        }
        step("tmp") { sb.appendLine("tmp=" + SystemTemporaryDirectory) }
        step("random") { Random.nextLong() }
        val dir = Path(SystemTemporaryDirectory, "probe-" + Random.nextLong().toULong())
        step("mkdir") { SystemFileSystem.createDirectories(dir) }
        var store: ActivityStore? = null
        step("store") { store = ActivityStore(dir) }
        step("recorder") {
            val r = ActivityRecorder(store!!)
            r.start("p1", Sport.RUN, 0, 1_000)
        }
        fail(sb.toString())
    }
}
