package dev.pacer.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pacer.container
import dev.pacer.core.Codec
import dev.pacer.core.Gpx
import dev.pacer.core.RunDescription
import dev.pacer.core.TrackPoint
import dev.pacer.core.formatDuration
import dev.pacer.core.formatMiles
import dev.pacer.core.formatMmSs
import dev.pacer.core.formatPace
import dev.pacer.core.paceSecPerMile
import dev.pacer.data.RunEntity
import dev.pacer.data.RunStatus
import dev.pacer.data.decodedPlan
import dev.pacer.ui.theme.PacerAmber
import dev.pacer.ui.theme.PacerGreen
import dev.pacer.ui.theme.PacerRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val STRAVA_UPLOAD_URL = "https://www.strava.com/upload/select"

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val runs by context.container.runs.history.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("History", style = MaterialTheme.typography.headlineSmall)
        }
        if (runs.isEmpty()) Text("No runs yet.", modifier = Modifier.padding(16.dp))
        LazyColumn(verticalArrangement = Gap, modifier = Modifier.padding(top = 8.dp)) {
            items(runs, key = { it.id }) { r ->
                Card(onClick = { onOpen(r.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startWallMs)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(r.title + if (r.status == RunStatus.FINISHED) " (not saved)" else "", style = MaterialTheme.typography.titleMedium)
                        Text("${formatMiles(r.distanceM)} mi · ${formatDuration(r.movingMs)} · ${formatPace(paceSecPerMile(r.movingMs, r.distanceM))}/mi")
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryScreen(runId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.runs
    val scope = rememberCoroutineScope()
    val run by repo.observe(runId).collectAsStateWithLifecycle(null)
    var points by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(runId) { points = repo.points(runId) }
    val r = run ?: return

    val splits = Codec.decodeSplits(r.splits)
    val intervals = Codec.decodeIntervals(r.intervals)
    val plan = r.decodedPlan()

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
        item {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone) { Text("Close") }
                Text(r.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            if (points.size >= 2) {
                RunMap(points, points.size, null, Modifier.fillMaxWidth().height(260.dp), live = false)
            }
            Column(Modifier.padding(16.dp)) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(r.startWallMs)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Metric("Miles", formatMiles(r.distanceM), Modifier.weight(1f))
                    Metric("Moving", formatDuration(r.movingMs), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Metric("Avg pace", formatPace(paceSecPerMile(r.movingMs, r.distanceM)), Modifier.weight(1f))
                    Metric("Elapsed", formatDuration(r.elapsedMs), Modifier.weight(1f))
                }
                if (points.isNotEmpty()) {
                    // Track continuity check (pauses excluded): the screen-off test looks at this.
                    val gap = points.zipWithNext().filter { !it.second.segmentStart }
                        .maxOfOrNull { it.second.wallTimeMs - it.first.wallTimeMs } ?: 0
                    Text(
                        "GPS: ${points.size} points, longest gap ${"%.1f".format(gap / 1000.0)} s",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Actions(r, points, onDone, onDelete = { confirmDelete = true })
            }
        }
        if (splits.isNotEmpty()) {
            item { Header("Mile splits") }
            items(splits.size) { i ->
                Line("Mile ${i + 1}", formatDuration(splits[i]))
            }
        }
        if (intervals.isNotEmpty()) {
            item { Header("Intervals (actual vs target)") }
            items(intervals) { ir ->
                val target = ir.interval.targetPaceSec
                val actual = ir.paceSec
                val tol = plan.toleranceFor(ir.interval)
                val color = when {
                    target == null || actual == null -> MaterialTheme.colorScheme.onSurface
                    actual > target + tol -> PacerAmber
                    actual < target - tol -> PacerRed
                    else -> PacerGreen
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(ir.interval.label, Modifier.weight(1f))
                    Text(formatPace(actual), color = color, fontWeight = FontWeight.Bold)
                    Text(target?.let { "  / ${formatMmSs(it)}" } ?: "  / –", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (r.status == RunStatus.SAVED) "Delete this run?" else "Discard this run?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.delete(r.id); onDone() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Actions(r: RunEntity, points: List<TrackPoint>, onDone: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Gap) {
        if (r.status == RunStatus.FINISHED) {
            Row(horizontalArrangement = Gap) {
                Button(onClick = { scope.launch { context.container.runs.markSaved(r.id); onDone() } }, Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(onClick = onDelete, Modifier.weight(1f)) { Text("Discard") }
            }
        }
        val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            if (uri != null) scope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(gpxText(r, points).toByteArray()) }
                    }
                }
                toast(context, ok.fold({ "GPX saved" }, { "Couldn't save GPX: ${it.message}" }))
            }
        }
        Row(horizontalArrangement = Gap) {
            OutlinedButton(
                onClick = {
                    if (points.isEmpty()) toast(context, "No GPS points were recorded for this run")
                    else saver.launch(gpxFileName(r))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Export GPX") }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(STRAVA_UPLOAD_URL))) },
                modifier = Modifier.weight(1f),
            ) { Text("Upload to Strava") }
        }
        TextButton(onClick = {
            if (points.isEmpty()) toast(context, "No GPS points were recorded for this run")
            else scope.launch {
                runCatching { shareGpx(context, r, points) }.onFailure { toast(context, "Couldn't share GPX: ${it.message}") }
            }
        }) { Text("Share GPX…") }
        Text(
            "Strava upload: Export GPX (e.g. to Downloads), then Upload to Strava and choose the file.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (r.status == RunStatus.SAVED) TextButton(onClick = onDelete) { Text("Delete run") }
    }
}

@Composable
private fun Header(t: String) {
    HorizontalDivider()
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
}

@Composable
private fun Line(a: String, b: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(a, Modifier.weight(1f))
        Text(b, fontWeight = FontWeight.Bold)
    }
}

private fun gpxText(r: RunEntity, points: List<TrackPoint>): String {
    val desc = RunDescription.of(r.decodedPlan(), r.distanceM, r.movingMs, Codec.decodeSplits(r.splits), Codec.decodeIntervals(r.intervals))
    return Gpx.write(r.title, desc, points)
}

private fun gpxFileName(r: RunEntity): String =
    "Pacer_" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(Date(r.startWallMs)) + ".gpx"

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

private suspend fun shareGpx(context: Context, r: RunEntity, points: List<TrackPoint>) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "gpx").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, gpxFileName(r)).apply { writeText(gpxText(r, points)) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    // Few apps register for application/gpx+xml, so share as a generic file to reach Drive, Files, Gmail etc.
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, r.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, "Share GPX"))
}
