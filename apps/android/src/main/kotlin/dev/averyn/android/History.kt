package dev.averyn.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.averyn.domain.ActivityState
import dev.averyn.sync.SyncStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private const val HISTORY_LIMIT = 20

private data class HistoryRow(
    val activityId: String,
    val title: String,
    val state: ActivityState?,
    val sync: String,
)

private enum class ExportKind { GPX, DIAGNOSTICS, RAW }

/**
 * Past activities with their exports (TDD-0006 R8): the GPX, the `diagnostics-v3` report and the raw file of any
 * stored activity, not only the one just finished, so a tester can still export after the app was restarted.
 * [refreshKey] changes when something was recorded or finished, which reloads the list.
 */
@Composable
internal fun HistorySection(
    app: AverynApplication,
    refreshKey: Any?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var pending by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) { rows = withContext(Dispatchers.IO) { loadHistory(app) } }

    fun launcherResult(kind: ExportKind): (android.net.Uri?) -> Unit =
        { uri ->
            val id = pending
            pending = null
            if (uri != null && id != null) {
                scope.launch(Dispatchers.IO) {
                    val result =
                        runCatching {
                            when (kind) {
                                ExportKind.GPX -> exportGpx(context, app.store, id, uri)
                                ExportKind.DIAGNOSTICS -> exportDiagnostics(context, app.store, id, uri)
                                ExportKind.RAW -> exportRaw(context, app.store, id, uri)
                            }
                        }
                    message =
                        result.fold(
                            onSuccess = { exported ->
                                when {
                                    !exported.written -> "Export failed"
                                    exported.skippedRecords > 0 ->
                                        "Exported; ${exported.skippedRecords} damaged record(s) were left out"
                                    else -> "Exported"
                                }
                            },
                            onFailure = { "Export failed" },
                        )
                }
            }
        }

    val gpx =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/gpx+xml"),
            launcherResult(ExportKind.GPX),
        )
    val diagnostics =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
            launcherResult(ExportKind.DIAGNOSTICS),
        )
    val raw =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-ndjson"),
            launcherResult(ExportKind.RAW),
        )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("History")
        if (rows.isEmpty()) Text("No activities yet")
        rows.forEach { row ->
            Text("${row.title} — ${row.state ?: "?"}, sync ${row.sync}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    pending = row.activityId
                    gpx.launch("${row.activityId}.gpx")
                }) { Text("GPX") }
                Button(onClick = {
                    pending = row.activityId
                    diagnostics.launch("${row.activityId}-diagnostics.json")
                }) { Text("Diagnostics") }
                Button(onClick = {
                    pending = row.activityId
                    raw.launch("${row.activityId}.jsonl")
                }) { Text("Raw file") }
            }
        }
        message?.let { Text(it) }
    }
}

private fun loadHistory(app: AverynApplication): List<HistoryRow> {
    val statuses = SyncStatusStore(app.store)
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return app.store
        .list()
        .sortedByDescending { it.startedAtMs }
        .take(HISTORY_LIMIT)
        .map { metadata ->
            HistoryRow(
                activityId = metadata.activityId,
                title = "${metadata.sport} ${format.format(Date(metadata.startedAtMs))}",
                state = app.store.lastStateOf(metadata.activityId),
                sync = statuses.statusOf(metadata.activityId).state.name,
            )
        }
}
