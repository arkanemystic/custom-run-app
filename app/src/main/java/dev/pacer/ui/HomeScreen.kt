package dev.pacer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pacer.container
import dev.pacer.core.Fix
import dev.pacer.core.Plan
import dev.pacer.core.PresetEditor
import dev.pacer.core.Workout
import dev.pacer.core.formatDuration
import dev.pacer.core.formatMmSs
import dev.pacer.data.Settings
import dev.pacer.location.FusedLocationSource
import dev.pacer.location.GpsMode
import dev.pacer.service.RunService
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    draft: Workout?,
    onEditForRun: (Workout) -> Unit,
    onWorkouts: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presets by context.container.presets.presets.collectAsStateWithLifecycle(emptyList())
    val settings by context.container.settings.settings.collectAsStateWithLifecycle(Settings())
    var hasLocation by remember { mutableStateOf(granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var saverWarning by remember { mutableStateOf(false) }

    val selected: Workout? = presets.firstOrNull { it.id == settings.selectedPresetId }
    val workout = draft?.takeIf { it.id == selected?.id } ?: selected

    fun plan(): Plan = workout?.let { Plan.Intervals(it) } ?: Plan.Free(settings.freeTargetPaceSec)

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        RunService.start(context, plan())
    }
    fun startNow() {
        if (!granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RunService.start(context, plan())
        }
    }
    fun tryStart() {
        val pm = context.getSystemService(PowerManager::class.java)
        val restricted = pm.isPowerSaveMode && pm.locationPowerSaveMode != PowerManager.LOCATION_MODE_NO_CHANGE
        if (restricted) saverWarning = true else startNow()
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = it[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    val gps = gpsStatus(hasLocation)

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pacer", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onHistory) { Text("History") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Text(
            when {
                !hasLocation -> "Location permission needed"
                gps == null -> "Location: searching…"
                else -> "Location: ±${gps.accuracyM.toInt()} m"
            },
            color = if (gps != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Workout", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onWorkouts) { Text("Manage") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Gap) {
            items(presets, key = { it.id }) { p ->
                val isSel = p.id == settings.selectedPresetId
                val shown = if (isSel) workout ?: p else p
                PresetRow(
                    title = p.name + if (isSel && draft?.id == p.id && draft != p) " (edited for this run)" else "",
                    subtitle = describe(shown),
                    selected = isSel,
                    onClick = {
                        if (isSel) onEditForRun(shown) else scope.launch { context.container.settings.setSelectedPreset(p.id) }
                    },
                )
            }
            item {
                val isSel = selected == null
                PresetRow(
                    title = "Free run",
                    subtitle = "No intervals" + (settings.freeTargetPaceSec?.let { " · target ${formatMmSs(it)}/mi" } ?: ""),
                    selected = isSel,
                    onClick = { scope.launch { context.container.settings.setSelectedPreset(-1) } },
                )
                if (isSel) {
                    Stepper(
                        "Free run target pace",
                        settings.freeTargetPaceSec?.let { "${formatMmSs(it)} /mi" } ?: "Off",
                        onStep = { d ->
                            scope.launch {
                                val cur = settings.freeTargetPaceSec
                                val next = if (cur == null) PresetEditor.DEFAULT_PACE_SEC
                                else if (d < 0 && cur <= PresetEditor.MIN_PACE_SEC) null
                                else PresetEditor.pace(cur, d * PresetEditor.PACE_STEP_SEC)
                                context.container.settings.setFreeTargetPace(next)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        trailing = {
                            if (settings.freeTargetPaceSec != null) {
                                TextButton(onClick = { scope.launch { context.container.settings.setFreeTargetPace(null) } }) { Text("Off") }
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (!hasLocation) {
                    locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else tryStart()
            },
            modifier = Modifier.fillMaxWidth().height(96.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(),
        ) {
            Text(if (hasLocation) "Start" else "Allow location", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (saverWarning) {
        AlertDialog(
            onDismissRequest = { saverWarning = false },
            title = { Text("Battery Saver is on") },
            text = {
                Text(
                    "Battery Saver may stop or throttle location while the screen is off, which can leave gaps " +
                        "in your track. Turn it off for the run, or start anyway.",
                )
            },
            confirmButton = { TextButton(onClick = { saverWarning = false; startNow() }) { Text("Start anyway") } },
            dismissButton = { TextButton(onClick = { saverWarning = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PresetRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected && title != "Free run") {
                    Text("Tap to adjust for this run", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

fun describe(w: Workout): String {
    fun pace(p: Int?) = p?.let { " @ ${formatMmSs(it)}" } ?: ""
    val parts = buildList {
        if (w.warmup.durationSec > 0) add("${formatMmSs(w.warmup.durationSec)} warm-up${pace(w.warmup.targetPaceSec)}")
        if (w.repeats > 0) {
            val walk = if (w.walk.durationSec > 0) " + ${formatMmSs(w.walk.durationSec)} walk${pace(w.walk.targetPaceSec)}" else ""
            add("${w.repeats} × ${formatMmSs(w.run.durationSec)} run${pace(w.run.targetPaceSec)}$walk")
        }
        if (w.cooldown.durationSec > 0) add("${formatMmSs(w.cooldown.durationSec)} cool-down${pace(w.cooldown.targetPaceSec)}")
    }
    return parts.joinToString(", ") + " · ${formatDuration(w.totalSec * 1000L)}"
}

private fun granted(context: android.content.Context, p: String) =
    context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/**
 * Balanced, low-rate location only while this screen is started; removed as soon as it isn't.
 * Returns the latest fix or null.
 */
@Composable
private fun gpsStatus(enabled: Boolean): Fix? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fix by remember { mutableStateOf<Fix?>(null) }
    DisposableEffect(enabled, lifecycle) {
        if (!enabled) return@DisposableEffect onDispose { }
        val src = FusedLocationSource(context)
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> src.start(GpsMode.SETUP) { fix = it }
                Lifecycle.Event.ON_STOP -> src.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            src.stop()
        }
    }
    return fix
}
