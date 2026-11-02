package dev.averyn.android

import android.content.Context
import android.net.Uri
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.diagnosticsOf
import dev.averyn.tracking.toJson
import dev.averyn.tracking.writeGpx
import kotlinx.io.asSink
import kotlinx.io.buffered

/** What an export of one stored activity produced; `skippedRecords` > 0 means damaged lines were left out (F5). */
internal data class ExportResult(
    val written: Boolean,
    val skippedRecords: Int = 0,
)

/** Streams and re-serializes every stored sample: real I/O, call it off the main thread. */
internal fun exportGpx(
    context: Context,
    store: ActivityStore,
    activityId: String,
    uri: Uri,
): ExportResult {
    val out = context.contentResolver.openOutputStream(uri) ?: return ExportResult(written = false)
    return out.use { stream ->
        ExportResult(written = true, skippedRecords = writeGpx(store, activityId, stream.asSink().buffered()))
    }
}

/** The `diagnostics-v4` report rebuilt from the stored file, with the device context (no identifiers). */
internal fun exportDiagnostics(
    context: Context,
    store: ActivityStore,
    activityId: String,
    uri: Uri,
): ExportResult {
    val report = store.diagnosticsOf(activityId) ?: return ExportResult(written = false)
    val out = context.contentResolver.openOutputStream(uri) ?: return ExportResult(written = false)
    out.use { it.write(report.toJson(deviceInfo(context)).toByteArray(Charsets.UTF_8)) }
    return ExportResult(written = true)
}

/** The activity file exactly as stored, i.e. byte for byte what sync uploads (ADR-0015). Contains coordinates. */
internal fun exportRaw(
    context: Context,
    store: ActivityStore,
    activityId: String,
    uri: Uri,
): ExportResult {
    val out = context.contentResolver.openOutputStream(uri) ?: return ExportResult(written = false)
    out.use { it.write(store.readRaw(activityId)) }
    return ExportResult(written = true)
}
